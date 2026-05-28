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
    private var mediaSession: MediaSession? = null
    private val savePositionHandler = Handler(Looper.getMainLooper())
    private val savePositionRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            savePositionHandler.postDelayed(this, 10000)
        }
    }

    private fun saveCurrentPosition() {
        mediaSession?.player?.let { player ->
            if (player.playbackState != Player.STATE_IDLE) {
                getSharedPreferences("AppSettings", MODE_PRIVATE)
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
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setEnableFloatOutput(true)
                    .build()
            }
        }

        // Use OkHttp for better streaming support and ICY metadata
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("BitperfectPlayer/1.1 (Android TV)")

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val dataSourceFactory = DataSource.Factory {
            AppDataSource(this, httpDataSourceFactory)
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "Player Error: ${error.errorCodeName} - ${error.message}", error)
                // Skip to next if unplayable
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.prepare()
                    player.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveLastPlayed(mediaItem)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) saveCurrentPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
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

        val title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
        
        val settings = getSharedPreferences("AppSettings", MODE_PRIVATE)
        if (settings.getBoolean("resume_playback", false)) {
            val player = mediaSession?.player
            val queue = JSONArray()
            player?.let {
                for (i in 0 until it.mediaItemCount) {
                    val item = it.getMediaItemAt(i)
                    val obj = JSONObject()
                    obj.put("mediaId", item.mediaId)
                    obj.put("title", item.mediaMetadata.title?.toString() ?: "")
                    obj.put("artist", item.mediaMetadata.artist?.toString() ?: "")
                    queue.put(obj)
                }
            }
            settings.edit()
                .putString("last_played_uri", uri)
                .putString("last_played_title", title)
                .putString("last_played_artist", artist)
                .putInt("last_played_index", player?.currentMediaItemIndex ?: 0)
                .putString("last_played_queue", queue.toString())
                .apply()
        }
        
        if (settings.getBoolean("recent_files", true)) {
            try {
                val recentJson = settings.getString("recent_list", "[]")
                val recentArray = JSONArray(recentJson)
                val newArray = JSONArray()
                val newItem = JSONObject().apply {
                    put("uri", uri)
                    put("title", title)
                    put("artist", artist)
                }
                newArray.put(newItem)
                for (i in 0 until recentArray.length()) {
                    val oldUri = recentArray.getJSONObject(i).getString("uri")
                    if (oldUri != uri) {
                        newArray.put(recentArray.getJSONObject(i))
                    }
                    if (newArray.length() >= 20) break
                }
                settings.edit().putString("recent_list", newArray.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        saveCurrentPosition()
        savePositionHandler.removeCallbacks(savePositionRunnable)
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    @UnstableApi
    private class AppDataSource(context: Context, httpDataSourceFactory: HttpDataSource.Factory) : DataSource {
        private val defaultDataSource = DefaultDataSource.Factory(context, httpDataSourceFactory).createDataSource()
        private val smbDataSource = SmbDataSource()
        private var activeDataSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            defaultDataSource.addTransferListener(transferListener)
            smbDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            activeDataSource = if (dataSpec.uri.scheme == "smb") {
                smbDataSource
            } else {
                defaultDataSource
            }
            return activeDataSource!!.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeDataSource?.read(buffer, offset, length) ?: -1
        }

        override fun getUri(): Uri? = activeDataSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = activeDataSource?.responseHeaders ?: emptyMap()

        override fun close() {
            activeDataSource?.close()
            activeDataSource = null
        }
    }
}
