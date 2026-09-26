package com.example.bitperfectplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : BaseActivity() {

    companion object {
        private const val PREFS_APP        = "AppSettings"
        private const val KEY_COLOR_SCHEME = "color_scheme"
        private const val KEY_WAVEFORM     = "waveform_type"
        private const val REQUEST_FILES    = 2001
        private const val REQUEST_PERMS    = 1
    }

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) addUrisToPlaylist(uris)
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Write defaults before the UI is created so the fragment reads the
        // correct initial values (e.g., resume_playback defaults to true).
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        if (!prefs.contains("screensaver_delay")) {
            prefs.edit {
                putInt("screensaver_delay", 1)
                putBoolean("resume_playback", true)
                putBoolean("auto_scan", true)
                putBoolean("network_buffer", true)
                putBoolean("auto_reconnect", true)
                putInt(KEY_WAVEFORM, 4)
                putInt(KEY_COLOR_SCHEME, 5)
            }
        }
        // Initialize newly added settings independently so upgrades do not silently
        // disable features when older preferences already contain screensaver_delay.
        if (!prefs.contains("resume_playback")) {
            prefs.edit { putBoolean("resume_playback", true) }
        }

        setContentView(R.layout.activity_main)

        checkPermissions()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(object : Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { refreshScreensaver() }
                    override fun onIsPlayingChanged(isPlaying: Boolean)               { refreshScreensaver() }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { refreshScreensaver() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            screensaverHandler.postDelayed({ refreshScreensaver() }, 2_000)
                        }
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    fun getController(): MediaController? = mediaController

    // ---------------------------------------------------------------------------
    // Screensaver
    // ---------------------------------------------------------------------------

    private fun refreshScreensaver() {
        if (!isScreensaverActive) return
        window.decorView.findViewWithTag<TextView>("screensaver_text")?.let { updateScreensaverText(it) }
        val waveform = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
        waveform?.setPlaying(mediaController?.isPlaying == true)
        waveform?.setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM, 0))
        waveform?.setColor(getThemeColor())
    }

    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController
        if (controller == null || controller.currentMediaItem == null) {
            textView.text = "Bitperfect Player"
            return
        }
        // Same track/artist/album resolution as the Now Playing screen and card.
        val info = TrackInfoResolver.resolve(controller, PlaybackService.icyInfo)
        var title = info.track
        val artist = info.artist
        val album = info.album

        // When the title embeds the artist ("Artist - Track") alongside a separate
        // artist row, strip the prefix so the value is not shown twice (BUG).
        if (artist.isNotBlank()) {
            for (d in arrayOf(" - ", " – ", " — ", " : ", " | ")) {
                val prefix = artist + d
                if (title.startsWith(prefix)) { title = title.substring(prefix.length).trim(); break }
            }
        }

        val sb = SpannableStringBuilder()
        sb.append("Now Playing:\n\n")

        val iconColor = android.graphics.Color.LTGRAY
        val iconSize = (textView.textSize * 1.2f).toInt()

        fun appendRow(text: String, iconRes: Int) {
            if (text.isBlank()) return
            val start = sb.length
            sb.append("  ") // placeholder
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply {
                setTint(iconColor)
                setBounds(0, 0, iconSize, iconSize)
            }
            if (drawable != null) {
                sb.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(text).append("\n")
        }

        appendRow(title, R.drawable.ic_audio)
        // Skip the artist row when the artist is already part of the title
        // (e.g. station "Solar Radio High" in title "Solar Radio High [256kbps]")
        if (artist.isNotBlank() && artist != title && !title.startsWith(artist)) appendRow(artist, R.drawable.ic_artist)
        if (album.isNotBlank() && album != title && album != artist) appendRow(album, R.drawable.ic_album_art)

        textView.text = sb
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
    }

    override fun onScreensaverCreated(container: android.view.ViewGroup) {
        val density = resources.displayMetrics.density
        AnimatedWaveformView(this).apply {
            tag = "screensaver_waveform"
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (400 * density).toInt(),
                (64 * density).toInt()
            ).also { it.topMargin = (24 * density).toInt() }
            setPlaying(mediaController?.isPlaying == true)
            setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM, 0))
            setColor(getThemeColor())
            container.addView(this)
        }
    }

    private fun getThemeColor(): Int {
        val index = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
        return if (index in CardPresenter.THEME_COLORS.indices)
            CardPresenter.THEME_COLORS[index]
        else
            CardPresenter.THEME_COLORS[0]
    }

    // ---------------------------------------------------------------------------
    // Playlist management
    // ---------------------------------------------------------------------------

    private fun addUrisToPlaylist(uris: List<Uri>) {
        val controller = mediaController ?: return

        Thread {
            val allItems = mutableListOf<MediaItem>()
            for (uri in uris) {
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                when {
                    fileName.endsWith(".m3u") || fileName.endsWith(".m3u8") -> allItems.addAll(parseM3u(uri))
                    fileName.endsWith(".pls")                               -> allItems.addAll(parsePls(uri))
                    fileName.endsWith(".cue")                               -> allItems.addAll(parseCue(uri))
                    else                                                    -> allItems.add(createMediaItem(uri))
                }
            }

            runOnUiThread {
                if (allItems.isEmpty()) return@runOnUiThread
                // Collect existing items and append new ones atomically to avoid per-add glitches
                val existing = (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it) }
                val merged   = existing + allItems
                controller.setMediaItems(merged)

                if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                    controller.prepare()
                    controller.play()
                }
                Toast.makeText(this, "Playlist: ${merged.size} items total", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun createMediaItem(uri: Uri): MediaItem {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}

        val meta = MetadataUtils.getMetadata(this, uri)
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(PlaylistParser.mimeTypeFor(uri.toString()))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(meta.title ?: uri.lastPathSegment ?: "Unknown")
                    .setArtist(meta.artist ?: "")
                    .setAlbumTitle(meta.album ?: "")
                    .build()
            )
            .build()
    }

    // ---------------------------------------------------------------------------
    // Playlist parsers
    // ---------------------------------------------------------------------------

    fun parseM3u(uri: Uri, basePath: String? = null): List<MediaItem> {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val finalBase = basePath ?: if (uri.scheme == "file") uri.path?.substringBeforeLast("/") else null
                parseM3uFromStream(inputStream, finalBase)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
    }

    fun parseM3uFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> =
        PlaylistParser.parseM3uFromStream(inputStream, basePath)

    fun parsePls(uri: Uri, basePath: String? = null): List<MediaItem> {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val finalBase = basePath ?: if (uri.scheme == "file") uri.path?.substringBeforeLast("/") else null
                parsePlsFromStream(inputStream, finalBase)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
    }

    fun parsePlsFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> =
        PlaylistParser.parsePlsFromStream(inputStream, basePath)

    // ---------------------------------------------------------------------------
    // File picker (legacy ACTION_GET_CONTENT fallback used by some Android TV devices)
    // ---------------------------------------------------------------------------

    fun pickFiles() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                putExtra(
                    android.content.Intent.EXTRA_MIME_TYPES,
                    arrayOf("audio/*", "application/octet-stream", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
                )
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_FILES)
        } catch (e: Exception) {
            Toast.makeText(this, "File manager not found.", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILES && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { clip -> repeat(clip.itemCount) { uris.add(clip.getItemAt(it).uri) } }
                ?: data?.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) addUrisToPlaylist(uris)
        }
    }

    // ---------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------

    private fun checkPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        (supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment)
            ?.refreshWithCurrentFocus()
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    fun parseCue(uri: Uri): List<MediaItem> {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val basePath = if (uri.scheme == "file") uri.path?.substringBeforeLast("/") else uri.toString().substringBeforeLast("%2F")
                parseCueFromStream(inputStream, basePath)
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
    }

    fun parseCueFromStream(inputStream: java.io.InputStream, basePath: String?): List<MediaItem> =
        PlaylistParser.parseCueFromStream(inputStream, basePath)
}
