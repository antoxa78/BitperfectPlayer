package com.example.bitperfectplayer

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses AudioTrack directly for high-resolution PCM so DefaultAudioSink cannot convert it to
 * float or 16-bit PCM. Other formats are delegated to media3's standard sink.
 */
@UnstableApi
class BitPerfectAudioSink(
    private val delegate: AudioSink,
    private val bitPerfectManager: BitPerfectManager,
    private val selectedDevice: AtomicReference<AudioDeviceInfo?>
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var preferredDevice: AudioDeviceInfo? = null
    private var volume = 1f
    private var tunneling = false
    private var delegateConfigured = false

    private var directMode = false
    private var directFormat: Format? = null
    private var directChannelMask = AudioFormat.CHANNEL_INVALID
    private var directBufferSize = 0
    private var directTrack: AudioTrack? = null
    private var directConfig: AudioSink.AudioTrackConfig? = null
    private var routedDevice: AudioDeviceInfo? = null
    private var directFrameSize = 0
    private var directWrittenFrames = 0L
    private var directBasePlaybackHead = 0L
    private var directStartMediaTimeUs = C.TIME_UNSET
    private var directEnded = false
    private var playing = false
    private val audioTimestamp = AudioTimestamp()

    /** Encoding the direct AudioTrack is opened with: the input encoding, or
     *  PCM_32BIT / PCM_24BIT when float decoder output is converted so the track
     *  matches an Android 14+ BIT_PERFECT mixer (see [chooseDirectOutputEncoding]). */
    private var directOutputEncoding = C.ENCODING_INVALID
    private var directConvertFloat = false
    /** Reused native-order buffer holding the integer version of one input buffer. */
    private var convertBuffer: ByteBuffer? = null
    /** Converted data not yet fully accepted by a non-blocking AudioTrack.write(). */
    private var pendingConverted: ByteBuffer? = null

    /**
     * A format change that arrived while the direct AudioTrack still holds
     * audio of the previous item. Like DefaultAudioSink's pending
     * configuration, it is applied only once that audio has played out —
     * releasing the track straight away cut off the end of every track.
     */
    private class PendingConfig(val format: Format, val specifiedBufferSize: Int, val outputChannels: IntArray?)
    private var pendingConfig: PendingConfig? = null
    /** stop() was issued on the direct track (end of stream / draining for a switch). */
    private var directStopIssued = false
    private var drainStartedMs = 0L

    /** The configured stream is DoP (DSD over PCM): only valid bit-exact through
     *  the USB driver; played as PCM anywhere else it is just noise. */
    private var currentIsDop = false
    /** Last format passed to configure() (carried by errors so the service can
     *  recognise a DoP stream). */
    private var configuredFormat: Format? = null

    /**
     * When true, the userspace USB driver owns the DAC and this sink must not
     * create its own direct AudioTrack on the USB output (that would fight the
     * usbdevfs driver over the interface). Set by PlaybackService when the
     * decent-player driver is engaged.
     */
    @Volatile
    var driverOwnsUsbDevice: Boolean = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        delegate.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        delegate.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        return if (isDirectCandidate(format) && canCreateDirectTrack(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            delegate.getFormatSupport(format)
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return if (isDirectCandidate(format)) AudioOffloadSupport.DEFAULT_UNSUPPORTED
        else delegate.getFormatOffloadSupport(format)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!directMode) return delegate.getCurrentPositionUs(sourceEnded)
        if (directTrack == null || directStartMediaTimeUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        return directStartMediaTimeUs + framesToDurationUs(playedFrames())
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (isDopFormat(format) && !driverOwnsUsbDevice) {
            // Refuse instead of letting the system mixer / resampler (or a
            // plain direct track) turn the DoP bitstream into audible noise.
            // PlaybackService catches this and retries the track as PCM.
            throw AudioSink.ConfigurationException(
                "DoP stream needs the bit-perfect USB driver", format
            )
        }
        currentIsDop = isDopFormat(format)
        configuredFormat = format
        if (directMode && directTrack != null) {
            if (outputChannels == null && isDirectCandidate(format) && isSameDirectFormat(format)) {
                // Next item in the same PCM format (gapless album): keep feeding
                // the same AudioTrack — no drain, no gap, no mixer re-negotiation.
                directFormat = format
                pendingConfig = null
                return
            }
            // Different format: let the current track play out first (see handleBuffer).
            pendingConfig = PendingConfig(format, specifiedBufferSize, outputChannels)
            return
        }
        pendingConfig = null
        applyConfiguration(format, specifiedBufferSize, outputChannels)
    }

    private fun isSameDirectFormat(format: Format): Boolean {
        val cur = directFormat ?: return false
        return format.sampleRate == cur.sampleRate &&
            format.channelCount == cur.channelCount &&
            format.pcmEncoding == cur.pcmEncoding
    }

    private fun applyConfiguration(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        directStopIssued = false
        if (directMode) {
            releaseDirectTrack()
            delegate.reset()
            directMode = false
            directFormat = null
            delegateConfigured = false
        }

        if (isDirectCandidate(format) && outputChannels == null && canCreateDirectTrack(format)) {
            if (delegateConfigured) {
                delegate.reset()
                delegateConfigured = false
            }
            directMode = true
            directFormat = format
            directChannelMask = channelMaskFor(format)
            directBufferSize = specifiedBufferSize
            chooseDirectOutputEncoding(format)
            pendingConverted = null
            directFrameSize = Util.getPcmFrameSize(directOutputEncoding, format.channelCount)
            directWrittenFrames = 0L
            directBasePlaybackHead = 0L
            directStartMediaTimeUs = C.TIME_UNSET
            directEnded = false
            return
        }

        val outputDevice = if (driverOwnsUsbDevice) {
            // The usbdevfs driver owns the DAC. Route the delegate to whatever was
            // explicitly forced (e.g. the wrapper's forceMediaToSpeaker); otherwise
            // leave it null so DefaultAudioSink falls back to the default output
            // (never the claimed DAC).
            preferredDevice
        } else {
            preferredDevice ?: bitPerfectManager.findUsbOutputDevice()
        }
        selectedDevice.set(outputDevice)
        delegate.setPreferredDevice(outputDevice)
        delegate.configure(format, specifiedBufferSize, outputChannels)
        delegateConfigured = true
    }

    override fun play() {
        playing = true
        if (directMode) directTrack?.play() else delegate.play()
    }

    /**
     * Plays out the direct track before a pending format switch. Returns true
     * once everything written has been rendered (or the track is unusable).
     */
    private fun drainDirectTrack(): Boolean {
        val track = directTrack ?: return true
        if (!playing) return false  // paused: finish the drain after play()
        if (!directStopIssued) {
            try { track.stop() } catch (_: Exception) { return true }
            directStopIssued = true
            drainStartedMs = android.os.SystemClock.elapsedRealtime()
        }
        val head = (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - directBasePlaybackHead
        if (head >= directWrittenFrames) return true
        // Never wedge the renderer on a track whose position stops moving.
        return android.os.SystemClock.elapsedRealtime() - drainStartedMs > DRAIN_TIMEOUT_MS
    }

    override fun handleDiscontinuity() {
        // Re-anchored from the next buffer (see handleBuffer), accounting for
        // everything already written, so the position stays continuous.
        if (directMode) directStartMediaTimeUs = C.TIME_UNSET else delegate.handleDiscontinuity()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        pendingConfig?.let { pc ->
            if (!drainDirectTrack()) return false
            pendingConfig = null
            applyConfiguration(pc.format, pc.specifiedBufferSize, pc.outputChannels)
        }
        if (currentIsDop && !driverOwnsUsbDevice) {
            // The USB driver let go of the DAC mid-stream (unplug, release):
            // never let the DoP bitstream reach a speaker as PCM noise.
            throw AudioSink.WriteException(
                AudioTrack.ERROR_INVALID_OPERATION,
                configuredFormat ?: directFormat ?: Format.Builder().build(),
                false
            )
        }
        if (!directMode) return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        val track = directTrack ?: try {
            initializeDirectTrack()
            checkNotNull(directTrack)
        } catch (e: AudioSink.InitializationException) {
            return fallBackToMedia3Sink(buffer, presentationTimeUs, encodedAccessUnitCount, e)
        }

        if (!buffer.hasRemaining()) return true
        if (directStartMediaTimeUs == C.TIME_UNSET) {
            // This buffer starts after everything already written to the track
            // (non-zero after a discontinuity, e.g. a gapless item change).
            directStartMediaTimeUs = presentationTimeUs - framesToDurationUs(directWrittenFrames)
        }

        if (directConvertFloat) {
            // Convert the whole input buffer once; keep the result until the
            // track has taken all of it (ExoPlayer re-offers the same, unconsumed
            // input buffer while we return false).
            val out = pendingConverted ?: convertFloatToInteger(buffer).also { pendingConverted = it }
            val w = track.write(out, out.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (w < 0) {
                throw AudioSink.WriteException(w, checkNotNull(directFormat), w == AudioTrack.ERROR_DEAD_OBJECT)
            }
            directWrittenFrames += w / directFrameSize
            if (out.hasRemaining()) return false
            pendingConverted = null
            buffer.position(buffer.limit())
            return true
        }

        val written = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            throw AudioSink.WriteException(
                written,
                checkNotNull(directFormat),
                written == AudioTrack.ERROR_DEAD_OBJECT
            )
        }
        directWrittenFrames += written / directFrameSize
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (directMode) {
            directEnded = true
            // A streaming AudioTrack only guarantees to play out its last,
            // partially filled buffer after stop(); without it a DIRECT output
            // can hold those frames back and isEnded() never becomes true.
            val track = directTrack
            if (track != null && !directStopIssued && pendingConverted == null) {
                try { track.stop() } catch (_: Exception) {}
                directStopIssued = true
            }
        } else {
            delegate.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean =
        if (directMode) pendingConfig == null && directEnded && !hasPendingData() else delegate.isEnded()

    override fun hasPendingData(): Boolean {
        if (!directMode) return delegate.hasPendingData()
        return directTrack != null && directWrittenFrames > playedFrames()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (!directMode) delegate.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return if (directMode) PlaybackParameters.DEFAULT else delegate.getPlaybackParameters()
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (!directMode) delegate.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return if (directMode) false else delegate.getSkipSilenceEnabled()
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        if (directMode && directTrack != null) {
            releaseDirectTrack()
        } else {
            delegate.setAudioAttributes(audioAttributes)
        }
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (directMode && directTrack != null) releaseDirectTrack()
        else delegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        if (!directMode) delegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        selectedDevice.set(audioDeviceInfo)
        if (directMode) directTrack?.preferredDevice = audioDeviceInfo
        else delegate.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (!directMode) delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        tunneling = true
        if (directMode) {
            releaseDirectTrack()
            // Tunneling disqualifies direct mode (see isDirectCandidate()'s own
            // tunneling check) — clear the flag here too, not just the track,
            // or a handleBuffer() call arriving before the next configure()
            // would silently rebuild a non-tunneled direct AudioTrack from the
            // stale format instead of routing to the delegate.
            directMode = false
            directFormat = null
        }
        delegate.enableTunnelingV21()
    }

    override fun disableTunneling() {
        tunneling = false
        delegate.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        // Offload is intentionally disabled for bit-perfect playback.
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        // Offload is intentionally disabled for bit-perfect playback.
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (directMode) directTrack?.setVolume(volume) else delegate.setVolume(volume)
    }

    override fun pause() {
        playing = false
        if (directMode) directTrack?.pause() else delegate.pause()
    }

    override fun flush() {
        val pc = pendingConfig
        if (pc != null) {
            // The audio waiting to drain is being discarded anyway: switch now.
            pendingConfig = null
            applyConfiguration(pc.format, pc.specifiedBufferSize, pc.outputChannels)
        }
        if (directMode) {
            directTrack?.let { t ->
                // flush() only works on a paused/stopped track.
                try { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause() } catch (_: Exception) {}
                t.flush()
                if (playing) try { t.play() } catch (_: Exception) {}
            }
            pendingConverted = null
            directWrittenFrames = 0L
            directBasePlaybackHead = directTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
            directStartMediaTimeUs = C.TIME_UNSET
            directEnded = false
            directStopIssued = false
        } else {
            delegate.flush()
        }
    }

    override fun reset() {
        pendingConfig = null
        directStopIssued = false
        currentIsDop = false
        configuredFormat = null
        releaseDirectTrack()
        directMode = false
        directFormat = null
        bitPerfectManager.clear()
        delegate.reset()
        delegateConfigured = false
    }

    override fun release() {
        reset()
        delegate.release()
    }

    private fun initializeDirectTrack() {
        val format = checkNotNull(directFormat)
        val minBufferSize = AudioTrack.getMinBufferSize(
            format.sampleRate,
            directChannelMask,
            directOutputEncoding
        )
        if (minBufferSize <= 0) {
            throw initializationException(IllegalArgumentException("Unsupported direct PCM format"))
        }

        val frameSize = directFrameSize
        val requestedBufferSize = maxOf(minBufferSize * 2, directBufferSize)
        val bufferSize = ((requestedBufferSize + frameSize - 1) / frameSize) * frameSize
        val config = AudioSink.AudioTrackConfig(
            directOutputEncoding,
            format.sampleRate,
            directChannelMask,
            tunneling,
            false,
            bufferSize
        )

        // Use the same device for mixer negotiation and AudioTrack routing. If ExoPlayer
        // has not supplied one, prefer the currently detected USB output.
        val outputDevice = preferredDevice ?: bitPerfectManager.findUsbOutputDevice()
        routedDevice = outputDevice
        selectedDevice.set(outputDevice)
        bitPerfectManager.updateAudioTrack(config, outputDevice)

        try {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(directOutputEncoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(directChannelMask)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes.getAudioAttributesV21().audioAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) builder.setSessionId(audioSessionId)

            val track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IllegalStateException("AudioTrack was not initialized")
            }
            outputDevice?.let { track.preferredDevice = it }
            track.setVolume(volume)
            directTrack = track
            directConfig = config
            directBasePlaybackHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            listener?.onAudioTrackInitialized(config)
            if (playing) track.play()
        } catch (e: Exception) {
            bitPerfectManager.clear()
            throw initializationException(e)
        }
    }

    private fun fallBackToMedia3Sink(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
        failure: AudioSink.InitializationException
    ): Boolean {
        val format = directFormat ?: throw failure
        bitPerfectManager.clear()
        directMode = false
        directFormat = null
        delegate.setPreferredDevice(routedDevice ?: preferredDevice)
        delegate.configure(format, directBufferSize, null)
        delegateConfigured = true
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun releaseDirectTrack() {
        val track = directTrack ?: return
        val config = directConfig
        directTrack = null
        directConfig = null
        pendingConverted = null
        try { track.pause() } catch (_: Exception) {}
        // release() can throw on a track already in a bad state (e.g. dead
        // object after the DAC drops mid-stream) — guard it like pause() above
        // so teardown always completes and callers on ExoPlayer's playback
        // thread (reset(), configure(), setAudioAttributes(), etc.) never
        // crash mid-recovery.
        try { track.release() } catch (_: Exception) {}
        bitPerfectManager.clear()
        if (config != null) listener?.onAudioTrackReleased(config)
    }

    private fun playedFrames(): Long {
        val track = directTrack ?: return 0L
        val rate = directFormat?.sampleRate ?: 0
        val frames = if (playing && rate > 0 && track.getTimestamp(audioTimestamp)) {
            // The timestamp describes the frame presented at nanoTime, which
            // can be tens of ms old: advance it to "now" so the position moves
            // smoothly instead of in steps.
            val ageNs = System.nanoTime() - audioTimestamp.nanoTime
            audioTimestamp.framePosition + if (ageNs > 0) ageNs * rate / 1_000_000_000L else 0L
        } else {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        // Never report more than was actually written.
        return (frames - directBasePlaybackHead).coerceIn(0L, directWrittenFrames)
    }

    private fun framesToDurationUs(frames: Long): Long {
        val sampleRate = directFormat?.sampleRate ?: return 0L
        return frames * 1_000_000L / sampleRate
    }

    private fun initializationException(cause: Exception): AudioSink.InitializationException {
        return AudioSink.InitializationException(
            AudioTrack.STATE_UNINITIALIZED,
            directFormat?.sampleRate ?: 0,
            directChannelMask,
            directBufferSize,
            checkNotNull(directFormat),
            false,
            cause
        )
    }

    private fun isDirectCandidate(format: Format): Boolean {
        if (driverOwnsUsbDevice) return false
        if (tunneling || format.sampleMimeType != androidx.media3.common.MimeTypes.AUDIO_RAW) return false
        // 16-bit PCM (plain CD-quality FLAC/WAV) needs the same direct-track +
        // bit-perfect-mixer treatment as 24/32-bit: without it, a 16-bit track
        // goes through the delegate's normal AudioTrack, which never asks
        // BitPerfectManager for the Android 14+ MIXER_BEHAVIOR_BIT_PERFECT
        // mixer attributes — so the system mixer is free to resample it to
        // its internal rate (e.g. 44.1kHz -> 48kHz) even in "Bit-perfect via
        // Android" mode.
        return format.pcmEncoding == C.ENCODING_PCM_16BIT ||
            format.pcmEncoding == C.ENCODING_PCM_24BIT ||
            format.pcmEncoding == C.ENCODING_PCM_32BIT ||
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
    }

    private fun canCreateDirectTrack(format: Format): Boolean {
        if (!isDirectCandidate(format) || format.sampleRate <= 0 || format.channelCount <= 0) return false
        return AudioTrack.getMinBufferSize(
            format.sampleRate,
            channelMaskFor(format),
            format.pcmEncoding
        ) > 0
    }

    private fun channelMaskFor(format: Format): Int {
        return Util.getAudioTrackChannelConfig(format.channelCount)
    }

    /**
     * Decoders deliver float here (the sink advertises float so that 24-bit
     * sources are not truncated to 16-bit), but Android 14+ USB bit-perfect
     * mixers exist only for the DAC's integer formats: a float track never
     * matches one and silently goes through the resampling system mixer. If the
     * DAC offers a BIT_PERFECT mixer for int32 (else int24) at this rate, open
     * the track in that format and convert — exact for any ≤24-bit source.
     * Below Android 14 (e.g. Shield, Android 11: no 24/32-bit AudioTrack before
     * API 31) nothing changes.
     */
    private fun chooseDirectOutputEncoding(format: Format) {
        directOutputEncoding = format.pcmEncoding
        directConvertFloat = false
        if (format.pcmEncoding != C.ENCODING_PCM_FLOAT) return
        val device = preferredDevice ?: bitPerfectManager.findUsbOutputDevice() ?: return
        val intEncoding = bitPerfectManager.findBitPerfectIntegerEncoding(
            device, format.sampleRate, directChannelMask
        ) ?: return
        directOutputEncoding = intEncoding
        directConvertFloat = true
        Log.i(TAG, "Float input -> ${if (intEncoding == C.ENCODING_PCM_32BIT) "int32" else "int24"} " +
            "track for the BIT_PERFECT mixer (${format.sampleRate} Hz, ${format.channelCount} ch)")
    }

    private fun convertFloatToInteger(input: ByteBuffer): ByteBuffer {
        // duplicate() resets the byte order to BIG_ENDIAN: restore the input's.
        val floats = input.duplicate().order(input.order()).asFloatBuffer()
        val n = floats.remaining()
        val int32 = directOutputEncoding == C.ENCODING_PCM_32BIT
        val needed = n * (if (int32) 4 else 3)
        var out = convertBuffer
        if (out == null || out.capacity() < needed) {
            out = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
            convertBuffer = out
        }
        out!!.clear()
        if (int32) {
            for (i in 0 until n) out.putInt(floatToInt32(floats.get()))
        } else {
            for (i in 0 until n) {
                val v = floatToInt24(floats.get())
                out.put(v.toByte())
                out.put((v shr 8).toByte())
                out.put((v shr 16).toByte())
            }
        }
        out.flip()
        return out
    }

    private companion object {
        const val TAG = "BitPerfectAudioSink"

        /** Upper bound for playing out the old track before a format switch. */
        const val DRAIN_TIMEOUT_MS = 2_000L

        /** DSD sources label their DoP formats ("DSD64 DoP"); see SacdMediaExtractor / DsdFileExtractor. */
        fun isDopFormat(format: Format): Boolean = format.label?.contains("DoP") == true

        /** Exact for float values that came from ≤24-bit integers (scaling by a
         *  power of two in double precision), rounded to nearest otherwise. */
        fun floatToInt32(f: Float): Int =
            Math.round(f.toDouble() * 2147483648.0)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

        fun floatToInt24(f: Float): Int =
            Math.round(f.toDouble() * 8388608.0).coerceIn(-8388608L, 8388607L).toInt()
    }
}
