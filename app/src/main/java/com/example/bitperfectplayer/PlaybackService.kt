package com.example.bitperfectplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Live ICY info for the stream currently being played, if any. */
data class IcyStreamInfo(
    val mediaId: String,
    val title: String?,
    val station: String?,
    val genre: String?,
    val description: String?,
    val url: String?
)

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        // ── Constants ─────────────────────────────────────────────────────────
        private const val TAG                  = "PlaybackService"
        private const val PREFS_APP            = "AppSettings"
        private const val KEY_NETWORK_BUFFER   = "network_buffer"
        private const val KEY_AUTO_RECONNECT   = "auto_reconnect"
        private const val KEY_RESUME_PLAYBACK  = "resume_playback"
        private const val KEY_RECENT_FILES     = "recent_files"

        private const val HTTP_TIMEOUT_SECS    = 20L
        private const val USER_AGENT           = "BitperfectPlayer/1.1 (Android TV)"
        private const val POSITION_SAVE_MS     = 10_000L
        private const val RECONNECT_DELAY_MS   = 5_000L
        private const val MAX_RETRIES          = 5

        private const val BUFFER_MIN_MS        = 5_000
        private const val BUFFER_MAX_MS        = 120_000
        private const val BUFFER_PLAYBACK_MS   = 5_000
        private const val BUFFER_REBUFFER_MS   = 5_000
        private const val BUFFER_MAX_BYTES     = 128 * 1024 * 1024

        private const val RECENT_LIST_MAX      = 20

        private const val USB_SETTLE_MS        = 1_500L
        private const val USB_RESET_GAP_MS     = 400L

        private const val LAN_FG_NOTIF_ID      = 1002
        private const val MEDIA3_NOTIF_ID      = 1001 // Media3 DefaultMediaNotificationProvider default id
        private const val CHANNEL_LAN_CONTROL  = "lan_control"
        private const val LAN_FG_WATCHDOG_MS   = 60_000L

        private const val TAG_BITPERFECT       = "BitPerfectAudio"

        // ── USB DAC detection ─────────────────────────────────────────────────
        /**
         * Detects a live USB audio device using UsbManager.
         * Unlike AudioManager.getDevices() which caches USB descriptors and keeps
         * returning the device after power-off, UsbManager.deviceList only contains
         * devices currently active on the bus.
         *
         * Callable from any Context — does not require the service to be running.
         */
        fun findUsbAudioDevice(context: Context): android.hardware.usb.UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE)
                as? android.hardware.usb.UsbManager ?: return null
            return usbManager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any { i ->
                    device.getInterface(i).interfaceClass ==
                        android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
                }
            }
        }

        /** Live reference to the running service — used by UI for resetAudioSink(). */
        @Volatile var instance: PlaybackService? = null
            private set

        /**
         * Latest ICY metadata from the stream currently being played.
         *
         * Media3 gives MediaItem metadata precedence over in-band ICY metadata, so
         * Player.mediaMetadata.title never reflects the stream's StreamTitle once a
         * static title is set on the item. The UI reads this field instead.
         */
        @Volatile var icyInfo: IcyStreamInfo? = null
            private set
    }

    // ── USB DAC monitoring ────────────────────────────────────────────────────

    // Runnable field so USB-settle delayed calls can be cancelled before re-posting,
    // preventing pileup on rapid hotplug events (BUG-18).
    private val usbSettleRunnable = Runnable { checkAndResetUsbAudio("USB attach") }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached — will check for DAC in ${USB_SETTLE_MS}ms")
                    // Cancel any previous pending settle check before re-posting (BUG-18).
                    mainHandler.removeCallbacks(usbSettleRunnable)
                    mainHandler.postDelayed(usbSettleRunnable, USB_SETTLE_MS)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    mainHandler.removeCallbacks(usbSettleRunnable)
                }
            }
        }
    }

    /**
     * If a live USB DAC is present, resets the audio sink to force bit-perfect re-negotiation.
     */
    fun checkAndResetUsbAudio(reason: String = "manual") {
        val dac = findUsbAudioDevice(this)
        if (dac != null) {
            Log.i(TAG, "USB DAC live: '${dac.deviceName}' — resetting sink [$reason]")
            resetAudioSink()
        } else {
            Log.d(TAG, "No live USB DAC found [$reason]")
        }
    }

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bitPerfectManager: BitPerfectManager
    private var mpdServer: MpdServer? = null

    // ── LAN keep-foreground ───────────────────────────────────────────────────
    // Android TV aggressively kills idle "started" services once the app UI is
    // closed, which takes the MPD server down with it. While LAN control is
    // enabled and Media3 is not showing its own media notification, hold the
    // service in the foreground state with a minimal silent notification.

    private var lanFgOwned = false
    private val lanFgWatchdog = object : Runnable {
        override fun run() {
            updateLanForeground()
            mainHandler.postDelayed(this, LAN_FG_WATCHDOG_MS)
        }
    }

    private fun updateLanForeground() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLanForeground() }
            return
        }
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val mpdEnabled = prefs.getBoolean("mpd_enabled", true)
        val player = mediaSession?.player
        val media3OwnsFg = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

        if (lanFgOwned) {
            if (media3OwnsFg) {
                // Media3 took over the foreground state — hand over silently.
                lanFgOwned = false
            } else if (!mpdEnabled) {
                try { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                lanFgOwned = false
                Log.i(TAG, "LAN keep-foreground released")
            }
        }
        if (!mpdEnabled || media3OwnsFg || lanFgOwned) return

        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_LAN_CONTROL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_LAN_CONTROL, "LAN control",
                        NotificationManager.IMPORTANCE_MIN)
                )
            }
            // While paused, Media3 keeps its (non-foreground) media notification
            // with id 1001; remove it so we don't end up with two notifications
            // alongside our LAN keep-foreground one.
            try { mgr.cancel(MEDIA3_NOTIF_ID) } catch (_: Exception) {}
            val port = prefs.getInt("mpd_port", 6600).coerceIn(1024, 65535)
            val n = NotificationCompat.Builder(this, CHANNEL_LAN_CONTROL)
                .setSmallIcon(R.drawable.ic_network)
                .setContentTitle("LAN control active")
                .setContentText("MPD :$port — control from phone app")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(LAN_FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(LAN_FG_NOTIF_ID, n)
            }
            lanFgOwned = true
            Log.i(TAG, "LAN keep-foreground started")
        } catch (e: Exception) {
            Log.w(TAG, "LAN keep-foreground failed: ${e.message}")
        }
    }

    /** mediaId of the currently playing item — protected by icyInfoLock. */
    private var activeMediaId: String? = null
    private val icyInfoLock = Any()

    // ── Position saver ────────────────────────────────────────────────────────

    private val savePositionRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            mainHandler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    /**
     * Playback position worth persisting, in ms.
     *
     * Returns 0 for live streams (duration == TIME_UNSET) because their
     * currentPosition grows unboundedly and is meaningless for a restart —
     * restoring it makes ExoPlayer seek past the stream end on seekable
     * radio streams (e.g. MP3 with a Xing/VBR header), which instantly ends
     * playback instead of resuming the station (BUG-19). Also returns 0 for
     * finished items (pos == duration, e.g. playlist ended) since resuming
     * at that position would instantly end playback again.
     */
    private fun safePositionToSave(p: Player): Long {
        val pos = p.currentPosition
        val dur = p.duration
        return if (dur == C.TIME_UNSET || pos >= dur) 0L else pos
    }

    private fun saveCurrentPosition() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE) {
                // Index and position must be written atomically in one edit:
                // saveLastPlayed() (on track transition) and this timer save
                // independently, and pairing a stale position from the previous
                // track with the new track's index would make resume seek past
                // the end and skip/stop instead of resuming.
                val safePos = safePositionToSave(p)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit()
                    .putLong("last_played_pos", safePos)
                    .putInt("last_played_index", p.currentMediaItemIndex)
                    .apply()
            }
        }
    }

    /**
     * Saves the current playback position synchronously using commit() instead
     * of apply(). Called from the UI before System.exit(0) so the position is
     * guaranteed to be on disk before the process is killed (BUG-6).
     */
    fun saveCurrentPositionSync() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE) {
                val safePos = safePositionToSave(p)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit()
                    .putLong("last_played_pos", safePos)
                    .putInt("last_played_index", p.currentMediaItemIndex)
                    .commit()
            }
        }
    }

    /**
     * Tears down ExoPlayer's AudioTrack (closing the HAL session) and
     * immediately rebuilds it, forcing Android to re-negotiate bit-perfect
     * output parameters with the DAC.
     *
     * Current queue, position, and play-state are fully restored.
     */
    // Runnable field for the sink-restore step so rapid resetAudioSink() calls
    // cancel the previous pending restore before scheduling a new one (BUG-17).
    private var sinkRestoreRunnable: Runnable? = null

    fun resetAudioSink() {
        val player = mediaSession?.player as? ExoPlayer ?: return

        // Cancel any in-flight restore from a previous call before we stop the
        // player again, so the restore does not run against a stale item list.
        sinkRestoreRunnable?.let { mainHandler.removeCallbacks(it) }

        // Capture the user's play INTENT, not isPlaying. A stream that is still
        // buffering reports isPlaying == false (it is only true in STATE_READY),
        // so restoring it without play() would leave it paused forever. This is
        // exactly what broke resume of radio stations on restart: the startup
        // DAC reset fired 1.5s after the service started while the auto-resumed
        // station was still buffering (BUG-20).
        val playWhenReady = player.playWhenReady
        val index      = player.currentMediaItemIndex
        // Live streams (duration == TIME_UNSET) report an unbounded position;
        // restoring it would seek past the end of a seekable station (BUG-19).
        val position   = if (player.duration == C.TIME_UNSET) 0L else player.currentPosition
        val items      = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        Log.i(TAG, "Resetting audio sink [playing=${player.isPlaying} playWhenReady=$playWhenReady idx=$index pos=$position]")

        // Pause + stop releases the AudioTrack → HAL session is closed
        player.pause()
        player.stop()

        val restoreRunnable = Runnable {
            sinkRestoreRunnable = null
            val p = mediaSession?.player as? ExoPlayer ?: return@Runnable
            // Restore the play intent even when there is nothing queued yet, so
            // a later setMediaItems() call does not inherit the pause() state.
            p.playWhenReady = playWhenReady
            if (items.isNotEmpty()) {
                p.setMediaItems(items, index, position)
                p.prepare()
                if (playWhenReady) p.play()
            }
            Log.i(TAG, "Audio sink reset complete")
        }
        sinkRestoreRunnable = restoreRunnable
        mainHandler.postDelayed(restoreRunnable, USB_RESET_GAP_MS)
    }

    // ── Bit-perfect audio processor chain ─────────────────────────────────────

    /**
     * Replaces DefaultAudioSink's default AudioProcessorChain.
     *
     * Why this exists: DefaultAudioSink.DefaultAudioProcessorChain *always*
     * appends a SilenceSkippingAudioProcessor and a SonicAudioProcessor
     * internally — there is no supported way to opt out of them by passing
     * an empty processor array, because the two-arg private constructor that
     * takes them isn't exposed. SonicAudioProcessor is what ExoPlayer uses to
     * time-stretch/pitch-shift audio when playback speed departs from 1.0x
     * (e.g. live-edge catch-up). Even though nothing in this codebase
     * currently sets a non-default PlaybackParameters, leaving Sonic in the
     * graph means a single future call to player.setPlaybackParameters(...)
     * — or a library/live-streaming feature added later — would silently
     * start stretching PCM and break bit-perfect output with zero warning.
     *
     * This chain removes that possibility structurally:
     *   - getAudioProcessors() returns an empty array, so no processor ever
     *     touches the PCM stream — it passes through completely unmodified.
     *   - applyPlaybackParameters() ignores whatever is requested and always
     *     reports PlaybackParameters.DEFAULT (speed=1.0, pitch=1.0) back to
     *     ExoPlayer, so even a stray setPlaybackParameters() call is a no-op
     *     at the sink level instead of engaging a stretch algorithm.
     *   - applySkipSilenceEnabled() is likewise hard-pinned to false.
     *
     * The tradeoff, by design: if the network/buffer can't keep up, ExoPlayer
     * has no speed-adjustment escape hatch left, so it stalls into
     * STATE_BUFFERING instead. That's the correct behavior for a bit-perfect
     * player — a silent pause is honest, a stretched sample is not.
     */
    private object BitPerfectAudioProcessorChain : AudioProcessorChain {
        private val NO_PROCESSORS = emptyArray<AudioProcessor>()

        override fun getAudioProcessors(): Array<AudioProcessor> = NO_PROCESSORS

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
            if (playbackParameters != PlaybackParameters.DEFAULT) {
                Log.w(
                    TAG_BITPERFECT,
                    "Ignoring non-1.0x PlaybackParameters request " +
                        "(speed=${playbackParameters.speed}, pitch=${playbackParameters.pitch}) " +
                        "— bit-perfect chain has no time-stretch processor"
                )
            }
            return PlaybackParameters.DEFAULT
        }

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

        override fun getMediaDuration(playoutDurationUs: Long): Long = playoutDurationUs

        override fun getSkippedOutputFrameCount(): Long = 0L
    }

    // ── Player factory ────────────────────────────────────────────────────────

    private lateinit var httpFactory: OkHttpDataSource.Factory

    private fun buildPlayer(): ExoPlayer {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val offloadProvider = BitPerfectOffloadProvider(am)
        val selectedAudioDevice = AtomicReference<android.media.AudioDeviceInfo?>(null)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val sink = DefaultAudioSink.Builder(context)
                .setEnableAudioTrackPlaybackParams(false)
                .setEnableFloatOutput(true)
                .setAudioProcessorChain(BitPerfectAudioProcessorChain)
                .setAudioOffloadSupportProvider(offloadProvider)
                .setAudioTrackProvider(object : DefaultAudioSink.AudioTrackProvider {
                    override fun getAudioTrack(
                        audioTrackConfig: AudioSink.AudioTrackConfig,
                        audioAttributes: AudioAttributes,
                        audioSessionId: Int
                    ): android.media.AudioTrack {
                        bitPerfectManager.updateAudioTrack(
                            audioTrackConfig,
                            selectedAudioDevice.get()
                        )
                        return DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                            audioTrackConfig, audioAttributes, audioSessionId
                        )
                    }
                })
                .build()
                return BitPerfectAudioSink(sink, bitPerfectManager, selectedAudioDevice)
            }
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val dataSourceFactory = DataSource.Factory {
            AppDataSource(this, httpFactory)
        }

        val mediaSourceFactory = SacdMediaSourceFactory(
            DefaultMediaSourceFactory(this, extractorsFactory)
                .setDataSourceFactory(dataSourceFactory)
        )

        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val builder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .experimentalSetDynamicSchedulingEnabled(true)

        if (settings.getBoolean(KEY_NETWORK_BUFFER, true)) {
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
                    .setTargetBufferBytes(BUFFER_MAX_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
        }

        val player = builder.build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    // Offload routes audio through the DSP and bypasses the bit-perfect
                    // mixer, so it must stay disabled for bit-perfect playback.
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()

        return player.also { it.addListener(playerListener) }
    }

    // ── Player listener ───────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        private var retryCount = 0

        override fun onEvents(player: Player, events: Player.Events) {
            // Media3 promotes/demotes its own media notification as playback
            // starts/stops — keep our LAN keep-foreground in sync.
            updateLanForeground()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName} — ${error.message}", error)
            // The current AudioTrack is no longer usable. Do not leave its preferred
            // mixer attributes active while reconnecting or moving to another item.
            bitPerfectManager.clear()
            val player = mediaSession?.player ?: return
            val autoReconnect = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RECONNECT, true)
            val isStream = player.currentMediaItem?.mediaId
                ?.let { it.startsWith("http://") || it.startsWith("https://") } == true

            if (autoReconnect && isStream && retryCount < MAX_RETRIES) {
                retryCount++
                Log.i(TAG, "Reconnect $retryCount/$MAX_RETRIES")
                mainHandler.postDelayed({
                    if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.play() }
                }, RECONNECT_DELAY_MS)
                return
            }
            retryCount = 0
            if (player.hasNextMediaItem()) { player.seekToNext(); player.prepare(); player.play() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retryCount = 0
            saveLastPlayed(mediaItem)
            resetIcyInfo(mediaItem?.mediaId)
            if (mediaItem == null) bitPerfectManager.clear()
        }

        override fun onMetadata(metadata: Metadata) {
            val mediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                val title = (if (entry is IcyInfo) entry.title else null)?.trim()
                if (!title.isNullOrBlank()) {
                    try {
                        mergeIcyInfo(mediaId, title = title)
                        Log.d(TAG, "ICY title: $title")
                    } catch (e: Exception) { Log.e(TAG, "ICY title merge failed", e) }
                }
            }
            // Push the captured stream info to the MediaSession so system surfaces
            // (Shield home Now Playing bar, other controllers) show the real
            // track/artist instead of "Bitperfect Player - Unknown".
            publishIcyMetadataToSession()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveCurrentPosition()
        }

        private var wasBuffering = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retryCount = 0
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
                saveCurrentPosition()

            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                // Stop/end releases the active audio pipeline. Keep the preference scoped
                // to an active track rather than leaving it set for the service lifetime.
                bitPerfectManager.clear()
            }

            // Bit-perfect design note: a buffer underrun surfaces here as a plain
            // stall (STATE_BUFFERING → playback pauses) rather than a speed-up
            // to catch up, since BitPerfectAudioProcessorChain removes the
            // time-stretch path entirely. Logged so stalls are visible/countable
            // instead of silently absorbed.
            if (playbackState == Player.STATE_BUFFERING && !wasBuffering) {
                wasBuffering = true
                Log.i(TAG_BITPERFECT, "Buffer underrun — stalling playback (no speed-up, bit-perfect chain)")
            } else if (playbackState != Player.STATE_BUFFERING && wasBuffering) {
                wasBuffering = false
                Log.i(TAG_BITPERFECT, "Stall recovered — playback resumed at 1.0x")
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Should never fire with anything but DEFAULT given BitPerfectAudioProcessorChain,
            // but log loudly if it ever does — that would indicate a regression.
            if (playbackParameters != PlaybackParameters.DEFAULT) {
                Log.e(
                    TAG_BITPERFECT,
                    "UNEXPECTED: playback parameters changed to speed=${playbackParameters.speed} " +
                        "pitch=${playbackParameters.pitch} — bit-perfect guarantee may be violated"
                )
            }
        }

    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        bitPerfectManager = BitPerfectManager(this)

        httpFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
                .readTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        ).setUserAgent(USER_AGENT)

        mediaSession = MediaSession.Builder(this, buildPlayer())
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val resume = try {
                        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                        if (!settings.getBoolean(KEY_RESUME_PLAYBACK, false)) null
                        else settings.getString("last_played_queue", null)?.let { queueJson ->
                            val items = rebuildSavedQueueItems(JSONArray(queueJson))
                            if (items.isEmpty()) null
                            else {
                                val index = settings.getInt("last_played_index", 0).coerceIn(0, items.lastIndex)
                                val pos = settings.getLong("last_played_pos", 0)
                                val restorePos = if (items[index].mediaId.startsWith("http://") || items[index].mediaId.startsWith("https://")) 0L else pos
                                MediaSession.MediaItemsWithStartPosition(items, index, restorePos)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Playback resumption failed: ${e.message}")
                        null
                    }
                    return if (resume != null) {
                        Log.i(TAG, "Playback resumption: restored ${resume.mediaItems.size} items")
                        Futures.immediateFuture(resume)
                    } else {
                        Futures.immediateFailedFuture(UnsupportedOperationException())
                    }
                }
            })
            .build()

        // Build the jcifs SMB context off the main thread so the first media-source
        // creation (which can run on the main thread during addMediaItems) is cheap.
        // This must happen AFTER the OkHttpClient is built: pre-warming registers the
        // BouncyCastle provider, and doing that concurrently with OkHttp's TLS setup
        // makes SSLContext.init fail with "BKS not found".
        Thread { SmbContext.prewarm() }.start()
        mainHandler.post(savePositionRunnable)

        // Restore the saved queue right away (before the MPD server is reachable)
        // so LAN clients never see a briefly-empty playlist after a process
        // restart — previously only MainFragment restored it, seconds later
        // (BUG: playlist items disappear in MALP for a few seconds).
        restoreSavedQueue()

        // Listen for USB plug/unplug events
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        // On startup: if a USB DAC is already connected, reset the sink so
        // Android HAL negotiates bit-perfect output from the very first track.
        mainHandler.postDelayed({ checkAndResetUsbAudio("startup") }, USB_SETTLE_MS)

        // Embedded MPD server for MALP / any MPD client (port 6600).
        // Runs on its own threads and drives playback only via MediaController
        // — never touches the bit-perfect audio chain.
        updateMpdServer()

        // Keep-alive watchdog: re-assert foreground state if the system drops it.
        mainHandler.post(lanFgWatchdog)
    }

    fun isMpdRunning(): Boolean = mpdServer != null
    fun getClientCount(): Int = mpdServer?.getClientCount() ?: 0

    /** Stops playback but keeps the service (and MPD server) alive for LAN control. */
    fun stopPlaybackKeepLanControl() {
        mainHandler.post {
            try {
                mediaSession?.player?.let { p ->
                    p.playWhenReady = false
                    p.stop()
                }
            } catch (_: Exception) {}
            updateLanForeground()
        }
    }

    fun updateMpdServer() {
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val enabled = prefs.getBoolean("mpd_enabled", true)
        val port = prefs.getInt("mpd_port", 6600).coerceIn(1024, 65535)
        try {
            if (enabled) {
                // Ensure the service is in the started state so it survives
                // activity unbind / task removal while LAN control is enabled.
                try { startService(Intent(this, PlaybackService::class.java)) } catch (_: Exception) {}
                val curPort = mpdServer?.getPort()
                if (mpdServer == null) {
                    mpdServer = MpdServer(this).also { it.start() }
                    Log.i(TAG, "MPD server started on $port (LAN control enabled)")
                } else if (curPort != null && curPort != port) {
                    mpdServer?.stop()
                    mpdServer = MpdServer(this).also { it.start() }
                    Log.i(TAG, "MPD server restarted on $port (port changed)")
                }
            } else {
                mpdServer?.stop()
                mpdServer = null
                Log.i(TAG, "MPD server stopped (LAN control disabled)")
            }
            updateLanForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update MPD server", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Media3's default stops the service when playback is idle, which kills
        // the MPD server and breaks LAN control after the app UI is closed.
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val keepAlive = prefs.getBoolean("mpd_enabled", true) &&
            prefs.getBoolean("mpd_autostart", false)
        if (keepAlive) {
            Log.i(TAG, "Task removed — keeping service alive for LAN control")
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Only this app and trusted system/media controllers may control playback.
        return if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) {
            mediaSession
        } else {
            null
        }
    }

    override fun onDestroy() {
        instance = null
        saveCurrentPosition()
        bitPerfectManager.clear()
        resetIcyInfo(null)
        try { mpdServer?.stop() } catch (_: Exception) {}
        mpdServer = null
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        mediaSession?.run { player.release(); release() }
        super.onDestroy()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveLastPlayed(mediaItem: MediaItem?) {
        val uri = mediaItem?.mediaId ?: return
        if (uri.startsWith("action:")) return
        val icy = icyInfo
        val title  = if (icy != null && icy.mediaId == uri && !icy.title.isNullOrBlank()) icy.title
                     else mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)

        if (settings.getBoolean(KEY_RESUME_PLAYBACK, false)) {
            val player = mediaSession?.player
            val queue  = JSONArray()
            player?.let {
                for (i in 0 until it.mediaItemCount) {
                    val item = it.getMediaItemAt(i)
                    queue.put(JSONObject().apply {
                        val localConfig = item.localConfiguration
                        val clipping = item.clippingConfiguration
                        put("mediaId", item.mediaId)
                        put("uri", localConfig?.uri?.toString() ?: item.mediaId)
                        put("title",   item.mediaMetadata.title?.toString()  ?: "")
                        put("artist",  item.mediaMetadata.artist?.toString() ?: "")
                        put("start", clipping.startPositionMs)
                        put("end", clipping.endPositionMs)
                    })
                }
            }
            settings.edit()
                .putString("last_played_uri",    uri)
                .putString("last_played_title",  title)
                .putString("last_played_artist", artist)
                .putInt(   "last_played_index",  player?.currentMediaItemIndex ?: 0)
                .putLong(  "last_played_pos",    player?.let { safePositionToSave(it) } ?: 0L)
                .putString("last_played_queue",  queue.toString())
                .apply()
        }

        if (settings.getBoolean(KEY_RECENT_FILES, true)) {
            try {
                val arr  = JSONArray(settings.getString("recent_list", "[]"))
                val newArr = JSONArray()
                newArr.put(JSONObject().apply { put("uri", uri); put("title", title); put("artist", artist) })
                for (i in 0 until arr.length()) {
                    if (newArr.length() >= RECENT_LIST_MAX) break
                    if (arr.getJSONObject(i).getString("uri") != uri) newArr.put(arr.getJSONObject(i))
                }
                settings.edit().putString("recent_list", newArr.toString()).apply()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /**
     * Restores the saved queue/index/position into the player if its queue is
     * empty. Mirrors MainFragment.resumeLastPlayed() but runs at service start,
     * so the MPD server never exposes an empty playlist after a restart.
     */
    private fun restoreSavedQueue() {
        try {
            val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
            if (!settings.getBoolean(KEY_RESUME_PLAYBACK, false)) return
            val player = mediaSession?.player ?: return
            if (player.mediaItemCount > 0) return
            val queueJson = settings.getString("last_played_queue", null) ?: return
            val items = rebuildSavedQueueItems(JSONArray(queueJson))
            if (items.isEmpty()) return
            val index = settings.getInt("last_played_index", 0).coerceIn(0, items.lastIndex)
            val pos = settings.getLong("last_played_pos", 0)
            // Radio streams never have a meaningful position (BUG-19): restart at 0.
            val restorePos = if (items[index].mediaId.startsWith("http://") || items[index].mediaId.startsWith("https://")) 0L else pos
            player.setMediaItems(items, index, restorePos)
            player.prepare()
            Log.i(TAG, "Restored ${items.size} queued items (index $index)")
        } catch (e: Exception) {
            Log.w(TAG, "Queue restore failed: ${e.message}")
        }
    }

    /** Rebuilds [MediaItem]s from the persisted "last_played_queue" JSON array. */
    private fun rebuildSavedQueueItems(queueArray: JSONArray): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        for (i in 0 until queueArray.length()) {
            val entry = queueArray.optJSONObject(i)
            if (entry == null) continue
            val mId = entry.optString("mediaId", "")
            val uri = entry.optString("uri", mId)
            val title = entry.optString("title", "")
            val artist = entry.optString("artist", "")
            val start = entry.optLong("start", 0)
            val end = entry.optLong("end", C.TIME_UNSET)
            if (uri.isEmpty()) continue
            val fallbackTitle = uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { mId }
            val meta = MediaMetadata.Builder().setTitle(title.ifBlank { fallbackTitle })
            if (artist.isNotBlank()) meta.setArtist(artist)
            val b = MediaItem.Builder()
                .setMediaId(mId)
                .setUri(uri.toUri())
                .setMediaMetadata(meta.build())
            if (start > 0 || end != C.TIME_UNSET) {
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                if (end != C.TIME_UNSET) clip.setEndPositionMs(end)
                b.setClippingConfiguration(clip.build())
            }
            items.add(b.build())
        }
        return items
    }

    /** Merges a piece of ICY info into the current holder (if it belongs to the active item). */
    private fun mergeIcyInfo(
        mediaId: String,
        title: String? = null,
        station: String? = null,
        genre: String? = null,
        description: String? = null,
        url: String? = null
    ) {
        synchronized(icyInfoLock) {
            // A loader can finish after the player has moved to another item. Do not allow
            // that late response to repopulate metadata for the old stream.
            if (activeMediaId != mediaId) return

            val cur = icyInfo
            val merged = if (cur != null && cur.mediaId == mediaId) {
                IcyStreamInfo(
                    mediaId,
                    title ?: cur.title,
                    station ?: cur.station,
                    genre ?: cur.genre,
                    description ?: cur.description,
                    url ?: cur.url
                )
            } else {
                IcyStreamInfo(mediaId, title, station, genre, description, url)
            }
            if (merged != cur) icyInfo = merged
        }
    }

    private fun resetIcyInfo(mediaId: String?) {
        synchronized(icyInfoLock) {
            activeMediaId = mediaId
            icyInfo = null
        }
    }

    /**
     * Publishes the captured stream info (ICY song title / artist / station name)
     * as the current item's metadata so system surfaces — the Shield home screen's
     * Now Playing bar, other media controllers — show the real track and artist
     * instead of the app name + "Unknown".
     */
    private fun publishIcyMetadataToSession() {
        try {
            val player = mediaSession?.player ?: return
            val item = player.currentMediaItem ?: return
            val icy = icyInfo ?: return
            if (icy.mediaId != item.mediaId) return

            val icyTitle = icy.title?.takeIf { it.isNotBlank() }
            var track = icyTitle
            var artist = ""
            val rawTrack = track
            if (rawTrack != null) {
                val delims = arrayOf(" - ", " – ", " — ", " : ", " | ")
                for (d in delims) {
                    if (rawTrack.contains(d)) { val p = rawTrack.split(d, limit = 2); artist = p[0].trim(); track = p[1].trim(); break }
                }
            }
            val station = icy.station?.takeIf { it.isNotBlank() }
            val base = item.mediaMetadata
            val builder = base.buildUpon()
            var changed = false
            // IMPORTANT: never overwrite the queue item's title with the live track —
            // the playlist/queue (and MPD) must keep the stream's own name. The live
            // track is stored in subtitle instead; the Now Playing display already
            // shows the live title via icyInfo / TrackInfoResolver.
            if (!track.isNullOrBlank() && track != base.subtitle) { builder.setSubtitle(track); changed = true }
            if (artist.isBlank() && station != null && station != track) artist = station
            if (artist.isNotBlank() && artist != base.artist) { builder.setArtist(artist); changed = true }
            // Stations repeat the same ICY title on every metadata frame — don't
            // replace the queue item unless something actually changed.
            if (!changed) return
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(builder.build()).build()
            )
        } catch (e: Exception) {
            Log.d(TAG, "publishIcyMetadata failed: ${e.message}")
        }
    }

    // ── Inner data source ─────────────────────────────────────────────────────

    @UnstableApi
    private inner class AppDataSource(context: Context, httpFactory: HttpDataSource.Factory) : DataSource {
        private val defaultSrc = DefaultDataSource.Factory(context, httpFactory).createDataSource()
        private val smbSrc     = SmbDataSource()
        private var active: DataSource? = null
        private var lastUri: String? = null

        override fun addTransferListener(l: TransferListener) { defaultSrc.addTransferListener(l); smbSrc.addTransferListener(l) }
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            lastUri = if (scheme == "http" || scheme == "https") dataSpec.uri.toString() else null
            active = if (scheme == "smb") smbSrc else defaultSrc
            return active?.open(dataSpec) ?: throw IOException("No data source available")
        }
        override fun read(b: ByteArray, o: Int, l: Int): Int = active?.read(b, o, l) ?: -1
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> {
            val headers = active?.responseHeaders ?: emptyMap()
            val uri = lastUri ?: return headers
            if (headers.isEmpty()) return headers

            // Called on the loading thread: must never throw or touch the player.
            // lastUri is the ORIGINAL item URL (dataSpec.uri from open()), which
            // matches the player item's mediaId even when the stream redirects —
            // merging under it keeps the ICY info matched to the item without
            // touching the player off the main thread (BUG: station name never
            // captured, "Player is accessed on the wrong thread").
            try {
                fun header(name: String): String? =
                    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                        ?.value?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

                mergeIcyInfo(
                    uri,
                    station     = header("icy-name"),
                    genre       = header("icy-genre"),
                    description = header("icy-description"),
                    url         = header("icy-url")
                )
                // Headers arrive on the loading thread; push to the session on main.
                mainHandler.post { publishIcyMetadataToSession() }
            } catch (e: Exception) { Log.e(TAG, "ICY header capture failed", e) }
            return headers
        }
        override fun close() { active?.close(); active = null }
    }
}
