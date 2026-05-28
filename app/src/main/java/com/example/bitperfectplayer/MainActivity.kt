package com.example.bitperfectplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : BaseActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            addUrisToPlaylist(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("AppSettings", MODE_PRIVATE)
        if (!prefs.contains("screensaver_delay")) {
            prefs.edit()
                .putInt("screensaver_delay", 1)
                .putBoolean("resume_playback", true)
                .putBoolean("auto_scan", true)
                .putInt("waveform_type", 4)
                .putInt("color_scheme", 5)
                .apply()
        }

        checkPermissions()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(object : Player.Listener {
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        refreshScreensaver()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        refreshScreensaver()
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        refreshScreensaver()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            screensaverHandler.postDelayed({ refreshScreensaver() }, 2000)
                        }
                    }
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun refreshScreensaver() {
        if (isScreensaverActive) {
            val screensaverText = window.decorView.findViewWithTag<TextView>("screensaver_text")
            if (screensaverText != null) {
                updateScreensaverText(screensaverText)
            }
            val screensaverWaveform = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
            screensaverWaveform?.setPlaying(mediaController?.isPlaying == true)
            val waveType = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("waveform_type", 0)
            screensaverWaveform?.setWaveformType(waveType)
            screensaverWaveform?.setColor(getThemeColor())
        }
    }

    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController
        if (controller == null || controller.mediaMetadata.title == null) {
            textView.text = "Bitperfect Player"
            return
        }
        
        val metadata = controller.mediaMetadata
        val title = metadata.title?.toString() ?: "Bitperfect Player"
        val artist = metadata.artist?.toString() ?: ""
        
        textView.text = if (artist.isNotEmpty()) "Now Playing:\n$title\n$artist" else "Now Playing:\n$title"
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
    }

    private fun getThemeColor(): Int {
        val colors = intArrayOf(
            0xFF00E676.toInt(), // Neon Green
            0xFF2979FF.toInt(), // Electric Blue
            0xFFFFC400.toInt(), // Amber Gold
            0xFF7C4DFF.toInt(), // Deep Purple
            0xFFFF4081.toInt(), // Hot Pink
            0xFF888888.toInt(), // Grey
            0xFFFF5252.toInt(), // Red
            0xFF1B5E20.toInt(), // Dark Green
            0xFFFFFFFF.toInt()  // White
        )
        val index = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("color_scheme", 0)
        return if (index in colors.indices) colors[index] else colors[0]
    }

    override fun onScreensaverCreated(container: android.view.ViewGroup) {
        val waveform = AnimatedWaveformView(this).apply {
            tag = "screensaver_waveform"
            val density = resources.displayMetrics.density
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (400 * density).toInt(),
                (64 * density).toInt()
            ).apply {
                topMargin = (24 * density).toInt()
            }
            setPlaying(mediaController?.isPlaying == true)
            val waveType = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("waveform_type", 0)
            setWaveformType(waveType)
            setColor(getThemeColor())
        }
        container.addView(waveform)
    }

    private fun addUrisToPlaylist(uris: List<Uri>) {
        val controller = mediaController ?: return
        
        Thread {
            val allItems = mutableListOf<MediaItem>()
            for (uri in uris) {
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                when {
                    fileName.endsWith(".m3u") || fileName.endsWith(".m3u8") -> {
                        allItems.addAll(parseM3u(uri))
                    }
                    fileName.endsWith(".pls") -> {
                        allItems.addAll(parsePls(uri))
                    }
                    else -> {
                        allItems.add(createMediaItem(uri))
                    }
                }
            }

            runOnUiThread {
                if (allItems.isNotEmpty()) {
                    // Collect current items and add new ones to avoid issues with addMediaItems on some devices
                    val currentItems = mutableListOf<MediaItem>()
                    for (i in 0 until controller.mediaItemCount) {
                        currentItems.add(controller.getMediaItemAt(i))
                    }
                    currentItems.addAll(allItems)
                    
                    controller.setMediaItems(currentItems)
                    
                    if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                        controller.prepare()
                        controller.play()
                    }
                    Toast.makeText(this, "Playlist: ${currentItems.size} items total", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun createMediaItem(uri: Uri): MediaItem {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {}

        val lower = uri.toString().lowercase()
        val mimeType = when {
            lower.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
            else -> null
        }

        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(uri.lastPathSegment ?: "Unknown")
                    .build()
            )
            .build()
    }

    fun parseM3u(uri: Uri, basePath: String? = null): List<MediaItem> {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val finalBasePath = basePath ?: if (uri.scheme == "file") uri.path?.substringBeforeLast("/") else null
                return parseM3uFromStream(inputStream, finalBasePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    fun parseM3uFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null
            
            while (reader.readLine().also { line = it } != null) {
                var trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                // Handle UTF-8 BOM
                if (trimmed.startsWith("\uFEFF")) {
                    trimmed = trimmed.substring(1)
                }

                if (trimmed.startsWith("#EXTINF:")) {
                    // Extract title after the comma
                    val commaIndex = trimmed.indexOf(",")
                    if (commaIndex != -1) {
                        currentTitle = trimmed.substring(commaIndex + 1)
                    }
                } else if (!trimmed.startsWith("#")) {
                    // Normalize backslashes for cross-platform compatibility
                    val normalizedPath = trimmed.replace("\\", "/")
                    var itemUriString = normalizedPath
                    
                    if (basePath != null && !normalizedPath.contains("://") && !normalizedPath.startsWith("/")) {
                        if (basePath.contains("%2F") && !basePath.startsWith("file://")) {
                            // Likely a SAF URI where / is encoded as %2F
                            val encodedPath = Uri.encode(normalizedPath).replace("/", "%2F")
                            itemUriString = if (basePath.endsWith("%2F")) basePath + encodedPath else "$basePath%2F$encodedPath"
                        } else {
                            if (basePath.endsWith("/")) itemUriString = basePath + normalizedPath else itemUriString = "$basePath/$normalizedPath"
                        }
                    }

                    val itemUri = try {
                        when {
                            itemUriString.startsWith("/") -> Uri.fromFile(java.io.File(itemUriString))
                            itemUriString.startsWith("file://") -> {
                                val path = itemUriString.substring(7)
                                Uri.fromFile(java.io.File(path))
                            }
                            itemUriString.startsWith("content://") ||
                            itemUriString.startsWith("http://") || itemUriString.startsWith("https://") ||
                            itemUriString.startsWith("smb://") -> Uri.parse(itemUriString)
                            else -> if (basePath == null && itemUriString.startsWith("primary%3A")) {
                                // Likely a partial SAF path, try to parse
                                Uri.parse(itemUriString)
                            } else null
                        }
                    } catch (e: Exception) { null }

                    if (itemUri != null) {
                        val lowercaseUrl = itemUri.toString().lowercase()
                        val mimeType = when {
                            lowercaseUrl.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                            lowercaseUrl.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                            lowercaseUrl.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
                            lowercaseUrl.endsWith(".m4a") || lowercaseUrl.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
                            lowercaseUrl.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
                            else -> null
                        }

                        val metadataBuilder = MediaMetadata.Builder()
                        var finalTitle = currentTitle ?: itemUri.lastPathSegment ?: trimmed
                        
                        if (finalTitle.contains(" - ")) {
                            val parts = finalTitle.split(" - ", limit = 2)
                            metadataBuilder.setArtist(parts[0].trim())
                            finalTitle = parts[1].trim()
                        }
                        
                        items.add(
                            MediaItem.Builder()
                                .setMediaId(itemUri.toString())
                                .setUri(itemUri)
                                .setMimeType(mimeType)
                                .setMediaMetadata(metadataBuilder.setTitle(finalTitle).build())
                                .build()
                        )
                    }
                    currentTitle = null // Reset for next item
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    fun parsePls(uri: Uri, basePath: String? = null): List<MediaItem> {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val finalBasePath = basePath ?: if (uri.scheme == "file") uri.path?.substringBeforeLast("/") else null
                return parsePlsFromStream(inputStream, finalBasePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    fun parsePlsFromStream(inputStream: java.io.InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val props = linkedMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("[")) continue
                
                // Handle UTF-8 BOM
                if (trimmed.startsWith("\uFEFF")) {
                    trimmed = trimmed.substring(1)
                }

                val eq = trimmed.indexOf("=")
                if (eq != -1) {
                    val key = trimmed.substring(0, eq).trim().lowercase()
                    val value = trimmed.substring(eq + 1).trim()
                    props[key] = value
                }
            }
            val count = props.remove("numberofentries")?.toIntOrNull() ?: 0
            for (i in 1..count) {
                val file = props.remove("file$i") ?: continue
                // Normalize backslashes
                val normalizedPath = file.replace("\\", "/")
                var itemUriString = normalizedPath
                
                if (basePath != null && !normalizedPath.contains("://") && !normalizedPath.startsWith("/")) {
                    if (basePath.contains("%2F") && !basePath.startsWith("file://")) {
                        val encodedPath = Uri.encode(normalizedPath).replace("/", "%2F")
                        itemUriString = if (basePath.endsWith("%2F")) basePath + encodedPath else "$basePath%2F$encodedPath"
                    } else {
                        if (basePath.endsWith("/")) itemUriString = basePath + normalizedPath else itemUriString = "$basePath/$normalizedPath"
                    }
                }

                val itemUri = try {
                    when {
                        itemUriString.startsWith("/") -> Uri.fromFile(java.io.File(itemUriString))
                        itemUriString.startsWith("file://") -> {
                            val path = itemUriString.substring(7)
                            Uri.fromFile(java.io.File(path))
                        }
                        itemUriString.startsWith("content://") ||
                        itemUriString.startsWith("http://") || itemUriString.startsWith("https://") ||
                        itemUriString.startsWith("smb://") -> Uri.parse(itemUriString)
                        else -> null
                    }
                } catch (e: Exception) { null }

                if (itemUri != null) {
                    val title = props.remove("title$i") ?: itemUri.lastPathSegment ?: itemUriString.substringAfterLast("/").substringBeforeLast(".")
                    val length = props.remove("length$i")
                    val lowercaseUrl = itemUri.toString().lowercase()
                    val mimeType = when {
                        lowercaseUrl.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                        lowercaseUrl.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                        lowercaseUrl.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
                        lowercaseUrl.endsWith(".m4a") || lowercaseUrl.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
                        lowercaseUrl.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
                        else -> null
                    }
                    val metadataBuilder = MediaMetadata.Builder()
                    var finalTitle = title
                    if (finalTitle.contains(" - ")) {
                        val parts = finalTitle.split(" - ", limit = 2)
                        metadataBuilder.setArtist(parts[0].trim())
                        finalTitle = parts[1].trim()
                    }
                    items.add(
                        MediaItem.Builder()
                            .setMediaId(itemUri.toString())
                            .setUri(itemUri)
                            .setMimeType(mimeType)
                            .setMediaMetadata(metadataBuilder.setTitle(finalTitle).build())
                            .build()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    fun pickFiles() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            intent.type = "audio/*"
            intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
            
            // Define MIME types explicitly to include playlists
            val mimeTypes = arrayOf(
                "audio/*", 
                "application/octet-stream", 
                "application/x-mpegurl", 
                "audio/mpegurl",
                "audio/x-mpegurl"
            )
            intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimeTypes)
            
            startActivityForResult(intent, 2001)
        } catch (e: Exception) {
            Toast.makeText(this, "File manager not found.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            data?.let {
                if (it.clipData != null) {
                    val count = it.clipData!!.itemCount
                    for (i in 0 until count) {
                        uris.add(it.clipData!!.getItemAt(i).uri)
                    }
                } else if (it.data != null) {
                    uris.add(it.data!!)
                }
            }
            if (uris.isNotEmpty()) {
                addUrisToPlaylist(uris)
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val fragment = supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment
        fragment?.refreshWithCurrentFocus()
    }

    override fun onDestroy() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
    
    fun getController(): MediaController? = mediaController
}
