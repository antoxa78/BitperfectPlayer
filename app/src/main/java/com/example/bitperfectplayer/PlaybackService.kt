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
import androidx.media3.common.Player
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    }

    // ── USB DAC monitoring ────────────────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached — will check for DAC in ${USB_SETTLE_MS}ms")
                    mainHandler.postDelayed(
                        { checkAndResetUsbAudio("USB attach") },
                        USB_SETTLE_MS
                    )
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                }
            }
        }
    }

    /**
     * Receives ACTION_SCREEN_ON (Shield waking from sleep).
     * After sleep the USB DAC HAL session is invalid — ExoPlayer's audio
     * renderer throws MediaCodecAudioRenderer error (format_supported=YES)
     * because the format is fine but the audio device isn't ready yet.
     * We wait USB_SETTLE_MS for the DAC to re-enumerate, then force a sink reset.
     */
    private val screenWakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                Log.i(TAG, "Screen on (wake from sleep) — scheduling DAC reset in ${USB_SETTLE_MS}ms")
                mainHandler.postDelayed(
                    { checkAndResetUsbAudio("screen wake") },
                    USB_SETTLE_MS
                )
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
     * Tears down ExoPlayer's AudioTrack (closing the HAL session) and
     * immediately rebuilds it, forcing Android to re-negotiate bit-perfect
     * output parameters with the DAC.
     *
     * Current queue, position, and play-state are fully restored.
     */
    fun resetAudioSink() {
        val player = mediaSession?.player as? ExoPlayer ?: return

        val wasPlaying = player.isPlaying
        val index      = player.currentMediaItemIndex
        val position   = player.currentPosition
        val items      = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        Log.i(TAG, "Resetting audio sink [playing=$wasPlaying idx=$index pos=$position]")

        // Pause + stop releases the AudioTrack → HAL session is closed
        player.pause()
        player.stop()

        mainHandler.postDelayed({
            val p = mediaSession?.player as? ExoPlayer ?: return@postDelayed
            if (items.isNotEmpty()) {
                p.setMediaItems(items, index, position)
                p.prepare()
                if (wasPlaying) p.play()
            }
            Log.i(TAG, "Audio sink reset complete")
        }, USB_RESET_GAP_MS)
    }

    // ── Player factory ────────────────────────────────────────────────────────

    private lateinit var httpFactory: OkHttpDataSource.Factory

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableAudioTrackPlaybackParams(false)
                .setEnableFloatOutput(true)
                .build()
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val dataSourceFactory = DataSource.Factory {
            AppDataSource(this, httpFactory)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val builder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)

        if (settings.getBoolean(KEY_NETWORK_BUFFER, true)) {
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
                    .setTargetBufferBytes(BUFFER_MAX_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
        }

        return builder.build().also { it.addListener(playerListener) }
    }

    // ── Player listener ───────────────────────────────────────────────────────

    private val playerListener = object : Player.Listener {
        private var retryCount = 0

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName} — ${error.message}", error)
            val player = mediaSession?.player ?: return

            // MediaCodecAudioRenderer error after sleep/wake: the USB DAC HAL session
            // is invalid even though format_supported=YES. Reset the audio sink and
            // resume playback — no manual intervention needed.
            val cause = error.cause
            val isAudioRendererError =
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                cause?.javaClass?.name?.contains("AudioSink") == true ||
                cause?.javaClass?.name?.contains("AudioTrack") == true ||
                error.message?.contains("MediaCodecAudioRenderer") == true ||
                error.message?.contains("AudioSink") == true

            if (isAudioRendererError && retryCount < 2) {
                retryCount++
                Log.i(TAG, "Audio renderer error — resetting DAC sink (attempt $retryCount)")
                mainHandler.postDelayed({
                    checkAndResetUsbAudio("renderer error recovery")
                }, USB_SETTLE_MS)
                return
            }

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
            retryCount = 0; saveLastPlayed(mediaItem)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) saveCurrentPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retryCount = 0
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
                saveCurrentPosition()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this

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

        // Listen for screen-on (Shield waking from sleep) to auto-reset the DAC
        registerReceiver(screenWakeReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        // On startup: if a USB DAC is already connected, reset the sink so
        // Android HAL negotiates bit-perfect output from the very first track.
        mainHandler.postDelayed({ checkAndResetUsbAudio("startup") }, USB_SETTLE_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        instance = null
        saveCurrentPosition()
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenWakeReceiver) } catch (_: Exception) {}
        mediaSession?.run { player.release(); release() }
        super.onDestroy()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveLastPlayed(mediaItem: MediaItem?) {
        val uri = mediaItem?.mediaId ?: return
        if (uri.startsWith("action:")) return
        val title  = mediaItem.mediaMetadata.title?.toString()  ?: "Unknown"
        val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)

        if (settings.getBoolean(KEY_RESUME_PLAYBACK, false)) {
            val player = mediaSession?.player
            val queue  = JSONArray()
            player?.let {
                for (i in 0 until it.mediaItemCount) {
                    val item = it.getMediaItemAt(i)
                    queue.put(JSONObject().apply {
                        put("mediaId", item.mediaId)
                        put("title",   item.mediaMetadata.title?.toString()  ?: "")
                        put("artist",  item.mediaMetadata.artist?.toString() ?: "")
                        put("uri",     item.localConfiguration?.uri?.toString() ?: item.mediaId)
                        
                        val clip = item.clippingConfiguration
                        if (clip.startPositionMs > 0) {
                            put("start", clip.startPositionMs)
                        }
                        if (clip.endPositionMs != androidx.media3.common.C.TIME_UNSET && clip.endPositionMs != androidx.media3.common.C.LENGTH_UNSET.toLong()) {
                            put("end", clip.endPositionMs)
                        }
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

    // ── Inner data source ─────────────────────────────────────────────────────

    @UnstableApi
    private class AppDataSource(context: Context, httpFactory: HttpDataSource.Factory) : DataSource {
        private val defaultSrc = DefaultDataSource.Factory(context, httpFactory).createDataSource()
        private val smbSrc     = SmbDataSource()
        private var active: DataSource? = null

        override fun addTransferListener(l: TransferListener) { defaultSrc.addTransferListener(l); smbSrc.addTransferListener(l) }
        override fun open(dataSpec: DataSpec): Long { active = if (dataSpec.uri.scheme == "smb") smbSrc else defaultSrc; return active!!.open(dataSpec) }
        override fun read(b: ByteArray, o: Int, l: Int): Int = active?.read(b, o, l) ?: -1
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
    }
}
