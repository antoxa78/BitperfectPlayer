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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.BufferedReader
import java.io.InputStreamReader

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
        setContentView(R.layout.activity_main)

        // Write defaults on first run only
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
        }, MoreExecutors.directExecutor())
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
        if (controller == null || controller.mediaMetadata.title == null) {
            textView.text = "Bitperfect Player"
            return
        }
        val metadata = controller.mediaMetadata
        val title  = metadata.title?.toString() ?: "Bitperfect Player"
        val artist = metadata.artist?.toString() ?: ""
        val album = metadata.albumTitle?.toString() ?: ""

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
        appendRow(artist, R.drawable.ic_artist)
        appendRow(album, R.drawable.ic_album_art)

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
            .setMimeType(mimeTypeFor(uri.toString()))
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

    fun parseM3uFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null

            while (reader.readLine().also { line = it } != null) {
                var trimmed = line!!.trim().removePrefix("\uFEFF")
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXTINF:")) {
                    val comma = trimmed.indexOf(',')
                    if (comma != -1) currentTitle = trimmed.substring(comma + 1)
                } else if (!trimmed.startsWith("#")) {
                    // Normalise Windows path separators
                    val normalizedPath = trimmed.replace("\\", "/")
                    val itemUriString  = resolveRelativePath(normalizedPath, basePath)
                    val itemUri        = parseEntryUri(itemUriString, basePath) ?: run { currentTitle = null; continue }

                    val metaBuilder = MediaMetadata.Builder()
                    var finalTitle  = currentTitle ?: itemUri.lastPathSegment ?: trimmed
                    if (finalTitle.contains(" - ")) {
                        val parts = finalTitle.split(" - ", limit = 2)
                        metaBuilder.setArtist(parts[0].trim())
                        finalTitle = parts[1].trim()
                    }

                    items.add(
                        MediaItem.Builder()
                            .setMediaId(itemUri.toString())
                            .setUri(itemUri)
                            .setMimeType(mimeTypeFor(itemUri.toString()))
                            .setMediaMetadata(metaBuilder.setTitle(finalTitle).build())
                            .build()
                    )
                    currentTitle = null
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

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

    fun parsePlsFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val props  = linkedMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var trimmed = line!!.trim().removePrefix("\uFEFF")
                if (trimmed.isEmpty() || trimmed.startsWith("[")) continue
                val eq = trimmed.indexOf('=')
                if (eq != -1) props[trimmed.substring(0, eq).trim().lowercase()] = trimmed.substring(eq + 1).trim()
            }

            val count = props.remove("numberofentries")?.toIntOrNull() ?: 0
            for (i in 1..count) {
                val file = props.remove("file$i") ?: continue
                val normalizedPath = file.replace("\\", "/")
                val itemUriString  = resolveRelativePath(normalizedPath, basePath)
                val itemUri        = parseEntryUri(itemUriString, basePath) ?: continue

                var finalTitle = props.remove("title$i")
                    ?: itemUri.lastPathSegment
                    ?: itemUriString.substringAfterLast("/").substringBeforeLast(".")
                props.remove("length$i") // consume but ignore

                val metaBuilder = MediaMetadata.Builder()
                if (finalTitle.contains(" - ")) {
                    val parts = finalTitle.split(" - ", limit = 2)
                    metaBuilder.setArtist(parts[0].trim())
                    finalTitle = parts[1].trim()
                }
                items.add(
                    MediaItem.Builder()
                        .setMediaId(itemUri.toString())
                        .setUri(itemUri)
                        .setMimeType(mimeTypeFor(itemUri.toString()))
                        .setMediaMetadata(metaBuilder.setTitle(finalTitle).build())
                        .build()
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

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

    /**
     * Resolves a relative playlist entry path against [basePath], handling both
     * regular filesystem paths and SAF (Storage Access Framework) URIs.
     */
    private fun resolveRelativePath(path: String, basePath: String?): String {
        if (basePath == null || path.contains("://") || path.startsWith("/")) return path
        return when {
            basePath.contains("%2F") && !basePath.startsWith("file://") -> {
                val encoded = Uri.encode(path).replace("/", "%2F")
                if (basePath.endsWith("%2F")) "$basePath$encoded" else "$basePath%2F$encoded"
            }
            basePath.endsWith("/") -> "$basePath$path"
            else -> "$basePath/$path"
        }
    }

    private fun parseEntryUri(uriString: String, basePath: String?): Uri? = try {
        when {
            uriString.startsWith("/")          -> Uri.fromFile(java.io.File(uriString))
            uriString.startsWith("file://")    -> Uri.fromFile(java.io.File(uriString.substring(7)))
            uriString.startsWith("content://") ||
            uriString.startsWith("http://")    ||
            uriString.startsWith("https://")   ||
            uriString.startsWith("smb://") -> uriString.toUri()
            // Last-ditch attempt: partial SAF path
            basePath == null && uriString.startsWith("primary%3A") -> uriString.toUri()
            else -> null
        }
    } catch (e: Exception) { null }

    private fun mimeTypeFor(uriString: String): String? {
        val lower = uriString.lowercase()
        return when {
            lower.endsWith(".flac")               -> MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3")                -> MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav")                -> MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg")                -> MimeTypes.AUDIO_OGG
            lower.endsWith(".ape")                -> "audio/x-ape"
            else                                  -> null
        }
    }

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

    fun parseCueFromStream(inputStream: java.io.InputStream, basePath: String?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentFile: String? = null
            var albumTitle: String? = null
            var albumArtist: String? = null
            
            data class CueTrack(val number: Int, var title: String? = null, var artist: String? = null, var startTimeMs: Long = 0)
            val tracks = mutableListOf<CueTrack>()
            var currentTrack: CueTrack? = null

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim().removePrefix("\uFEFF")
                val upper = trimmed.uppercase()

                when {
                    upper.startsWith("FILE") -> {
                        currentFile = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("TITLE") && currentTrack == null -> {
                        albumTitle = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("PERFORMER") && currentTrack == null -> {
                        albumArtist = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("TRACK") -> {
                        val num = trimmed.split(" ")[1].toIntOrNull() ?: 0
                        currentTrack = CueTrack(num)
                        tracks.add(currentTrack)
                    }
                    upper.startsWith("TITLE") && currentTrack != null -> {
                        currentTrack.title = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("PERFORMER") && currentTrack != null -> {
                        currentTrack.artist = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("INDEX 01") && currentTrack != null -> {
                        val timeStr = trimmed.substringAfter("INDEX 01").trim()
                        currentTrack.startTimeMs = parseCueTime(timeStr)
                    }
                }
            }

            if (currentFile != null && tracks.isNotEmpty()) {
                val audioUriString = resolveRelativePath(currentFile, basePath)
                val audioUri = parseEntryUri(audioUriString, basePath)
                
                if (audioUri != null) {
                    for (i in tracks.indices) {
                        val track = tracks[i]
                        val nextTrackStart = if (i + 1 < tracks.size) tracks[i+1].startTimeMs else C.TIME_UNSET
                        
                        val metaBuilder = MediaMetadata.Builder()
                            .setTitle(track.title ?: "Track ${track.number}")
                            .setArtist(track.artist ?: albumArtist ?: "Unknown Artist")
                            .setAlbumTitle(albumTitle ?: "Unknown Album")
                        
                        val clippingBuilder = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(track.startTimeMs)
                        if (nextTrackStart != C.TIME_UNSET) {
                            clippingBuilder.setEndPositionMs(nextTrackStart)
                        }

                        items.add(
                            MediaItem.Builder()
                                .setMediaId("${audioUri}_${track.number}")
                                .setUri(audioUri)
                                .setMimeType(mimeTypeFor(audioUri.toString()))
                                .setMediaMetadata(metaBuilder.build())
                                .setClippingConfiguration(clippingBuilder.build())
                                .build()
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

    private fun parseCueTime(timeStr: String): Long {
        // MM:SS:FF where FF is frames (1/75th of a second)
        val parts = timeStr.split(":")
        if (parts.size != 3) return 0
        val m = parts[0].toLongOrNull() ?: 0
        val s = parts[1].toLongOrNull() ?: 0
        val f = parts[2].toLongOrNull() ?: 0
        return (m * 60 * 1000) + (s * 1000) + (f * 1000 / 75)
    }
}
