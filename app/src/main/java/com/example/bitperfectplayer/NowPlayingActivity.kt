package com.example.bitperfectplayer

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class NowPlayingActivity : BaseActivity() {
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var animatedWaveform: AnimatedWaveformView
    
    private lateinit var textTitle: TextView
    private lateinit var textArtist: TextView
    private lateinit var textAlbum: TextView
    private lateinit var textPlaylistPos: TextView
    private lateinit var textBitrate: TextView
    private lateinit var textCurrentTime: TextView
    private lateinit var textTotalTime: TextView
    private lateinit var imgTrackIcon: ImageView
    private lateinit var seekBar: SeekBar
    private var isSeekBarTracking: Boolean = false
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        textTitle = findViewById(R.id.text_title)
        textArtist = findViewById(R.id.text_artist)
        textAlbum = findViewById(R.id.text_album)
        textPlaylistPos = findViewById(R.id.text_playlist_pos)
        textBitrate = findViewById(R.id.text_bitrate)
        textCurrentTime = findViewById(R.id.text_current_time)
        textTotalTime = findViewById(R.id.text_total_time)
        imgTrackIcon = findViewById(R.id.img_track_icon)
        animatedWaveform = findViewById(R.id.animated_waveform)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnRepeat = findViewById(R.id.btn_repeat)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    textCurrentTime.text = formatTime(progress.toLong())
                    // On Android TV, D-pad events only fire onProgressChanged (not onStopTrackingTouch),
                    // so we must seek here. For touch drags, onStopTrackingTouch handles the final seek.
                    if (!isSeekBarTracking) {
                        mediaController?.seekTo(progress.toLong())
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
                handler.removeCallbacksAndMessages(null)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaController?.seekTo(seekBar?.progress?.toLong() ?: 0L)
                isSeekBarTracking = false
                startProgressUpdate()
            }
        })

        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { mediaController?.seekToPrevious() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { mediaController?.seekToNext() }
        findViewById<ImageButton>(R.id.btn_playlist).setOnClickListener { showPlaylist() }
        
        btnPlayPause.setOnClickListener {
            mediaController?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        btnShuffle.setOnClickListener {
            mediaController?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                updateShuffleRepeatUI()
            }
        }

        btnRepeat.setOnClickListener {
            mediaController?.let {
                val nextMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                it.repeatMode = nextMode
                updateShuffleRepeatUI()
            }
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupController()
                resetScreensaverTimer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun parseM3uLocal(inputStream: java.io.InputStream, baseUri: android.net.Uri? = null): List<androidx.media3.common.MediaItem> {
        val items = mutableListOf<androidx.media3.common.MediaItem>()
        val basePath = baseUri?.toString()?.substringBeforeLast("/")
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
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
                    val commaIndex = trimmed.indexOf(",")
                    if (commaIndex != -1) {
                        currentTitle = trimmed.substring(commaIndex + 1)
                    }
                } else if (!trimmed.startsWith("#")) {
                    // Normalize backslashes
                    val normalizedPath = trimmed.replace("\\", "/")
                    var itemUriString = normalizedPath
                    
                    if (basePath != null && !normalizedPath.contains("://") && !normalizedPath.startsWith("/")) {
                        if (basePath.contains("%2F") && !basePath.startsWith("file://")) {
                            itemUriString = if (basePath.endsWith("%2F")) basePath + normalizedPath else "$basePath%2F$normalizedPath"
                        } else {
                            itemUriString = if (basePath.endsWith("/")) basePath + normalizedPath else "$basePath/$normalizedPath"
                        }
                    }

                    val itemUri = try {
                        when {
                            itemUriString.startsWith("/") -> android.net.Uri.fromFile(java.io.File(itemUriString))
                            itemUriString.startsWith("file://") -> {
                                val path = itemUriString.substring(7)
                                android.net.Uri.fromFile(java.io.File(path))
                            }
                            itemUriString.startsWith("content://") ||
                            itemUriString.startsWith("http://") || itemUriString.startsWith("https://") ||
                            itemUriString.startsWith("smb://") -> android.net.Uri.parse(itemUriString)
                            else -> null
                        }
                    } catch (e: Exception) { null }

                    if (itemUri != null) {
                        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
                        var finalTitle = currentTitle ?: itemUri.lastPathSegment ?: trimmed
                        
                        if (finalTitle.contains(" - ")) {
                            val parts = finalTitle.split(" - ", limit = 2)
                            metadataBuilder.setArtist(parts[0].trim())
                            finalTitle = parts[1].trim()
                        }

                        items.add(
                            androidx.media3.common.MediaItem.Builder()
                                .setMediaId(itemUri.toString())
                                .setUri(itemUri)
                                .setMediaMetadata(metadataBuilder.setTitle(finalTitle).build())
                                .build()
                        )
                    }
                    currentTitle = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return items
    }

    private fun parsePlsLocal(inputStream: java.io.InputStream, baseUri: android.net.Uri? = null): List<androidx.media3.common.MediaItem> {
        val items = mutableListOf<androidx.media3.common.MediaItem>()
        val basePath = baseUri?.toString()?.substringBeforeLast("/")
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
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
                        itemUriString = if (basePath.endsWith("%2F")) basePath + normalizedPath else "$basePath%2F$normalizedPath"
                    } else {
                        itemUriString = if (basePath.endsWith("/")) basePath + normalizedPath else "$basePath/$normalizedPath"
                    }
                }

                val itemUri = try {
                    when {
                        itemUriString.startsWith("/") -> android.net.Uri.fromFile(java.io.File(itemUriString))
                        itemUriString.startsWith("file://") -> {
                            val path = itemUriString.substring(7)
                            android.net.Uri.fromFile(java.io.File(path))
                        }
                        itemUriString.startsWith("content://") ||
                        itemUriString.startsWith("http://") || itemUriString.startsWith("https://") ||
                        itemUriString.startsWith("smb://") -> android.net.Uri.parse(itemUriString)
                        else -> null
                    }
                } catch (e: Exception) { null }
                if (itemUri != null) {
                    val title = props.remove("title$i") ?: itemUri.lastPathSegment ?: itemUriString.substringAfterLast("/").substringBeforeLast(".")
                    val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
                    var finalTitle = title
                    if (finalTitle.contains(" - ")) {
                        val parts = finalTitle.split(" - ", limit = 2)
                        metadataBuilder.setArtist(parts[0].trim())
                        finalTitle = parts[1].trim()
                    }
                    items.add(
                        androidx.media3.common.MediaItem.Builder()
                            .setMediaId(itemUri.toString())
                            .setUri(itemUri)
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

    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController ?: return
        val metadata = controller.mediaMetadata
        val mediaItem = controller.currentMediaItem
        val itemMetadata = mediaItem?.mediaMetadata
        
        var displayTitle = metadata.title?.toString() ?: itemMetadata?.title?.toString() ?: metadata.displayTitle?.toString() ?: "Bitperfect Player"
        var displayArtist = metadata.artist?.toString() ?: itemMetadata?.artist?.toString() ?: metadata.albumArtist?.toString() ?: ""

        val delimiters = arrayOf(" - ", " – ", " — ", " : ", " | ")
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            for (delim in delimiters) {
                if (displayTitle.contains(delim)) {
                    val parts = displayTitle.split(delim, limit = 2)
                    displayArtist = parts[0].trim()
                    displayTitle = parts[1].trim()
                    break
                }
            }
        }

        val station = metadata.station?.toString() ?: itemMetadata?.station?.toString()
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            displayArtist = station ?: ""
        }

        val builder = StringBuilder("Now Playing:\n")
        builder.append(displayTitle)
        
        if (displayArtist.isNotEmpty() && !displayArtist.equals("Unknown Artist", ignoreCase = true) && displayArtist != displayTitle) {
            builder.append("\n").append(displayArtist)
        }
        
        if (!station.isNullOrBlank() && station != displayArtist && station != displayTitle) {
            builder.append("\n(").append(station).append(")")
        }
        
        // Add format info to screensaver if available
        val currentTracks = controller.currentTracks
        var format: androidx.media3.common.Format? = null
        currentTracks.groups.forEach { group ->
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        format = group.getTrackFormat(i)
                        break
                    }
                }
            }
        }
        
        if (format != null) {
            val bitrateVal = format.bitrate
            val sampleRateVal = format.sampleRate
            val pcmEncoding = format.pcmEncoding
            
            val infoList = mutableListOf<String>()
            if (bitrateVal != androidx.media3.common.Format.NO_VALUE && bitrateVal > 0) infoList.add("${bitrateVal / 1000} kbps")
            if (sampleRateVal != androidx.media3.common.Format.NO_VALUE && sampleRateVal > 0) infoList.add("$sampleRateVal Hz")
            val bitDepthStr = when (pcmEncoding) {
                androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit"
                androidx.media3.common.C.ENCODING_PCM_24BIT -> "24-bit"
                androidx.media3.common.C.ENCODING_PCM_32BIT -> "32-bit"
                androidx.media3.common.C.ENCODING_PCM_FLOAT -> "Float"
                else -> ""
            }
            if (bitDepthStr.isNotEmpty()) infoList.add(bitDepthStr)
            
            if (infoList.isNotEmpty()) {
                builder.append("\n").append(infoList.joinToString(" | "))
            }
        }
        
        textView.text = builder.toString()
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
    }

    private fun setupController() {
        val controller = mediaController ?: return
        animatedWaveform.setPlaying(controller.isPlaying)
        val waveType = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("waveform_type", 0)
        animatedWaveform.setWaveformType(waveType)
        animatedWaveform.setColor(getThemeColor())
        updateControlButtonsTint()
        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateUI()
                refreshScreensaver()
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                updateUI()
                refreshScreensaver()
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateUI()
                refreshScreensaver()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateUI()
                if (playbackState == Player.STATE_READY) {
                    // Force another update after a short delay for streams to catch up with metadata
                    handler.postDelayed({ updateUI() }, 1000)
                    handler.postDelayed({ updateUI() }, 3000)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                animatedWaveform.setPlaying(isPlaying)
                val screensaverWaveform = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
                screensaverWaveform?.setPlaying(isPlaying)
                if (isPlaying) {
                    startProgressUpdate()
                    // Immediately update UI to catch metadata that might have arrived during start
                    updateUI()
                    handler.postDelayed({ updateUI() }, 500)
                }
                resetScreensaverTimer()
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                updateShuffleRepeatUI()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateShuffleRepeatUI()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                textBitrate.text = "Error: ${error.errorCodeName}"
                android.widget.Toast.makeText(this@NowPlayingActivity, "Playback error: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        updateUI()
        startProgressUpdate()
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

    private fun updateControlButtonsTint() {
        val themeColor = getThemeColor()
        val states = arrayOf(
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf()
        )
        val colors = intArrayOf(
            (themeColor and 0x00FFFFFF) or 0x66000000,
            (themeColor and 0x00FFFFFF) or 0x44000000,
            android.graphics.Color.TRANSPARENT
        )
        val tintList = android.content.res.ColorStateList(states, colors)
        
        val buttons = listOf(
            R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play_pause, 
            R.id.btn_next, R.id.btn_repeat, R.id.btn_playlist
        )
        
        buttons.forEach { id ->
            findViewById<ImageButton>(id)?.backgroundTintList = tintList
        }
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

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return
        
        // Handle Pure Black themes
        val colorIndex = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("color_scheme", 0)
        if (colorIndex >= 5) {
            window.decorView.setBackgroundColor(0xFF000000.toInt())
            findViewById<android.view.View>(android.R.id.content).parent?.let {
                (it as? android.view.View)?.setBackgroundColor(0xFF000000.toInt())
            }
            findViewById<android.widget.LinearLayout>(R.id.now_playing_root)?.setBackgroundColor(0xFF000000.toInt())
        } else {
            window.decorView.setBackgroundColor(0xFF121212.toInt())
            findViewById<android.widget.LinearLayout>(R.id.now_playing_root)?.setBackgroundColor(0xFF121212.toInt())
        }

        // Section 1: Gather Metadata
        // Use player metadata first as it contains dynamic stream updates
        val metadata = controller.mediaMetadata
        val mediaItem = controller.currentMediaItem
        val itemMetadata = mediaItem?.mediaMetadata

        val title = metadata.title?.toString() ?: itemMetadata?.title?.toString()
        val displayTitle = metadata.displayTitle?.toString() ?: itemMetadata?.displayTitle?.toString()
        val artist = metadata.artist?.toString() ?: itemMetadata?.artist?.toString()
        val albumArtist = metadata.albumArtist?.toString() ?: itemMetadata?.albumArtist?.toString()
        val station = metadata.station?.toString() ?: itemMetadata?.station?.toString()
        val subtitle = metadata.subtitle?.toString() ?: itemMetadata?.subtitle?.toString()
        val description = metadata.description?.toString() ?: itemMetadata?.description?.toString()

        // Section 2: Identify Artist and Track
        var displayArtist = artist ?: albumArtist ?: ""
        var displayTrack = title ?: displayTitle ?: "Unknown Title"

        // For streams, if artist is unknown, try to find it in subtitle or description
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            if (!subtitle.isNullOrBlank() && subtitle != displayTrack) {
                displayArtist = subtitle
            } else if (!description.isNullOrBlank() && description != displayTrack) {
                displayArtist = description
            }
        }

        // Section 3: Robust Splitting Logic (Handle "Artist - Song")
        val delimiters = arrayOf(" - ", " – ", " — ", " : ", " | ")
        var splitPerformed = false
        for (delim in delimiters) {
            if (displayTrack.contains(delim)) {
                val parts = displayTrack.split(delim, limit = 2)
                displayArtist = parts[0].trim()
                displayTrack = parts[1].trim()
                splitPerformed = true
                break
            }
        }

        if (!splitPerformed && displayArtist.contains(" - ")) {
            val parts = displayArtist.split(" - ", limit = 2)
            displayArtist = parts[0].trim()
            displayTrack = parts[1].trim()
        }

        // Section 4: Final Fallbacks
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            displayArtist = station ?: displayTitle ?: "Unknown Artist"
        }
        
        if (displayArtist == displayTrack && !station.isNullOrBlank()) {
            displayArtist = station
        }

        textTitle.text = displayTrack
        textArtist.text = displayArtist

        // Section 5: Station / Album Display
        // Priority for streams: Station Name
        val finalAlbum = when {
            !station.isNullOrBlank() && station != displayTrack && station != displayArtist -> station
            !displayTitle.isNullOrBlank() && displayTitle != displayTrack && displayTitle != displayArtist -> displayTitle
            else -> metadata.albumTitle?.toString() ?: itemMetadata?.albumTitle?.toString()
        }
        
        if (!finalAlbum.isNullOrEmpty()) {
            textAlbum.text = finalAlbum
            textAlbum.visibility = android.view.View.VISIBLE
        } else {
            textAlbum.visibility = android.view.View.GONE
        }
        
        // Section 6: UI Elements State
        val current = controller.currentMediaItemIndex + 1
        val total = controller.mediaItemCount
        textPlaylistPos.text = "$current / $total"

        val uri = mediaItem?.mediaId ?: ""
        val iconRes = when {
            uri.startsWith("smb://") -> R.drawable.ic_network
            uri.startsWith("content://") -> R.drawable.ic_folder
            else -> R.drawable.ic_audio
        }
        imgTrackIcon.setImageResource(iconRes)
        if (iconRes == R.drawable.ic_audio) {
            imgTrackIcon.setColorFilter(getThemeColor())
        } else {
            imgTrackIcon.clearColorFilter()
        }

        btnPlayPause.setImageResource(
            if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        
        updateShuffleRepeatUI()

        // Section 7: Technical Info (Bitrate/Sample Rate)
        val trackGroups = controller.currentTracks
        var format: androidx.media3.common.Format? = null
        
        for (group in trackGroups.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        format = group.getTrackFormat(i)
                        break
                    }
                }
            }
        }

        val infoList = mutableListOf<String>()
        val icyName = metadata.station?.toString() ?: itemMetadata?.station?.toString() ?: 
                      metadata.albumArtist?.toString() ?: itemMetadata?.albumArtist?.toString()

        if (icyName != null && icyName != artist && icyName != displayArtist) {
            infoList.add(icyName)
        }

        if (format != null) {
            val bitrateVal = format.bitrate
            val sampleRateVal = format.sampleRate
            val pcmEncoding = format.pcmEncoding
            
            val bitrateStr = if (bitrateVal != androidx.media3.common.Format.NO_VALUE && bitrateVal > 0) "${bitrateVal / 1000} kbps" else ""
            val sampleRateStr = if (sampleRateVal != androidx.media3.common.Format.NO_VALUE && sampleRateVal > 0) "${sampleRateVal} Hz" else ""
            val bitDepthStr = when (pcmEncoding) {
                androidx.media3.common.C.ENCODING_PCM_16BIT -> "16-bit"
                androidx.media3.common.C.ENCODING_PCM_24BIT -> "24-bit"
                androidx.media3.common.C.ENCODING_PCM_32BIT -> "32-bit"
                androidx.media3.common.C.ENCODING_PCM_FLOAT -> "Float"
                else -> ""
            }
            
            if (bitrateStr.isNotEmpty()) infoList.add(bitrateStr)
            if (sampleRateStr.isNotEmpty()) infoList.add(sampleRateStr)
            if (bitDepthStr.isNotEmpty()) infoList.add(bitDepthStr)
        }

        if (infoList.isNotEmpty()) {
            textBitrate.text = infoList.joinToString(" | ")
            textBitrate.setTextColor(android.graphics.Color.WHITE)
        } else {
            textBitrate.setTextColor(0xFF888888.toInt())
            when (controller.playbackState) {
                Player.STATE_BUFFERING -> textBitrate.text = "Buffering..."
                Player.STATE_IDLE -> textBitrate.text = "Idle"
                Player.STATE_ENDED -> textBitrate.text = "Playback Ended"
                else -> textBitrate.text = "Detecting Format..."
            }
        }
    }

    private fun updateShuffleRepeatUI() {
        val controller = mediaController ?: return
        
        btnShuffle.alpha = if (controller.shuffleModeEnabled) 1.0f else 0.5f
        
        when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 0.5f
            }
            Player.REPEAT_MODE_ALL -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 1.0f
            }
            Player.REPEAT_MODE_ONE -> {
                // You might want a different icon for Repeat One, but we'll use same with indicator or just different alpha/color
                // For now, let's just change alpha or use a "1" overlay if we had one.
                btnRepeat.setImageResource(android.R.drawable.ic_menu_rotate) // Visual difference
                btnRepeat.alpha = 1.0f
            }
        }
    }

    private fun showPlaylist() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        
        val items = mutableListOf<String>()
        for (i in 0 until count) {
            val item = controller.getMediaItemAt(i)
            items.add("${i + 1}. ${item.mediaMetadata.title ?: "Unknown"}")
        }

        // Custom adapter for playlist with icons
        val playlistAdapter = object : android.widget.ArrayAdapter<String>(this, R.layout.list_item_browse, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
                val text = view.findViewById<TextView>(R.id.item_text)
                val icon = view.findViewById<ImageView>(R.id.item_icon)
                
                text.text = getItem(position)
                val item = controller.getMediaItemAt(position)
                val uri = item.mediaId
                
                val iconRes = when {
                    uri.startsWith("smb://") -> R.drawable.ic_network
                    uri.startsWith("content://") -> R.drawable.ic_folder
                    else -> R.drawable.ic_audio
                }
                icon.setImageResource(iconRes)
                return view
            }
        }

        val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Current Playlist")
        
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val listView = android.widget.ListView(this).apply {
            adapter = playlistAdapter
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
        }
        dialogView.addView(listView)

        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = builder.setView(dialogView).create()

        fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            this.text = text
            setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        buttonRow.addView(createBtn("Clear") { 
            controller.clearMediaItems()
            android.widget.Toast.makeText(this@NowPlayingActivity, "Playlist cleared", android.widget.Toast.LENGTH_SHORT).show()
            updateUI()
        })
        buttonRow.addView(createBtn("Add Files") {
            checkAndBrowseInternalStorage(isSelectionMode = false, isDirectoryMode = false)
            dialog.dismiss()
        })
        buttonRow.addView(createBtn("Add Directory") {
            checkAndBrowseInternalStorage(isSelectionMode = true, isDirectoryMode = true)
            dialog.dismiss()
        })

        dialogView.addView(buttonRow)

        listView.setOnItemClickListener { _, _, which, _ ->
            controller.seekTo(which, 0)
            controller.play()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun checkAndBrowseInternalStorage(isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
            } else {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("All Files Access Required")
                    .setMessage("To browse storage directly on Android TV 11+, please grant 'All Files Access' in the system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
        }
    }

    private data class BrowseItem(val name: String, val path: String, val icon: Int, val isDirectory: Boolean)

    private class BrowseAdapter(context: android.content.Context, items: List<BrowseItem>) : 
        android.widget.ArrayAdapter<BrowseItem>(context, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<TextView>(R.id.item_text).text = item.name
            view.findViewById<ImageView>(R.id.item_icon).setImageResource(item.icon)
            return view
        }
    }

    private fun browseFileStorage(path: String, isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        val currentDir = java.io.File(path)
        val loadingToast = android.widget.Toast.makeText(this, "Reading folder...", android.widget.Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val files = currentDir.listFiles() ?: emptyArray()
                val items = mutableListOf<BrowseItem>()

                for (file in files) {
                    if (file.name.startsWith(".")) continue
                    
                    if (file.isDirectory) {
                        items.add(BrowseItem(file.name, file.absolutePath, R.drawable.ic_folder, true))
                    } else if (!isDirectoryMode && isPlayable(file.name)) {
                        val icon = if (file.name.lowercase().endsWith(".m3u") || file.name.lowercase().endsWith(".m3u8") || file.name.lowercase().endsWith(".pls")) R.drawable.ic_playlist else R.drawable.ic_audio
                        items.add(BrowseItem(file.name, file.absolutePath, icon, false))
                    }
                }

                val sortedItems = items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                runOnUiThread {
                    loadingToast.cancel()
                    
                    val title = if (isDirectoryMode) "Select Directory" else currentDir.name.ifEmpty { "Storage" }
                    
                    val adapter = BrowseAdapter(this, sortedItems)
                    val builder = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title)

                    val dialogView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(16, 16, 16, 16)
                    }

                    val listView = android.widget.ListView(this).apply {
                        this.adapter = adapter
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            0, 1.0f
                        )
                    }
                    dialogView.addView(listView)

                    val buttonRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val dialog = builder.setView(dialogView).create()

                    fun createBtn(text: String, onClick: () -> Unit) = android.widget.Button(this).apply {
                        this.text = text
                        setOnClickListener {
                            onClick()
                            dialog.dismiss()
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        isFocusable = true
                    }

                    buttonRow.addView(createBtn("Back") { 
                        dialog.dismiss()
                        val parent = currentDir.parent
                        if (parent != null && parent != android.os.Environment.getExternalStorageDirectory().parent) {
                            browseFileStorage(parent, isSelectionMode, isDirectoryMode)
                        }
                    })
                    if (isDirectoryMode) {
                        buttonRow.addView(createBtn("Add Folder") {
                            addFilesToPlaylist(currentDir, false)
                        })
                    } else {
                        buttonRow.addView(createBtn("Add All") {
                            addFilesToPlaylist(currentDir, false)
                        })
                        buttonRow.addView(createBtn("Replace All") {
                            addFilesToPlaylist(currentDir, true)
                        })
                    }

                    dialogView.addView(buttonRow)

                    listView.setOnItemClickListener { _, _, which, _ ->
                        val item = sortedItems[which]
                        if (item.isDirectory) {
                            dialog.dismiss()
                            browseFileStorage(item.path, isSelectionMode, isDirectoryMode)
                        } else {
                            handleFileSelection(item.path, item.name)
                            dialog.dismiss()
                        }
                    }

                    dialog.show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    loadingToast.cancel()
                    android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleFileSelection(path: String, name: String) {
        val file = java.io.File(path)
        val items = listOf(
            com.example.bitperfectplayer.MainFragment.DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music),
            com.example.bitperfectplayer.MainFragment.DialogOptionItem("Replace current playlist", R.drawable.ic_playlist)
        )
        val adapter = com.example.bitperfectplayer.MainFragment.DialogOptionAdapter(this, items)
        
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(adapter) { _, which ->
                addFilesToPlaylist(file, which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFilesToPlaylist(root: java.io.File, replace: Boolean) {
        val controller = mediaController ?: return
        val loadingToast = android.widget.Toast.makeText(this, "Processing files...", android.widget.Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            val itemsToAdd = mutableListOf<androidx.media3.common.MediaItem>()
            val isPlaylistFile = !root.isDirectory && isPlayable(root.name) &&
                (root.name.lowercase().endsWith(".m3u") || root.name.lowercase().endsWith(".m3u8") || root.name.lowercase().endsWith(".pls"))
            
            fun scanRecursive(file: java.io.File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { scanRecursive(it) }
                } else if (isPlayable(file.name)) {
                    val lower = file.name.lowercase()
                    if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                val parsed = parseM3uLocal(stream, android.net.Uri.fromFile(file))
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    val uri = android.net.Uri.fromFile(file).toString()
                                    itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                                        .setMediaId(uri)
                                        .setUri(android.net.Uri.fromFile(file))
                                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(file.name.substringBeforeLast(".")).build())
                                        .build())
                                }
                            }
                        } catch (e: Exception) {
                            val uri = android.net.Uri.fromFile(file).toString()
                            itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                                .setMediaId(uri)
                                .setUri(android.net.Uri.fromFile(file))
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(file.name.substringBeforeLast(".")).build())
                                .build())
                        }
                    } else if (lower.endsWith(".pls")) {
                        try {
                            java.io.FileInputStream(file).use { stream ->
                                val parsed = parsePlsLocal(stream, android.net.Uri.fromFile(file))
                                if (parsed.isNotEmpty()) {
                                    itemsToAdd.addAll(parsed)
                                } else {
                                    val uri = android.net.Uri.fromFile(file).toString()
                                    itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                                        .setMediaId(uri)
                                        .setUri(android.net.Uri.fromFile(file))
                                        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(file.name.substringBeforeLast(".")).build())
                                        .build())
                                }
                            }
                        } catch (e: Exception) {
                            val uri = android.net.Uri.fromFile(file).toString()
                            itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                                .setMediaId(uri)
                                .setUri(android.net.Uri.fromFile(file))
                                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(file.name.substringBeforeLast(".")).build())
                                .build())
                        }
                    } else {
                        val uri = android.net.Uri.fromFile(file).toString()
                        val lower = uri.lowercase()
                        val mimeType = when {
                            lower.endsWith(".flac") -> androidx.media3.common.MimeTypes.AUDIO_FLAC
                            lower.endsWith(".mp3") -> androidx.media3.common.MimeTypes.AUDIO_MPEG
                            lower.endsWith(".wav") -> androidx.media3.common.MimeTypes.AUDIO_WAV
                            lower.endsWith(".m4a") || lower.endsWith(".aac") -> androidx.media3.common.MimeTypes.AUDIO_AAC
                            lower.endsWith(".ogg") -> androidx.media3.common.MimeTypes.AUDIO_OGG
                            else -> null
                        }
                        val title = file.name.substringBeforeLast(".")
                        itemsToAdd.add(androidx.media3.common.MediaItem.Builder()
                            .setMediaId(uri)
                            .setUri(android.net.Uri.fromFile(file))
                            .setMimeType(mimeType)
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
                            .build())
                    }
                }
            }

            scanRecursive(root)

            runOnUiThread {
                loadingToast.cancel()
                if (itemsToAdd.isNotEmpty()) {
                    val sortedItems = if (isPlaylistFile) {
                        itemsToAdd
                    } else {
                        itemsToAdd.sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }
                    }
                    
                    if (replace) {
                        controller.setMediaItems(sortedItems)
                    } else {
                        controller.addMediaItems(sortedItems)
                    }
                    controller.prepare()
                    controller.play()
                    android.widget.Toast.makeText(this, "Added ${sortedItems.size} items", android.widget.Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    android.widget.Toast.makeText(this, "No music found.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun isPlayable(filename: String): Boolean {
        val extensions = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".m3u", ".m3u8", ".pls")
        return extensions.any { filename.lowercase().endsWith(it) }
    }

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val controller = mediaController ?: return
                val currentPos = controller.currentPosition
                val duration = controller.duration
                
                textCurrentTime.text = formatTime(currentPos)
                
                if (duration > 0 && duration != Long.MAX_VALUE && duration != androidx.media3.common.C.TIME_UNSET) {
                    seekBar.max = duration.toInt()
                    seekBar.progress = currentPos.toInt()
                    textTotalTime.text = formatTime(duration)
                } else {
                    seekBar.max = 100
                    seekBar.progress = 0
                    textTotalTime.text = "∞" // Infinity symbol for streams
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
}
