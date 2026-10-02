package com.decent.usbaudio.media3

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.NativeAudioEngine
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ExoPlayer [androidx.media3.exoplayer.audio.AudioSink] that sends PCM directly
 * to a USB Audio Class 2.0 DAC via isochronous transfers, bypassing the entire
 * Android audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * The delegate [DefaultAudioSink] is kept alive (muted) for ExoPlayer's clock
 * and position tracking. Audio data is routed to the USB DAC via a dedicated
 * streaming thread with a producer-consumer queue, decoupling USB timing from
 * the delegate's AudioTrack timing.
 *
 * @param delegate  The [DefaultAudioSink] owned by the ExoPlayer renderer.
 * @param context   Application context for USB device detection and audio routing.
 * @param config    Configuration options (default: bit-perfect enabled, route to speaker).
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: AudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig(),
    private val onDriverOwnsUsbDeviceChanged: ((Boolean) -> Unit)? = null
) : ForwardingAudioSink(delegate) {

    /** Source file bit depth (16, 24, 32). Auto-detected from NativeAudioEngine. */
    private var trackBitDepth: Int = 0

    /** True when the native engine is running. Read by [NativeEngineAwareLoadControl]
     *  to stop ExoPlayer from loading data (prevents SD card I/O contention).
     *  Temporarily set to false during seek to allow one post-seek load. */
    @Volatile
    var isNativeEngineActive: Boolean = false
        private set


    /** File path of the current track. Set internally by [PlayerIntegrationListener]
     *  from the MediaItem URI. When non-null and pointing to a FLAC file, the native
     *  audio engine is used. For HTTP URIs, this is null (ExoPlayer pipeline fallback). */
    @Volatile private var currentTrackPath: String? = null

    /** Clean up a finished native engine and apply deferred USB config.
     *  @return true if an engine was cleaned up (caller should restart playback). */
    private fun cleanupFinishedEngine(): Boolean {
        val engine = nativeEngine
        if (engine != null && !engine.isRunning) {
            engine.destroy()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            windowOffsetUs = -1L
            usbStartMediaTimeNeedsInit = true
            Log.i(TAG, "cleanupFinishedEngine: old engine cleared")

            // Apply deferred USB reconfiguration (cross-rate transition). This runs
            // from PlayerIntegrationListener.onMediaItemTransition(), which fires on
            // the player's application thread — the main thread here, since no
            // custom Looper is passed to ExoPlayer.Builder. configureUsbBitPerfect()
            // does blocking native ioctls plus a flat 50ms sleep for DAC PLL lock,
            // so running it inline would stall the main thread on every gapless
            // transition into a track with a different sample rate/channel count.
            // Backgrounding it is safe: configureUsbBitPerfect() is now guarded by
            // configureLock, so if configure() is also invoked for the new track
            // around the same time (on ExoPlayer's renderer thread), that call
            // simply waits for this one instead of racing it on the same USB
            // device/fd. createEngineIfNeeded() (called right after this returns,
            // still on the main thread) already tolerates usbAudioStream not being
            // ready yet — handleBuffer()'s lazy engine-creation fallback picks it
            // up once this background reconfiguration completes.
            if (hasDeferredConfig) {
                val rate = deferredRate
                val channels = deferredChannels
                val encoding = deferredEncoding
                hasDeferredConfig = false
                Log.i(TAG, "cleanupFinishedEngine: applying deferred config rate=$rate (background)")
                Thread({ configureUsbBitPerfect(rate, channels, encoding) }, "usbDeferredConfig").start()
            }
            return true
        }
        return false
    }

    /** Creates a native engine if the USB stream is ready and no engine exists.
     *  Replaces the streaming thread fallback if one was set up due to rate mismatch. */
    private fun createEngineIfNeeded() {
        if (nativeEngine?.isRunning == true) return  // already running
        val stream = usbAudioStream
        if (stream != null && stream.isAlive) {
            // Clean up dead engine if exists
            val old = nativeEngine
            if (old != null && !old.isRunning) {
                old.destroy()
                nativeEngine = null
                activeEnginePath = null
            }
            if (nativeEngine == null) {
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                startNativeEngineIfFlac(stream)
                // Engine starts paused with engineNeedsInitialSeek = true.
                // Temporarily unblock LoadControl so ExoPlayer sends at least one
                // handleBuffer — needed to capture presentationTimeUs and seek.
                // Without this, the LoadControl blocks immediately and the engine
                // stays paused forever (HTTP→local transition race).
                if (nativeEngine != null) {
                    isNativeEngineActive = false
                }
                Log.i(TAG, "createEngineIfNeeded: engine=${nativeEngine != null}")
            }
        }
    }

    @Volatile private var usbAudioStream: UsbAudioStream? = null
    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    @Volatile private var usbStreamingThread: UsbStreamingThread? = null
    @Volatile private var nativeEngine: NativeAudioEngine? = null
    private val engineLock = Any()

    /** True while an async idle release (stream stop + USB reset + close) runs.
     *  Guards against duplicate/concurrent releases and against [configureUsbBitPerfect]
     *  opening a fresh fd that the closing release would then kill. */
    @Volatile private var idleReleaseInFlight = false
    private val idleReleaseLock = Any()

    /** Serializes calls into [configureUsbBitPerfect]. It can be entered from two
     *  places: synchronously from [configure] (ExoPlayer's renderer thread) and,
     *  for a deferred cross-rate reconfiguration, from a background thread spawned
     *  by [cleanupFinishedEngine] (itself called from the main-thread
     *  onMediaItemTransition callback) — see the comment there for why that call
     *  is backgrounded. Without this lock those two paths could open/configure the
     *  same USB device concurrently. */
    private val configureLock = Any()
    // releaseUsbStream's worst case is bounded by the streaming-thread join (2s),
    // so a configure that arrives in this window keeps up without falling back.
    private val idleReleaseMaxWaitMs = 1500L

    /** Exposed so the host can wait for an in-flight idle release to fully finish
     *  (USB reset + device close) before reopening the DAC through another path. */
    fun isIdleReleaseInFlight(): Boolean = idleReleaseInFlight

    /**
     * True while this driver's exclusive usbfs claims on the DAC are in place (the
     * device is open and the kernel's snd-usb-audio is detached for as long as that
     * is the case, which is what silences every other app).
     *
     * The authoritative counterpart to [onDriverOwnsUsbDeviceChanged]: the claims
     * are taken in [configureUsbBitPerfect], which the deferred cross-rate
     * reconfiguration calls straight from its own thread without passing through
     * [configure] — so a callback-only "does the driver own the DAC" answer from the
     * host can be false while the device is very much claimed.
     */
    val ownsUsbDevice: Boolean
        get() = usbAudioStream != null || usbAudioDevice.isDeviceOpen

    // Cross-thread access (ExoPlayer renderer thread writes these in configure();
    // renderer + main threads read them in getCurrentPositionUs/play/pause).
    @Volatile private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var currentChannelCount: Int = 0
    private var pendingVolume: Float = 1f
    private var delegateMuted: Boolean = false
    private var handleBufferCallCount: Long = 0

    /**
     * Media timeline offset captured from the first buffer's presentationTimeUs
     * after each flush/init. Maps framesWritten=0 to the correct song position.
     * DefaultAudioSink calls this startMediaTimeUs internally.
     */
    private var usbStartMediaTimeUs: Long = 0L
    private var usbStartMediaTimeNeedsInit: Boolean = true
    private var handledEndOfStream: Boolean = false

    /** ExoPlayer's window offset, captured once per track. Never reset by flush.
     *  Used to convert between ExoPlayer timeline and FLAC absolute position. */
    private var windowOffsetUs: Long = -1L

    /** True when the engine was just created and needs its first seek from handleBuffer.
     *  Prevents play() from resuming the engine before the correct position is known. */
    private var engineNeedsInitialSeek: Boolean = false

    /** Path of the file the current native engine is decoding. Used to detect track changes. */
    @Volatile private var activeEnginePath: String? = null

    // ── Gapless (native engine) ────────────────────────────────────
    // The engine is given the next local FLAC in advance (NativeAudioEngine.setNextFd)
    // and continues straight into it on the same USB stream. The player is then
    // moved to that item with a seek purely for its timeline/UI; while that
    // seek is in flight ExoPlayer stops (pause) and flushes this sink, which
    // must NOT pause the engine or touch the USB stream.
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Engine switch count already handled (see NativeAudioEngine.getTrackSwitchCount). */
    @Volatile private var observedTrackSwitches = 0
    /** True from the moment the engine switched files until the player's first
     *  buffer of the new item has anchored the timeline. */
    @Volatile private var gaplessAdvanceInFlight = false
    @Volatile private var gaplessAdvanceStartedMs = 0L
    /** Queue index / path handed to the engine as its next track. */
    @Volatile private var queuedNextIndex = C.INDEX_UNSET
    @Volatile private var queuedNextPath: String? = null


    /** Max queue entries before returning false for backpressure (paces ExoPlayer).
     *  Pause responsiveness is handled by pauseStreaming(), not queue size. */
    private val QUEUE_BACKPRESSURE_THRESHOLD = 16

    /** Tracks ExoPlayer's play/pause state so seek-while-paused doesn't auto-resume. */
    @Volatile private var isPlaying = false

    /**
     * Set by an idle release (pause / stop / screen-off hand-back). The next
     * buffer while playing re-claims the DAC for the current format; without
     * this the rest of the track (until the next item's configure) silently
     * went through the Android audio path — and a DoP stream would have
     * reached it as noise.
     */
    @Volatile private var reclaimAfterIdleRelease = false

    /** Deferred USB reconfiguration — applied after engine finishes playing. */
    private var deferredRate: Int = 0
    private var deferredChannels: Int = 0
    private var deferredEncoding: Int = 0
    private var hasDeferredConfig: Boolean = false


    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        reclaimAfterIdleRelease = false   // configure() (re)opens the USB stream itself
        val enc = inputFormat.pcmEncoding
        if (enc != Format.NO_VALUE) currentEncoding = enc

        // If native engine is still playing the SAME track AND the rate didn't change,
        // don't touch it. This happens when ExoPlayer pre-buffers the next track ~10s
        // before EOF. But if the track or rate changed, destroy and reconfigure.
        if (nativeEngine?.isRunning == true) {
            val trackChanged = currentTrackPath != activeEnginePath
            // During a gapless advance the player is being moved onto the track
            // the engine is ALREADY playing; configure() can run before the
            // main-thread onMediaItemTransition has updated currentTrackPath, so
            // don't mistake that for a track change as long as the format fits.
            val gaplessSameFormat = gaplessAdvanceInFlight &&
                inputFormat.sampleRate == currentSampleRate &&
                inputFormat.channelCount == currentChannelCount
            if (!trackChanged || gaplessSameFormat) {
                // Same track, ExoPlayer pre-buffering — defer reconfiguration
                if (inputFormat.sampleRate != currentSampleRate || inputFormat.channelCount != currentChannelCount) {
                    deferredRate = inputFormat.sampleRate
                    deferredChannels = inputFormat.channelCount
                    deferredEncoding = enc
                    hasDeferredConfig = true
                    Log.i(TAG, "configure: engine running, pre-buffer — deferred rate=${inputFormat.sampleRate}")
                } else {
                    Log.i(TAG, "configure: engine running, same rate — keeping alive")
                }
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
                muteDelegateIfNeeded()
                return
            }
            // Track changed (manual skip) — destroy engine and proceed
            Log.i(TAG, "configure: track changed, destroying engine")
        }
        // Track changed or engine finished — destroy old engine
        val oldEngine = nativeEngine
        if (oldEngine != null) {
            oldEngine.stop()
            oldEngine.destroy()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            Log.i(TAG, "configure: destroyed old engine")
        }

        handleBufferCallCount = 0
        val sr = inputFormat.sampleRate.takeIf { it > 0 }
        val ch = inputFormat.channelCount.takeIf { it > 0 }

        Log.i(TAG, "configure: pcmEncoding=${when(enc) {
            C.ENCODING_PCM_FLOAT -> "FLOAT"; C.ENCODING_PCM_16BIT -> "16BIT"
            C.ENCODING_PCM_24BIT -> "24BIT"; C.ENCODING_PCM_32BIT -> "32BIT"
            else -> "UNKNOWN($enc)"
        }} rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount}")

        if (config.bitPerfectEnabled && sr != null && ch != null) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                configureUsbBitPerfect(sr, ch, enc)
                val usbStreamAlive = usbAudioStream?.isAlive == true
                onDriverOwnsUsbDeviceChanged?.invoke(usbStreamAlive)

                if (usbStreamAlive) {
                    windowOffsetUs = -1L
                    usbStartMediaTimeNeedsInit = true
                    if (config.forceRouteToSpeaker) forceMediaToSpeaker()
                    super.configure(inputFormat, specifiedBufferSize, outputChannels)
                    muteDelegateIfNeeded()
                    Log.i(TAG, "Delegate configured (muted, routed to speaker)")
                    return
                }
                // USB stream creation failed — fall through to delegate path so
                // BitPerfectAudioSink can route via the system audio stack instead
                // of pinning the delegate to the speaker with no USB stream to use.
                Log.w(TAG, "USB stream creation failed — falling back to system audio")
            } else if (device != null) {
                Log.w(TAG, "USB DAC found but no permission — requesting")
                usbAudioDevice.requestPermission(device) { granted ->
                    Log.i(TAG, "USB permission result for bit-perfect streaming: granted=$granted")
                }
            }
        }

        super.configure(inputFormat, specifiedBufferSize, outputChannels)

        if (usbAudioStream != null && !config.bitPerfectEnabled) {
            // Bit-perfect just got disabled: fully hand the DAC back to the
            // system (reset + close), otherwise the usbfs claims persist and
            // every other app stays silent.
            releaseUsbForIdle()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (config.bitPerfectEnabled && reclaimAfterIdleRelease && usbAudioStream == null) {
            // Keep the DAC released while paused, and wait for an in-flight
            // hand-back (USB reset) to finish before claiming it again.
            if (!isPlaying || idleReleaseInFlight) return false
            reclaimAfterIdleRelease = false
            reclaimUsbStream()
        }
        val stream = usbAudioStream
        if (config.bitPerfectEnabled && stream?.isAlive == true) {
            muteDelegateIfNeeded()

            // Fallback engine creation: if no engine and no streaming thread,
            // try creating one now (path and USB rate should both be correct by this point)
            if (nativeEngine == null && usbStreamingThread == null) {
                startNativeEngineIfFlac(stream)
                // Engine starts paused. The usbStartMediaTimeNeedsInit block below
                // will capture presentationTimeUs and seek to the correct position.
            }

            // Capture media timeline offset from first buffer (needed for position tracking)
            if (usbStartMediaTimeNeedsInit && gaplessAdvanceInFlight &&
                nativeEngine?.isRunning == true) {
                // First buffer of the item the engine already continued into
                // (we sought the player to its position 0). Anchor the timeline
                // to this item and leave the engine alone: no seek, no restart —
                // it has been playing this file since the switch.
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                usbStartMediaTimeNeedsInit = false
                windowOffsetUs = presentationTimeUs
                engineNeedsInitialSeek = false
                gaplessAdvanceInFlight = false
                isNativeEngineActive = true
                Log.i(TAG, "Gapless: timeline anchored to next track (windowOffset=$windowOffsetUs, " +
                        "engine already ${nativeEngine?.getPositionUs()?.div(1000)}ms in)")
            } else if (usbStartMediaTimeNeedsInit) {
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                usbStartMediaTimeNeedsInit = false
                // Save window offset once per track (not reset by flush/seek).
                // windowOffset = ExoPlayer timeline position of track start (position 0).
                // On fresh start: initialPlayerPosition=0 → offset = pts (correct).
                // On restore at 158s: initialPlayerPosition=158s → offset = pts - 158s (correct).
                if (windowOffsetUs < 0) {
                    windowOffsetUs = presentationTimeUs - initialPlayerPositionUs
                }
                Log.i(TAG, "startMediaTimeUs=$usbStartMediaTimeUs windowOffset=$windowOffsetUs initialPos=${initialPlayerPositionUs / 1000}ms")

                // After a flush (seek) or initial start, seek the native engine
                // to the correct position and resume it.
                val engine = nativeEngine
                if (engine != null && windowOffsetUs >= 0) {
                    val flacPositionUs = presentationTimeUs - windowOffsetUs
                    if (flacPositionUs >= 0) {
                        engine.seek(flacPositionUs)
                        if (isPlaying) engine.resume()
                        engineNeedsInitialSeek = false
                        Log.i(TAG, "Native engine seek to ${flacPositionUs / 1_000_000}s (playing=$isPlaying)")
                    }
                }
                // Re-block LoadControl now that we have the position.
                // flush() temporarily unblocked it to allow this handleBuffer call.
                if (nativeEngine?.isRunning == true) {
                    isNativeEngineActive = true
                }
            }

            // Native FLAC engine handles decode+USB directly — ignore ExoPlayer data.
            val engine = nativeEngine
            if (engine != null) {
                if (engine.isRunning) {
                    buffer.position(buffer.limit())
                    return true
                }
                // Engine finished playing — clean up for next track.
                // Lazy creation at the top of handleBuffer will create a new engine
                // with the correct currentTrackPath on the next call.
                Log.i(TAG, "Native engine finished — cleaning up for next track")
                engine.destroy()
                nativeEngine = null
                isNativeEngineActive = false
                activeEnginePath = null
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                // Return true for this buffer — next handleBuffer will create new engine
                buffer.position(buffer.limit())
                return true
            }

            val thread = usbStreamingThread ?: return true

            // Backpressure: if queue is nearly full, tell ExoPlayer to retry later.
            // This paces the renderer to the USB DAC's consumption rate without
            // depending on the delegate AudioTrack.
            if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
                return false
            }

            handleBufferCallCount++
            val snapshot: ByteBuffer = buffer.slice().order(buffer.order())

            if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = thread.obtainFloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    if (handleBufferCallCount <= 3) {
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: FLOAT samples=$totalSamples")
                    }
                    thread.enqueue(floatBuf)
                }
            } else {
                val remaining = snapshot.remaining()
                if (remaining > 0) {
                    val rawBytes = thread.obtainByteArray(remaining)
                    snapshot.get(rawBytes)
                    if (handleBufferCallCount <= 3) {
                        val bps = PcmUtils.bytesPerSample(currentEncoding)
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: RAW ${bps*8}bit bytes=$remaining")
                    }
                    thread.enqueueRaw(rawBytes, currentEncoding)
                }
            }

            // Advance buffer and return true — no delegate dependency.
            buffer.position(buffer.limit())
            return true
        }

        unmuteDelegateIfNeeded()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    // ── Position tracking via USB framesWritten ────────────────────────

    private var posLogCount = 0L

    private var engineEndNotified = false

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (config.bitPerfectEnabled) {
            val streamAlive = usbAudioStream?.isAlive == true
            val engine = nativeEngine
            val engineCreated = engine?.isCreated == true

            if (++posLogCount % 500 == 1L) {
                Log.i(TAG, "getPositionUs: streamAlive=$streamAlive engine=$engineCreated " +
                        "running=${engine?.isRunning} window=$windowOffsetUs enginePos=${engine?.getPositionUs()}")
            }

            // Detect engine finished — advance to next track internally.
            // ExoPlayer's renderer never reaches outputStreamEnded because
            // LoadControl blocked loading, so we skip externally via the Player ref.
            if (engine != null && !engine.isRunning && !engineEndNotified) {
                engineEndNotified = true
                Log.i(TAG, "Engine finished — advancing to next track")
                val p = attachedPlayer
                if (p != null) {
                    mainHandler.post { advanceAfterEngineEnd(p) }
                }
            }

            // Gapless: the engine continued into the queued next file without a
            // break. Move the player onto that item (timeline/UI only).
            if (engine != null && engine.isRunning) {
                val switches = engine.getTrackSwitchCount()
                if (switches != observedTrackSwitches) {
                    observedTrackSwitches = switches
                    onGaplessSwitch()
                }
            }
            if (gaplessAdvanceInFlight) {
                if (SystemClock.elapsedRealtime() - gaplessAdvanceStartedMs < GAPLESS_ADVANCE_TIMEOUT_MS) {
                    // Hold ExoPlayer's clock where it is (old item's end, then the
                    // sought position 0 of the new item) until the new item's first
                    // buffer anchors windowOffsetUs.
                    return AudioSink.CURRENT_POSITION_NOT_SET
                }
                Log.w(TAG, "Gapless: player advance did not complete in time — resuming normal position reporting")
                gaplessAdvanceInFlight = false
            }

            // Native engine: absolute FLAC position + window offset
            if (streamAlive && engineCreated && windowOffsetUs >= 0) {
                return windowOffsetUs + engine!!.getPositionUs()
            }

            // ExoPlayer pipeline fallback: relative framesWritten + startMediaTime
            if (streamAlive) {
                if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
                val frames = usbAudioStream?.framesWritten ?: 0L
                return if (currentSampleRate > 0) {
                    usbStartMediaTimeUs + frames * C.MICROS_PER_SECOND / currentSampleRate
                } else AudioSink.CURRENT_POSITION_NOT_SET
            }
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun isEnded(): Boolean {
        if (config.bitPerfectEnabled) {
            val engine = nativeEngine
            // Engine still running → not ended
            if (engine != null && engine.isRunning) return false
            // Engine exists but stopped → it finished (EOF). Signal ended directly.
            // Cannot delegate to super because LoadControl blocked ExoPlayer's loading,
            // so the delegate never reached end-of-stream on its own.
            if (engine != null && !engine.isRunning) return true
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (config.bitPerfectEnabled) {
            // Engine running → has pending data
            if (nativeEngine?.isRunning == true) return true
            if (usbStreamingThread?.hasPendingData() == true) return true
        }
        return super.hasPendingData()
    }

    override fun playToEndOfStream() {
        handledEndOfStream = true
        // Always propagate to delegate — ExoPlayer needs this signal to
        // detect end-of-stream and transition to the next track.
        super.playToEndOfStream()
    }

    override fun play() {
        super.play()
        isPlaying = true
        val resumed = if (!engineNeedsInitialSeek) { nativeEngine?.resume(); true } else false
        usbStreamingThread?.resumeStreaming()
        Log.i(TAG, "play() needsSeek=$engineNeedsInitialSeek resumed=$resumed")
    }

    override fun pause() {
        if (gaplessAdvanceInFlight && nativeEngine?.isRunning == true) {
            // ExoPlayer stops its renderers while it seeks onto the item the
            // engine is already playing — that is not a user pause, so keep the
            // engine (and the USB stream) running. A real pause during the
            // advance arrives via onPlayWhenReadyChanged(false) and is honoured there.
            Log.i(TAG, "pause() during gapless advance — engine keeps playing")
            super.pause()
            return
        }
        isPlaying = false
        if (!engineNeedsInitialSeek) nativeEngine?.pause()
        usbStreamingThread?.pauseStreaming()
        super.pause()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (config.bitPerfectEnabled && usbAudioStream?.isAlive == true) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

    override fun flush() {
        super.flush()
        // Native engine handles its own flush/seek internally
        // ExoPlayer pipeline: flush queue + native stream, serialized with the
        // streaming thread's writes (nativeFlush and a concurrent write used to
        // race on the stream's residual/accumulator state).
        val streamingThread = usbStreamingThread
        if (streamingThread != null) {
            streamingThread.flushStream()
        } else if (nativeEngine?.isRunning != true) {
            // Never flush the native USB stream while the native engine is running:
            // its decode thread writes into that stream concurrently, a seek is
            // handled by the engine itself (engine.seek from handleBuffer), and
            // during a gapless advance the stream must keep playing untouched.
            usbAudioStream?.flush()
        }
        usbStartMediaTimeNeedsInit = true
        handledEndOfStream = false
        // Temporarily unblock LoadControl so ExoPlayer loads at least one chunk
        // after seek. handleBuffer will re-block once it captures presentationTimeUs.
        // Without this, the LoadControl blocks ALL post-seek loading and the engine
        // never knows where to seek to.
        // (Also when the engine has already finished: a dead engine must never
        // keep ExoPlayer's loading blocked.)
        if (nativeEngine != null) {
            isNativeEngineActive = false
        }
    }

    override fun reset() {
        super.reset()
        // USB stream survives reset — configure() manages its lifecycle.
        // ExoPlayer calls reset() frequently (track changes, seeks).
        // Killing USB here causes audio to briefly route to the speaker.
    }

    override fun release() {
        // A released sink never comes back, so — unlike the between-tracks path —
        // the DAC has to be handed back completely: releaseUsbStream() on its own
        // deliberately keeps the device open (and with it the force=true usbfs
        // interface claims, which keep the kernel's snd-usb-audio detached and every
        // other app silent). Anything that tears the sink down without asking for a
        // hand-back first — a service teardown that skipped it, a player rebuild —
        // used to leak those claims here. No claims, no USB reset: the caller
        // already handed the DAC back and there is nothing left to return.
        if (ownsUsbDevice) {
            Log.i(TAG, "release() with the DAC still claimed — handing it back before the sink goes away")
            releaseUsbForIdle()
        } else {
            releaseUsbStream()
        }
        super.release()
    }

    // ── USB bit-perfect configuration ───────────────────────────────

    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int): Unit = synchronized(configureLock) {
        // NOTE: engine is NOT destroyed here. configure() returns early if engine
        // is still running. If we reach here, the engine is already dead or null.

        // Wait out any in-flight async idle release so its closeDevice() cannot
        // kill the fresh fd we open below. Bounded; on timeout we fall back to
        // the delegate and let the next configure re-try bit-perfect.
        var waited = 0L
        while (idleReleaseInFlight && waited < idleReleaseMaxWaitMs) {
            try { Thread.sleep(20) } catch (e: InterruptedException) { break }
            waited += 20
        }
        if (idleReleaseInFlight) {
            Log.w(TAG, "Idle release still in flight after ${waited}ms — using delegate for now")
            return
        }

        // Cache check — avoid needless USB stream recreation
        if (sampleRate == currentSampleRate && channelCount == currentChannelCount
            && usbAudioStream?.isAlive == true) {
            Log.d(TAG, "USB stream cached for rate=$sampleRate ch=$channelCount — reusing")
            // Engine will be created lazily in handleBuffer when currentTrackPath is set
            return
        }

        if (usbAudioStream != null) releaseUsbStream()

        val usbDevice = usbAudioDevice.findUsbAudioDevice() ?: return
        var deviceInfo = usbAudioDevice.openDevice(usbDevice)
        if (deviceInfo == null) {
            Log.e(TAG, "Failed to open USB device")
            return
        }

        // Always use the DAC's highest supported bit depth (standard practice).
        // Sources with lower bit depth are zero-padded in the LSBs.
        val bitDepth = deviceInfo.bestBitDepth
        val altSetting = deviceInfo.bestAltSetting
        Log.i(TAG, "Bit-perfect: source=${trackBitDepth}bit → alt=$altSetting usb=${bitDepth}bit " +
                "clockSource=0x${deviceInfo.clockSourceId.toString(16)}")

        var stream = UsbAudioStream(
            fd = deviceInfo.fd,
            interfaceId = deviceInfo.interfaceId,
            endpointOut = deviceInfo.endpointOutAddress,
            endpointFeedback = deviceInfo.endpointFeedbackAddress,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            maxPacketSize = deviceInfo.maxPacketSize
        )

        if (!stream.isReady) {
            Log.e(TAG, "USB stream creation failed")
            stream.release()
            usbAudioDevice.closeDevice()
            return
        }

        // ─── xHCI-verified transition sequence (from USB protocol analysis) ───
        //
        // 1. setAlt(0)       → xHCI Configure Endpoint (FREE old rings)
        // 2. SET_CUR          → write new sample rate to Clock Source
        // 3. GET_CUR          → verify clock accepted (CLOCK_VALID_CONTROL)
        // 4. setAlt(0) AGAIN  → defensive reset after clock change
        // 5. setAlt(N)        → xHCI Configure Endpoint (ALLOC new rings)
        // 6. wait ~47ms       → DAC PLL lock time
        // 7. start            → submit URBs

        // Step 1: setAlt(0) — FREE old ISO rings
        if (!usbAudioDevice.setAltSetting(0)) {
            Log.w(TAG, "setAlt(0) failed — stale fd, reopening device...")
            usbAudioDevice.closeDevice()
            stream.release()
            deviceInfo = usbAudioDevice.openDevice(usbDevice)
            if (deviceInfo == null) {
                Log.e(TAG, "Failed to reopen USB device")
                return
            }
            stream = UsbAudioStream(
                fd = deviceInfo.fd,
                interfaceId = deviceInfo.interfaceId,
                endpointOut = deviceInfo.endpointOutAddress,
                endpointFeedback = deviceInfo.endpointFeedbackAddress,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitDepth = bitDepth,
                maxPacketSize = deviceInfo.maxPacketSize
            )
            if (!stream.isReady) {
                Log.e(TAG, "USB stream recreation failed after reopen")
                stream.release()
                usbAudioDevice.closeDevice()
                return
            }
            Log.i(TAG, "Device reopened with fresh fd=${deviceInfo.fd}")
        }
        Log.i(TAG, "Step 1: setAlt(0) — old ISO ring freed")

        // Step 2: SET_CUR — write new sample rate
        usbAudioDevice.setSampleRate(sampleRate)

        // Step 3: GET_CUR(CLOCK_VALID_CONTROL) — verify clock is locked
        val clockValid = usbAudioDevice.readClockValid()
        Log.i(TAG, "Step 2-3: SET_CUR=$sampleRate, CLOCK_VALID=$clockValid")

        // Step 4: setAlt(0) AGAIN — defensive reset after clock change
        usbAudioDevice.setAltSetting(0)
        Log.i(TAG, "Step 4: setAlt(0) again — defensive reset")

        // Step 5: setAlt(N) — ALLOC new ISO rings
        val altResult = usbAudioDevice.setAltSetting(altSetting)
        Log.i(TAG, "Step 5: setAlt($altSetting): $altResult — new ISO ring allocated")

        // Step 6: wait ~47ms — DAC PLL lock time
        Thread.sleep(50)

        if (!stream.start()) {
            Log.e(TAG, "USB stream start failed")
            stream.release()
            usbAudioDevice.closeDevice()
            return
        }

        usbAudioStream = stream
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        muteDelegateIfNeeded()
        // Ownership is reported from here — the one place the force=true usbfs
        // claims are taken. configure() reports it too, but the deferred cross-rate
        // reconfiguration calls this method directly on its own thread, and without
        // this the host's "driver owns the DAC" state stayed false for the whole
        // remainder of that track: nothing kept the process alive, and the release
        // paths (pause/idle/Exit) all skipped the hand-back, leaving the kernel
        // driver detached and every other app silent until a physical replug.
        onDriverOwnsUsbDeviceChanged?.invoke(true)

        // Try to create engine now (works for first track where onMediaItemTransition
        // fired before configure). For subsequent tracks, createEngineIfNeeded() in
        // onMediaItemTransition handles it (path is correct by then).
        startNativeEngineIfFlac(stream)

        Log.i(TAG, "USB bit-perfect stream ACTIVE: rate=$sampleRate ch=$channelCount " +
                "bits=$bitDepth device=${deviceInfo.deviceName}")
    }

    /** Playback thread. Re-opens the USB stream for the current format after an
     *  idle release (see [reclaimAfterIdleRelease]). */
    private fun reclaimUsbStream() {
        val sr = currentSampleRate
        val ch = currentChannelCount
        if (sr <= 0 || ch <= 0) return
        val device = usbAudioDevice.findUsbAudioDevice() ?: return
        if (!usbAudioDevice.hasPermission(device)) return
        configureUsbBitPerfect(sr, ch, currentEncoding)
        val alive = usbAudioStream?.isAlive == true
        onDriverOwnsUsbDeviceChanged?.invoke(alive)
        if (alive) {
            // Anchor the position to the next buffer; windowOffsetUs (the item's
            // start) is unchanged, so a new native engine seeks to the right spot.
            usbStartMediaTimeNeedsInit = true
            if (config.forceRouteToSpeaker) forceMediaToSpeaker()
            muteDelegateIfNeeded()
            Log.i(TAG, "USB driver re-claimed the DAC after an idle release (rate=$sr ch=$ch)")
        }
    }

    /** Try to start a native FLAC engine. Falls back to ExoPlayer streaming thread. */
    @Synchronized
    private fun startNativeEngineIfFlac(stream: UsbAudioStream) {
        if (nativeEngine != null) return  // already created (synchronized method)

        // Stop existing streaming thread (mutually exclusive with native engine)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        val path = currentTrackPath
        if (path != null && path.lowercase().endsWith(".flac")) {
            val engine = NativeAudioEngine()
            try {
                val fd = android.os.ParcelFileDescriptor.open(
                    File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val created = engine.createFromFd(fd.fd, stream.nativeHandle)
                fd.close()
                // Pause BEFORE starting: the decode thread must not push the
                // file's first frames to the DAC before the first seek (on a
                // resume at 2:38 that was an audible blip of the track start).
                if (created) engine.pause()
                if (created && engine.start()) {
                    // Verify FLAC sample rate matches USB stream — prevents distortion
                    // when ExoPlayer's queue and onMediaItemTransition disagree about
                    // which track is playing (e.g., cross-album Recently Played lists).
                    if (engine.getSampleRate() != currentSampleRate) {
                        Log.w(TAG, "Rate mismatch: FLAC=${engine.getSampleRate()} USB=$currentSampleRate" +
                                " — falling back to ExoPlayer pipeline")
                        engine.stop()
                        engine.destroy()
                    } else {
                        // Started paused — resumes in handleBuffer after capturing
                        // the correct seek position from ExoPlayer's presentationTimeUs.
                        nativeEngine = engine
                        isNativeEngineActive = true
                        engineNeedsInitialSeek = true
                        engineEndNotified = false
                        activeEnginePath = path
                        trackBitDepth = engine.getBitsPerSample()
                        // Fresh engine: nothing queued yet, no switches seen.
                        observedTrackSwitches = 0
                        gaplessAdvanceInFlight = false
                        queuedNextIndex = C.INDEX_UNSET
                        queuedNextPath = null
                        mainHandler.post { updateQueuedNext() }
                        Log.i(TAG, "Native FLAC engine started (paused, awaiting seek) for: ${File(path).name} ${trackBitDepth}-bit")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native engine failed: ${e.message}")
            }
            engine.destroy()
        }

        // Fallback: ExoPlayer pipeline via streaming thread
        usbStreamingThread = UsbStreamingThread(stream).also { it.start() }
        Log.i(TAG, "Using ExoPlayer pipeline (non-FLAC or engine failed)")
    }

    // ── USB stream release ──────────────────────────────────────────

    /**
     * Release the USB stream, reset (soft-replug) and close the device so the
     * system audio HAL can reuse the DAC (e.g. after sleep/wake, or when the
     * player is paused/idle and other apps need the DAC). The USBDEVFS_RESET
     * forces a re-enumeration; without it the kernel does not rebind the system
     * USB-audio driver (proxy pcm) and every other app stays silent until a
     * physical unplug/replug. The delegate stays alive so ExoPlayer keeps its
     * clock/position tracking; the stream is re-created lazily on configure().
     *
     * Runs the heavy work (stream stop/join, USBDEVFS_RESET, close) on a
     * background thread so the caller (service main thread) never blocks.
     * Duplicate calls while a release is in flight are skipped — configure()
     * waits for any in-flight release before re-opening the device.
     */
    fun releaseUsbForIdle() {
        // Atomic acquire: only ONE idle release may ever run. Concurrent callers
        // (pause path + STATE_IDLE + screen-off are different threads) must not
        // spawn parallel release threads that both USBDEVFS_RESET the same DAC.
        synchronized(idleReleaseLock) {
            if (idleReleaseInFlight) {
                Log.d(TAG, "Idle release already in flight — skipping duplicate")
                return
            }
            idleReleaseInFlight = true
        }
        // Re-claim on the next buffer after play() if the player keeps this
        // configuration (a stop()/new item goes through configure() instead).
        if (usbAudioStream != null && currentSampleRate > 0) reclaimAfterIdleRelease = true
        val th = Thread({
            try {
                releaseUsbStream()
                try {
                    // Restore the DAC's clock to its default idle rate BEFORE
                    // the soft-replug. A UAC2 clock-source rate persists across
                    // a USBDEVFS_RESET (re-enumeration is not a power cycle), so
                    // without this explicit SET_CUR the DAC is left locked on the
                    // last played stream rate until physically unplugged or
                    // reprogrammed by another app.
                    usbAudioDevice.restoreToIdleSampleRate()
                    // Soft-replug: re-enumerate so the kernel rebinds the system
                    // USB-audio driver and the DAC returns to the system.
                    usbAudioDevice.resetUsbDevice()
                } finally {
                    // Always drop the force=true interface claims, even if the
                    // reset threw or no stream was live (e.g. a stale connection
                    // from a failed configure). A leaked claim blocks the kernel
                    // driver rebind for every other app.
                    usbAudioDevice.closeDevice()
                }
                Log.i(TAG, "USB driver released for idle — DAC reset & returned to system")
            } catch (t: Throwable) {
                Log.w(TAG, "Idle release failed: ${t.message}", t)
            } finally {
                idleReleaseInFlight = false
            }
        }, "usbIdleRelease")
        th.priority = Thread.NORM_PRIORITY
        th.start()
    }

    private fun releaseUsbStream() {
        val stream = usbAudioStream ?: return
        usbAudioStream = null
        onDriverOwnsUsbDeviceChanged?.invoke(false)

        // Stop USB stream FIRST — sets ctx->running=false, which unblocks
        // submitPcmToUrbs inside the native engine's decode thread.
        // Without this, nativeEngine.stop() deadlocks on pthread_join.
        stream.stop()

        // Now safe to stop native engine (decode thread can exit)
        nativeEngine?.stop()
        nativeEngine?.destroy()
        nativeEngine = null
        isNativeEngineActive = false
        gaplessAdvanceInFlight = false
        queuedNextIndex = C.INDEX_UNSET
        queuedNextPath = null

        // Stop the streaming thread (drains queue, joins thread)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        // Drain ALL in-flight URBs — MUST complete before setAlt(0)
        val drained = stream.drainUrbs()
        Log.i(TAG, "USB stream drained $drained URBs")

        // Release native context
        stream.release()

        // Keep device connection open between tracks (standard practice)
        clearForcedRouting()
        unmuteDelegateIfNeeded()
        Log.i(TAG, "USB audio stream released (device kept open)")
    }

    // ── Delegate volume management ──────────────────────────────────

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) { super.setVolume(0f); delegateMuted = true }
    }

    private fun unmuteDelegateIfNeeded() {
        if (delegateMuted) { super.setVolume(pendingVolume); delegateMuted = false }
    }

    // ── Audio routing helpers ───────────────────────────────────────

    private fun forceMediaToSpeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
                Log.i(TAG, "Delegate routed to speaker")
            }
        } catch (e: Exception) {
            Log.w(TAG, "forceMediaToSpeaker failed: ${e.message}")
        }
    }

    private fun clearForcedRouting() {
        try { delegate.setPreferredDevice(null) } catch (_: Exception) {}
    }

    // ── Player integration (attachToPlayer) ──────────────────────

    @Volatile private var attachedPlayer: Player? = null
    private var integrationListener: Player.Listener? = null

    /**
     * Attach this sink to an ExoPlayer instance. Registers an internal
     * [Player.Listener] that handles:
     * - Extracting the file path from each [MediaItem]'s URI
     * - Cleaning up finished native engines on track transitions
     * - Creating new native engines for local FLAC files
     * - Advancing to the next track when the native engine reaches EOF
     *
     * Must be called on the main thread, after [ExoPlayer.Builder.build].
     */
    fun attachToPlayer(player: Player) {
        val oldListener = integrationListener
        val oldPlayer = attachedPlayer
        if (oldListener != null && oldPlayer != null) {
            oldPlayer.removeListener(oldListener)
        }

        val listener = PlayerIntegrationListener()
        player.addListener(listener)
        attachedPlayer = player
        integrationListener = listener
        Log.i(TAG, "attachToPlayer: integration listener registered")
    }

    /** Detach from the current player. Call before player.release(). */
    fun detachFromPlayer() {
        val listener = integrationListener
        val player = attachedPlayer
        if (listener != null && player != null) {
            player.removeListener(listener)
        }
        attachedPlayer = null
        integrationListener = null
    }

    /** Player position (us) captured in onMediaItemTransition. Used to calculate
     *  the correct window offset on restore (first handleBuffer pts is at the
     *  restored position, not at 0). */
    @Volatile
    private var initialPlayerPositionUs: Long = 0L

    private inner class PlayerIntegrationListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return

            // Capture player position BEFORE engine creation. On restore this is
            // the saved position (e.g., 158s). On fresh start this is 0.
            initialPlayerPositionUs = (attachedPlayer?.currentPosition ?: 0L) * 1000L
            Log.i(TAG, "onMediaItemTransition: initialPlayerPos=${initialPlayerPositionUs / 1000}ms")

            val uri = mediaItem.localConfiguration?.uri

            // 1. Clean up finished engine from previous track
            val engineFinished = cleanupFinishedEngine()

            // 2. Resolve file path from URI
            val resolvedPath = resolveTrackPath(uri)
            currentTrackPath = resolvedPath
            Log.i(TAG, "onMediaItemTransition: uri=$uri path=$resolvedPath")

            // 3. Create engine if local FLAC
            if (resolvedPath != null) {
                createEngineIfNeeded()
            }

            // 4. If previous engine finished, reset position for new track
            if (engineFinished) {
                attachedPlayer?.seekTo(0)
            }

            // 5. Gapless: hand the engine the item that follows this one.
            updateQueuedNext()
        }

        // The item after the current one can change without a transition:
        // re-queue so the engine never continues into a stale file.
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = updateQueuedNext()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateQueuedNext()
        override fun onRepeatModeChanged(repeatMode: Int) = updateQueuedNext()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A user pause that lands while a gapless advance is in flight: the
            // sink ignored ExoPlayer's seek-induced pause(), so honour this one.
            if (!playWhenReady && gaplessAdvanceInFlight) {
                gaplessAdvanceInFlight = false
                isPlaying = false
                nativeEngine?.pause()
                Log.i(TAG, "Gapless: paused by user during advance")
            }
        }
    }

    /**
     * Main thread. Queue the item that will play after the current one as the
     * engine's gapless next track: the following index, or the same one with
     * repeat-one. Only local FLAC files qualify (the engine decodes FLAC and
     * checks rate/channels itself at the switch); anything else clears the queue
     * so the engine ends normally and the player transitions as before.
     */
    private fun updateQueuedNext() {
        val engine = nativeEngine ?: return
        val p = attachedPlayer ?: return
        if (!engine.isRunning) return
        // A switch the playback thread hasn't processed yet: queuedNext* still
        // describes the file the engine just took — leave it for onGaplessSwitch
        // (which re-queues after moving the player).
        if (engine.getTrackSwitchCount() != observedTrackSwitches) return
        val index = if (p.repeatMode == Player.REPEAT_MODE_ONE) p.currentMediaItemIndex
                    else p.nextMediaItemIndex
        val path = if (index != C.INDEX_UNSET && index < p.mediaItemCount) {
            resolveTrackPath(p.getMediaItemAt(index).localConfiguration?.uri)
        } else null
        if (path == null || !path.lowercase().endsWith(".flac")) {
            if (queuedNextPath != null) {
                engine.clearNext()
                queuedNextPath = null
                queuedNextIndex = C.INDEX_UNSET
                Log.i(TAG, "Gapless: next item is not a local FLAC — queue cleared")
            }
            return
        }
        if (path == queuedNextPath && index == queuedNextIndex) return  // already queued
        try {
            android.os.ParcelFileDescriptor.open(
                File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            ).use { pfd ->
                if (engine.setNextFd(pfd.fd)) {   // native dup()s the fd
                    queuedNextPath = path
                    queuedNextIndex = index
                    Log.i(TAG, "Gapless: queued next track #$index ${File(path).name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gapless: could not queue ${File(path).name}: ${e.message}")
            engine.clearNext()
            queuedNextPath = null
            queuedNextIndex = C.INDEX_UNSET
        }
    }

    /** Playback thread (from getCurrentPositionUs). The engine has continued into
     *  the queued file; move the player onto that item for its timeline and UI. */
    private fun onGaplessSwitch() {
        val path = queuedNextPath
        val index = queuedNextIndex
        queuedNextPath = null
        queuedNextIndex = C.INDEX_UNSET
        activeEnginePath = path
        windowOffsetUs = -1L
        trackBitDepth = nativeEngine?.getBitsPerSample() ?: trackBitDepth
        gaplessAdvanceStartedMs = SystemClock.elapsedRealtime()
        gaplessAdvanceInFlight = true
        Log.i(TAG, "Gapless: engine continued into next track #$index ${path?.let { File(it).name }} — advancing player")
        val p = attachedPlayer ?: run { gaplessAdvanceInFlight = false; return }
        mainHandler.post {
            val stillThere = index != C.INDEX_UNSET && index < p.mediaItemCount &&
                resolveTrackPath(p.getMediaItemAt(index).localConfiguration?.uri) == path
            if (stillThere) {
                p.seekTo(index, 0L)
                // Repeat-one re-seeks the same item, which fires no transition
                // callback: queue the next repetition explicitly.
                updateQueuedNext()
            } else {
                // The queue changed after the file was handed to the engine.
                // Fall back to a normal advance; configure() sees the path
                // mismatch and rebuilds the engine for the right item.
                Log.w(TAG, "Gapless: queue changed under the engine — normal advance")
                gaplessAdvanceInFlight = false
                advanceAfterEngineEnd(p)
            }
        }
    }

    /** Main thread. Advance after the engine ended (non-gapless path). */
    private fun advanceAfterEngineEnd(p: Player) {
        when {
            p.repeatMode == Player.REPEAT_MODE_ONE -> {
                // Re-seeking the same item fires no onMediaItemTransition, so the
                // finished engine would never be cleaned up and the load control
                // would keep ExoPlayer blocked: clean up here first.
                cleanupFinishedEngine()
                p.seekTo(p.currentMediaItemIndex, 0L)
            }
            p.hasNextMediaItem() -> p.seekToNextMediaItem()
            else -> p.pause()
        }
    }

    /**
     * Resolve a [MediaItem]'s URI to a local file path for the native engine.
     *
     * - `file:///path/to/song.flac` → `/path/to/song.flac`
     * - `/storage/.../song.flac` (bare path) → as-is
     * - `content://media/external/audio/123` → resolved via ContentResolver
     * - `http://` or `https://` → null (ExoPlayer pipeline handles these)
     */
    private fun resolveTrackPath(uri: Uri?): String? {
        if (uri == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> resolveContentUri(uri)
            "http", "https" -> {
                Log.i(TAG, "resolveTrackPath: HTTP URI → ExoPlayer pipeline (no native engine)")
                null
            }
            null -> {
                // Bare path string (no scheme) — common in local music players
                val pathStr = uri.toString()
                if (pathStr.startsWith("/")) pathStr else null
            }
            else -> null
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContentUri failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "UsbAudioSink"

        /** Upper bound for the player to reach the next item after a gapless
         *  switch before position reporting falls back to normal. */
        private const val GAPLESS_ADVANCE_TIMEOUT_MS = 5_000L

        /**
         * Wraps a [LoadControl] to suppress ExoPlayer loading when the native
         * FLAC engine is decoding directly to USB. Call BEFORE [ExoPlayer.Builder.build].
         *
         * @param delegate       Your app's LoadControl (e.g., DefaultLoadControl).
         * @param isEngineActive Lambda returning true when native engine is active.
         *                       Typical: `{ usbSink?.isNativeEngineActive == true }`
         */
        @JvmStatic
        @OptIn(UnstableApi::class)
        fun wrapLoadControl(
            delegate: LoadControl,
            isEngineActive: () -> Boolean
        ): LoadControl = NativeEngineAwareLoadControl(delegate, isEngineActive)
    }
}
