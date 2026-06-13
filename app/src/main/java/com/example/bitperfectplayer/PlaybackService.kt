package com.example.bitperfectplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
        private const val PREFS_APP               = "AppSettings"
        private const val KEY_NETWORK_BUFFER      = "network_buffer"
        private const val KEY_AUTO_RECONNECT      = "auto_reconnect"
        private const val KEY_RESUME_PLAYBACK     = "resume_playback"
        private const val KEY_RECENT_FILES        = "recent_files"

        private const val HTTP_TIMEOUT_SECS       = 20L
        private const val USER_AGENT              = "BitperfectPlayer/1.1 (Android TV)"
        private const val POSITION_SAVE_INTERVAL  = 10_000L
        private const val RECONNECT_DELAY_MS      = 5_000L
        private const val MAX_RETRIES             = 5

        // Buffer settings (milliseconds / bytes)
        private const val BUFFER_MIN_MS           = 60_000
        private const val BUFFER_MAX_MS           = 120_000
        private const val BUFFER_PLAYBACK_MS      = 2_500
        private const val BUFFER_REBUFFER_MS      = 5_000
        private const val BUFFER_MAX_BYTES        = 128 * 1024 * 1024 // 128 MB

        private const val RECENT_LIST_MAX         = 20
    }

    private var mediaSession: MediaSession? = null
    private val savePositionHandler = Handler(Looper.getMainLooper())
    private val savePositionRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            savePositionHandler.postDelayed(this, POSITION_SAVE_INTERVAL)
        }
    }

    private fun saveCurrentPosition() {
        mediaSession?.player?.let { player ->
            if (player.playbackState != Player.STATE_IDLE) {
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit().putLong("last_played_pos", player.currentPosition).apply()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

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

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(USER_AGENT)

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val dataSourceFactory = DataSource.Factory {
            AppDataSource(this, httpDataSourceFactory)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)

        if (settings.getBoolean(KEY_NETWORK_BUFFER, true)) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
                .setTargetBufferBytes(BUFFER_MAX_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            playerBuilder.setLoadControl(loadControl)
        }

        val player = playerBuilder.build()

        player.addListener(object : Player.Listener {
            private var retryCount = 0

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "Player error: ${error.errorCodeName} — ${error.message}", error)

                val autoReconnect = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_RECONNECT, true)
                val isNetworkStream = player.currentMediaItem?.mediaId
                    ?.let { it.startsWith("http://") || it.startsWith("https://") } == true

                if (autoReconnect && isNetworkStream && retryCount < MAX_RETRIES) {
                    retryCount++
                    android.util.Log.i("PlaybackService", "Reconnect attempt $retryCount/$MAX_RETRIES")
                    savePositionHandler.postDelayed({
                        if (player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                            player.play()
                        }
                    }, RECONNECT_DELAY_MS)
                    return
                }

                // Give up — skip to next track if possible
                retryCount = 0
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.prepare()
                    player.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                retryCount = 0
                saveLastPlayed(mediaItem)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) saveCurrentPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retryCount = 0
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    saveCurrentPosition()
                }
            }
        })

        mediaSession = MediaSession.Builder(this, player).build()
        savePositionHandler.post(savePositionRunnable)
    }

    private fun saveLastPlayed(mediaItem: MediaItem?) {
        val uri = mediaItem?.mediaId ?: return
        if (uri.startsWith("action:")) return

        val title  = mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
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
                        put("title",  item.mediaMetadata.title?.toString() ?: "")
                        put("artist", item.mediaMetadata.artist?.toString() ?: "")
                    })
                }
            }
            settings.edit()
                .putString("last_played_uri",    uri)
                .putString("last_played_title",  title)
                .putString("last_played_artist", artist)
                .putInt("last_played_index",     player?.currentMediaItemIndex ?: 0)
                .putString("last_played_queue",  queue.toString())
                .apply()
        }

        if (settings.getBoolean(KEY_RECENT_FILES, true)) {
            try {
                val recentJson  = settings.getString("recent_list", "[]")
                val recentArray = JSONArray(recentJson)
                val newArray    = JSONArray()
                newArray.put(JSONObject().apply {
                    put("uri",    uri)
                    put("title",  title)
                    put("artist", artist)
                })
                for (i in 0 until recentArray.length()) {
                    if (newArray.length() >= RECENT_LIST_MAX) break
                    val oldUri = recentArray.getJSONObject(i).getString("uri")
                    if (oldUri != uri) newArray.put(recentArray.getJSONObject(i))
                }
                settings.edit().putString("recent_list", newArray.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        saveCurrentPosition()
        savePositionHandler.removeCallbacks(savePositionRunnable)
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // Inner data source — routes smb:// to SmbDataSource, everything else to ExoPlayer's default
    // ---------------------------------------------------------------------------

    @UnstableApi
    private class AppDataSource(context: Context, httpDataSourceFactory: HttpDataSource.Factory) : DataSource {
        private val defaultDataSource = DefaultDataSource.Factory(context, httpDataSourceFactory).createDataSource()
        private val smbDataSource     = SmbDataSource()
        private var activeDataSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            defaultDataSource.addTransferListener(transferListener)
            smbDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            activeDataSource = if (dataSpec.uri.scheme == "smb") smbDataSource else defaultDataSource
            return activeDataSource!!.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            activeDataSource?.read(buffer, offset, length) ?: -1

        override fun getUri(): Uri? = activeDataSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            activeDataSource?.responseHeaders ?: emptyMap()

        override fun close() {
            activeDataSource?.close()
            activeDataSource = null
        }
    }
}
