package com.example.bitperfectplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
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

        private const val BUFFER_MIN_MS        = 60_000
        private const val BUFFER_MAX_MS        = 120_000
        private const val BUFFER_PLAYBACK_MS   = 2_500
        private const val BUFFER_REBUFFER_MS   = 5_000
        private const val BUFFER_MAX_BYTES     = 128 * 1024 * 1024

        private const val RECENT_LIST_MAX      = 20

        private const val USB_SETTLE_MS        = 1_500L
        private const val USB_RESET_GAP_MS     = 400L

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

    /** mediaId of the currently playing item — written on the main thread only. */
    @Volatile private var activeMediaId: String? = null
    private val icyInfoLock = Any()

    // ── Position saver ────────────────────────────────────────────────────────

    private val savePositionRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            mainHandler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    private fun saveCurrentPosition() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit().putLong("last_played_pos", p.currentPosition).apply()
        }
    }

    /**
     * Saves the current playback position synchronously using commit() instead
     * of apply(). Called from the UI before System.exit(0) so the position is
     * guaranteed to be on disk before the process is killed (BUG-6).
     */
    fun saveCurrentPositionSync() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit().putLong("last_played_pos", p.currentPosition).commit()
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

        val wasPlaying = player.isPlaying
        val index      = player.currentMediaItemIndex
        val position   = player.currentPosition
        val items      = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        Log.i(TAG, "Resetting audio sink [playing=$wasPlaying idx=$index pos=$position]")

        // Pause + stop releases the AudioTrack → HAL session is closed
        player.pause()
        player.stop()

        val restoreRunnable = Runnable {
            sinkRestoreRunnable = null
            val p = mediaSession?.player as? ExoPlayer ?: return@Runnable
            if (items.isNotEmpty()) {
                p.setMediaItems(items, index, position)
                p.prepare()
                if (wasPlaying) p.play()
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

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

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

        mediaSession = MediaSession.Builder(this, buildPlayer()).build()
        mainHandler.post(savePositionRunnable)

        // Listen for USB plug/unplug events
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        // On startup: if a USB DAC is already connected, reset the sink so
        // Android HAL negotiates bit-perfect output from the very first track.
        mainHandler.postDelayed({ checkAndResetUsbAudio("startup") }, USB_SETTLE_MS)
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
            return active!!.open(dataSpec)
        }
        override fun read(b: ByteArray, o: Int, l: Int): Int = active?.read(b, o, l) ?: -1
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> {
            val headers = active?.responseHeaders ?: emptyMap()
            val uri = lastUri ?: return headers
            if (headers.isEmpty()) return headers

            // Called on the loading thread: must never throw or touch the player.
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
            } catch (e: Exception) { Log.e(TAG, "ICY header capture failed", e) }
            return headers
        }
        override fun close() { active?.close(); active = null }
    }
}
