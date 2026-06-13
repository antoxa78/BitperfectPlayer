package com.example.bitperfectplayer

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class NowPlayingActivity : BaseActivity() {

    companion object {
        private const val PREFS_APP          = "AppSettings"
        private const val KEY_COLOR_SCHEME   = "color_scheme"
        private const val KEY_WAVEFORM_TYPE  = "waveform_type"
        private const val PURE_BLACK_INDEX   = 5       // colour schemes >= this index use pure-black background
        private const val BG_DARK            = 0xFF121212.toInt()
        private const val BG_PURE_BLACK      = 0xFF000000.toInt()
        private const val PROGRESS_TICK_MS   = 1_000L
        private const val METADATA_DELAY_1   = 1_000L
        private const val METADATA_DELAY_2   = 3_000L

        val THEME_COLORS = intArrayOf(
            0xFF00E676.toInt(), // Neon Green
            0xFF2979FF.toInt(), // Electric Blue
            0xFFFFC400.toInt(), // Amber Gold
            0xFF7C4DFF.toInt(), // Deep Purple
            0xFFFF4081.toInt(), // Hot Pink
            0xFF888888.toInt(), // Grey
            0xFFFF5252.toInt(), // Red
            0xFF1B5E20.toInt(), // Dark Green
            0xFFFFFFFF.toInt(), // White
        )
    }

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
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private var isSeekBarTracking = false

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        bindViews()
        setupSeekBar()
        setupButtons()

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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // View binding
    // ---------------------------------------------------------------------------

    private fun bindViews() {
        textTitle       = findViewById(R.id.text_title)
        textArtist      = findViewById(R.id.text_artist)
        textAlbum       = findViewById(R.id.text_album)
        textPlaylistPos = findViewById(R.id.text_playlist_pos)
        textBitrate     = findViewById(R.id.text_bitrate)
        textCurrentTime = findViewById(R.id.text_current_time)
        textTotalTime   = findViewById(R.id.text_total_time)
        imgTrackIcon    = findViewById(R.id.img_track_icon)
        animatedWaveform = findViewById(R.id.animated_waveform)
        seekBar         = findViewById(R.id.seek_bar)
        btnPlayPause    = findViewById(R.id.btn_play_pause)
        btnShuffle      = findViewById(R.id.btn_shuffle)
        btnRepeat       = findViewById(R.id.btn_repeat)
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    textCurrentTime.text = formatTime(progress.toLong())
                    // On Android TV, D-pad events fire only onProgressChanged (not onStopTrackingTouch),
                    // so we seek here. Touch drags are handled by onStopTrackingTouch for the final seek.
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
    }

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { mediaController?.seekToPrevious() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { mediaController?.seekToNext() }
        findViewById<ImageButton>(R.id.btn_playlist).setOnClickListener { showPlaylist() }

        btnPlayPause.setOnClickListener {
            mediaController?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        btnShuffle.setOnClickListener {
            mediaController?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                updateShuffleRepeatUI()
            }
        }
        btnRepeat.setOnClickListener {
            mediaController?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else                  -> Player.REPEAT_MODE_OFF
                }
                updateShuffleRepeatUI()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Controller / playback
    // ---------------------------------------------------------------------------

    private fun setupController() {
        val controller = mediaController ?: return
        animatedWaveform.setPlaying(controller.isPlaying)
        animatedWaveform.setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
        animatedWaveform.setColor(getThemeColor())
        updateControlButtonsTint()

        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateUI(); refreshScreensaver()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateUI(); refreshScreensaver()
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateUI(); refreshScreensaver()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateUI()
                if (playbackState == Player.STATE_READY) {
                    // Delayed re-checks catch metadata that arrives slightly after STATE_READY
                    handler.postDelayed({ updateUI() }, METADATA_DELAY_1)
                    handler.postDelayed({ updateUI() }, METADATA_DELAY_2)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                animatedWaveform.setPlaying(isPlaying)
                window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")?.setPlaying(isPlaying)
                if (isPlaying) {
                    startProgressUpdate()
                    updateUI()
                    handler.postDelayed({ updateUI() }, 500)
                }
                resetScreensaverTimer()
            }
            override fun onRepeatModeChanged(repeatMode: Int)             { updateShuffleRepeatUI() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { updateShuffleRepeatUI() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                textBitrate.text = "Error: ${error.errorCodeName}"
                Toast.makeText(this@NowPlayingActivity, "Playback error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })

        updateUI()
        startProgressUpdate()
    }

    // ---------------------------------------------------------------------------
    // Screensaver
    // ---------------------------------------------------------------------------

    private fun refreshScreensaver() {
        if (!isScreensaverActive) return
        window.decorView.findViewWithTag<TextView>("screensaver_text")?.let { updateScreensaverText(it) }
        val waveform = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
        waveform?.setPlaying(mediaController?.isPlaying == true)
        waveform?.setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
        waveform?.setColor(getThemeColor())
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
            setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
            setColor(getThemeColor())
            container.addView(this)
        }
    }

    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController ?: return
        val metadata   = controller.mediaMetadata
        val itemMeta   = controller.currentMediaItem?.mediaMetadata

        var displayTitle  = metadata.title?.toString() ?: itemMeta?.title?.toString()
            ?: metadata.displayTitle?.toString() ?: "Bitperfect Player"
        var displayArtist = metadata.artist?.toString() ?: itemMeta?.artist?.toString()
            ?: metadata.albumArtist?.toString() ?: ""

        val delimiters = arrayOf(" - ", " – ", " — ", " : ", " | ")
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            for (delim in delimiters) {
                if (displayTitle.contains(delim)) {
                    val parts = displayTitle.split(delim, limit = 2)
                    displayArtist = parts[0].trim()
                    displayTitle  = parts[1].trim()
                    break
                }
            }
        }

        val station = metadata.station?.toString() ?: itemMeta?.station?.toString()
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            displayArtist = station ?: ""
        }

        val sb = StringBuilder("Now Playing:\n").append(displayTitle)
        if (displayArtist.isNotEmpty() && !displayArtist.equals("Unknown Artist", ignoreCase = true) && displayArtist != displayTitle) {
            sb.append('\n').append(displayArtist)
        }
        if (!station.isNullOrBlank() && station != displayArtist && station != displayTitle) {
            sb.append("\n(").append(station).append(')')
        }

        // Technical info
        controller.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        buildFormatInfo(group.getTrackFormat(i))?.let { sb.append('\n').append(it) }
                        break
                    }
                }
            }
        }

        textView.text      = sb.toString()
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
    }

    // ---------------------------------------------------------------------------
    // UI updates
    // ---------------------------------------------------------------------------

    private fun updateControlButtonsTint() {
        val themeColor = getThemeColor()
        val tintList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(
                (themeColor and 0x00FFFFFF) or 0x66000000,
                (themeColor and 0x00FFFFFF) or 0x44000000,
                Color.TRANSPARENT
            )
        )
        listOf(R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play_pause, R.id.btn_next, R.id.btn_repeat, R.id.btn_playlist)
            .forEach { id -> findViewById<ImageButton>(id)?.backgroundTintList = tintList }
    }

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return

        // --- Background colour (Pure Black vs Dark Grey) ---
        val colorIndex = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
        val bgColor = if (colorIndex >= PURE_BLACK_INDEX) BG_PURE_BLACK else BG_DARK
        window.decorView.setBackgroundColor(bgColor)
        findViewById<android.widget.LinearLayout>(R.id.now_playing_root)?.setBackgroundColor(bgColor)

        // --- Gather metadata ---
        val metadata  = controller.mediaMetadata
        val mediaItem = controller.currentMediaItem
        val itemMeta  = mediaItem?.mediaMetadata

        val title        = metadata.title?.toString()       ?: itemMeta?.title?.toString()
        val displayTitle = metadata.displayTitle?.toString() ?: itemMeta?.displayTitle?.toString()
        val artist       = metadata.artist?.toString()      ?: itemMeta?.artist?.toString()
        val albumArtist  = metadata.albumArtist?.toString() ?: itemMeta?.albumArtist?.toString()
        val station      = metadata.station?.toString()     ?: itemMeta?.station?.toString()
        val subtitle     = metadata.subtitle?.toString()    ?: itemMeta?.subtitle?.toString()
        val description  = metadata.description?.toString() ?: itemMeta?.description?.toString()

        // --- Resolve artist / track ---
        var displayArtist = artist ?: albumArtist ?: ""
        var displayTrack  = title  ?: displayTitle ?: "Unknown Title"

        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            displayArtist = when {
                !subtitle.isNullOrBlank()     && subtitle     != displayTrack -> subtitle
                !description.isNullOrBlank()  && description  != displayTrack -> description
                else -> displayArtist
            }
        }

        // Try to split "Artist - Song" from the track field
        val delimiters = arrayOf(" - ", " – ", " — ", " : ", " | ")
        var splitPerformed = false
        for (delim in delimiters) {
            if (displayTrack.contains(delim)) {
                val parts = displayTrack.split(delim, limit = 2)
                displayArtist = parts[0].trim()
                displayTrack  = parts[1].trim()
                splitPerformed = true
                break
            }
        }
        if (!splitPerformed && displayArtist.contains(" - ")) {
            val parts = displayArtist.split(" - ", limit = 2)
            displayArtist = parts[0].trim()
            displayTrack  = parts[1].trim()
        }

        // Final fallbacks
        if (displayArtist.isEmpty() || displayArtist.equals("Unknown Artist", ignoreCase = true)) {
            displayArtist = station ?: displayTitle ?: "Unknown Artist"
        }
        if (displayArtist == displayTrack && !station.isNullOrBlank()) {
            displayArtist = station
        }

        textTitle.text  = displayTrack
        textArtist.text = displayArtist

        // --- Album / station label ---
        val finalAlbum = when {
            !station.isNullOrBlank()      && station      != displayTrack && station      != displayArtist -> station
            !displayTitle.isNullOrBlank() && displayTitle != displayTrack && displayTitle != displayArtist -> displayTitle
            else -> metadata.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString()
        }
        if (!finalAlbum.isNullOrEmpty()) {
            textAlbum.text       = finalAlbum
            textAlbum.visibility = android.view.View.VISIBLE
        } else {
            textAlbum.visibility = android.view.View.GONE
        }

        // --- Playlist position ---
        textPlaylistPos.text = "${controller.currentMediaItemIndex + 1} / ${controller.mediaItemCount}"

        // --- Track source icon ---
        val uri = mediaItem?.mediaId ?: ""
        val iconRes = when {
            uri.startsWith("smb://")     -> R.drawable.ic_network
            uri.startsWith("content://") -> R.drawable.ic_folder
            else                         -> R.drawable.ic_audio
        }
        imgTrackIcon.setImageResource(iconRes)
        if (iconRes == R.drawable.ic_audio) imgTrackIcon.setColorFilter(getThemeColor())
        else imgTrackIcon.clearColorFilter()

        btnPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateShuffleRepeatUI()

        // --- Technical format info ---
        var format: Format? = null
        for (group in controller.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) { format = group.getTrackFormat(i); break }
                }
                if (format != null) break
            }
        }

        val infoList = mutableListOf<String>()

        // Station name (ICY metadata)
        val icyName = station ?: albumArtist
        if (!icyName.isNullOrBlank() && icyName != artist && icyName != displayArtist) {
            infoList.add(icyName)
        }

        format?.let { infoList.addAll(formatInfoParts(it)) }

        if (infoList.isNotEmpty()) {
            textBitrate.text = infoList.joinToString(" | ")
            textBitrate.setTextColor(Color.WHITE)
        } else {
            textBitrate.setTextColor(0xFF888888.toInt())
            textBitrate.text = when (controller.playbackState) {
                Player.STATE_BUFFERING -> "Buffering…"
                Player.STATE_IDLE      -> "Idle"
                Player.STATE_ENDED     -> "Playback ended"
                else                   -> "Detecting format…"
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
                btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnRepeat.alpha = 1.0f
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Progress bar
    // ---------------------------------------------------------------------------

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val controller = mediaController ?: return
                val currentPos = controller.currentPosition
                val duration   = controller.duration

                textCurrentTime.text = formatTime(currentPos)

                if (duration > 0 && duration != Long.MAX_VALUE && duration != C.TIME_UNSET) {
                    seekBar.max      = duration.toInt()
                    seekBar.progress = currentPos.toInt()
                    textTotalTime.text = formatTime(duration)
                } else {
                    seekBar.max      = 100
                    seekBar.progress = 0
                    textTotalTime.text = "∞"
                }
                handler.postDelayed(this, PROGRESS_TICK_MS)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    // ---------------------------------------------------------------------------
    // Format helpers
    // ---------------------------------------------------------------------------

    private fun formatInfoParts(format: Format): List<String> {
        val parts = mutableListOf<String>()
        if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            parts.add("${format.bitrate / 1000} kbps")
        }
        if (format.sampleRate != Format.NO_VALUE && format.sampleRate > 0) {
            parts.add("${format.sampleRate} Hz")
        }
        val bitDepth = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> "16-bit"
            C.ENCODING_PCM_24BIT -> "24-bit"
            C.ENCODING_PCM_32BIT -> "32-bit"
            C.ENCODING_PCM_FLOAT -> "Float"
            else                 -> ""
        }
        if (bitDepth.isNotEmpty()) parts.add(bitDepth)
        return parts
    }

    private fun buildFormatInfo(format: Format): String? {
        val parts = formatInfoParts(format)
        return if (parts.isNotEmpty()) parts.joinToString(" | ") else null
    }

    // ---------------------------------------------------------------------------
    // Playlist dialog
    // ---------------------------------------------------------------------------

    private fun showPlaylist() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount

        val items = List(count) { i ->
            val item = controller.getMediaItemAt(i)
            "${i + 1}. ${item.mediaMetadata.title ?: "Unknown"}"
        }

        val playlistAdapter = object : android.widget.ArrayAdapter<String>(this, R.layout.list_item_browse, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
                view.findViewById<TextView>(R.id.item_text).text = getItem(position)
                val item    = controller.getMediaItemAt(position)
                val iconRes = when {
                    item.mediaId.startsWith("smb://")     -> R.drawable.ic_network
                    item.mediaId.startsWith("content://") -> R.drawable.ic_folder
                    else                                  -> R.drawable.ic_audio
                }
                view.findViewById<ImageView>(R.id.item_icon).setImageResource(iconRes)
                return view
            }
        }

        val builder    = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val listView = android.widget.ListView(this).apply {
            adapter = playlistAdapter
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
            )
        }
        dialogView.addView(listView)

        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val dialog = builder.setView(dialogView).create()

        fun btn(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
            text = label
            setOnClickListener { onClick(); dialog.dismiss() }
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            isFocusable = true
        }

        buttonRow.addView(btn("Clear") {
            controller.clearMediaItems()
            Toast.makeText(this@NowPlayingActivity, "Playlist cleared", Toast.LENGTH_SHORT).show()
            updateUI()
        })
        buttonRow.addView(btn("Add Files")     { checkAndBrowseInternalStorage(isSelectionMode = false, isDirectoryMode = false) })
        buttonRow.addView(btn("Add Directory") { checkAndBrowseInternalStorage(isSelectionMode = true,  isDirectoryMode = true)  })

        dialogView.addView(buttonRow)

        listView.setOnItemClickListener { _, _, which, _ ->
            controller.seekTo(which, 0)
            controller.play()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---------------------------------------------------------------------------
    // File / directory browsing (playlist management from Now Playing)
    // ---------------------------------------------------------------------------

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
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).also {
                                it.data = Uri.parse("package:$packageName")
                            })
                        } catch (e: Exception) {
                            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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

    private inner class BrowseAdapter(items: List<BrowseItem>) :
        android.widget.ArrayAdapter<BrowseItem>(this, R.layout.list_item_browse, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, parent, false)
            val item = getItem(position)!!
            view.findViewById<TextView>(R.id.item_text).text = item.name
            view.findViewById<ImageView>(R.id.item_icon).setImageResource(item.icon)
            return view
        }
    }

    private fun browseFileStorage(path: String, isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        val currentDir  = java.io.File(path)
        val loadingToast = Toast.makeText(this, "Reading folder…", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            try {
                val sortedItems = (currentDir.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .mapNotNull { file ->
                        when {
                            file.isDirectory -> BrowseItem(file.name, file.absolutePath, R.drawable.ic_folder, true)
                            !isDirectoryMode && isPlayable(file.name) -> {
                                val icon = if (file.name.lowercase().run { endsWith(".m3u") || endsWith(".m3u8") || endsWith(".pls") })
                                    R.drawable.ic_playlist else R.drawable.ic_audio
                                BrowseItem(file.name, file.absolutePath, icon, false)
                            }
                            else -> null
                        }
                    }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

                runOnUiThread {
                    loadingToast.cancel()
                    val title = if (isDirectoryMode) "Select Directory" else currentDir.name.ifEmpty { "Storage" }

                    val dialogView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(16, 16, 16, 16)
                    }
                    val listView = android.widget.ListView(this).apply {
                        adapter = BrowseAdapter(sortedItems)
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
                        )
                    }
                    dialogView.addView(listView)

                    val buttonRow = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity     = android.view.Gravity.CENTER
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }

                    val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title).setView(dialogView).create()

                    fun btn(label: String, onClick: () -> Unit) = android.widget.Button(this).apply {
                        text = label
                        setOnClickListener { onClick(); dialog.dismiss() }
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                        isFocusable = true
                    }

                    buttonRow.addView(btn("Back") {
                        val parent = currentDir.parentFile
                        val storageRoot = android.os.Environment.getExternalStorageDirectory().parent
                        if (parent != null && parent.absolutePath != storageRoot) {
                            browseFileStorage(parent.absolutePath, isSelectionMode, isDirectoryMode)
                        }
                    })
                    if (isDirectoryMode) {
                        buttonRow.addView(btn("Add Folder") { addFilesToPlaylist(currentDir, false) })
                    } else {
                        buttonRow.addView(btn("Add All")     { addFilesToPlaylist(currentDir, false) })
                        buttonRow.addView(btn("Replace All") { addFilesToPlaylist(currentDir, true)  })
                    }
                    dialogView.addView(buttonRow)

                    listView.setOnItemClickListener { _, _, which, _ ->
                        val item = sortedItems[which]
                        dialog.dismiss()
                        if (item.isDirectory) browseFileStorage(item.path, isSelectionMode, isDirectoryMode)
                        else handleFileSelection(item.path, item.name)
                    }
                    dialog.show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    loadingToast.cancel()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleFileSelection(path: String, name: String) {
        val items = listOf(
            MainFragment.DialogOptionItem("Add to current playlist",     R.drawable.ic_queue_music),
            MainFragment.DialogOptionItem("Replace current playlist",    R.drawable.ic_playlist)
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(MainFragment.DialogOptionAdapter(this, items)) { _, which ->
                addFilesToPlaylist(java.io.File(path), which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFilesToPlaylist(root: java.io.File, replace: Boolean) {
        val controller   = mediaController ?: return
        val loadingToast = Toast.makeText(this, "Processing files…", Toast.LENGTH_SHORT)
        loadingToast.show()

        Thread {
            val itemsToAdd = mutableListOf<MediaItem>()
            val isPlaylistFile = !root.isDirectory && isPlayable(root.name) &&
                root.name.lowercase().run { endsWith(".m3u") || endsWith(".m3u8") || endsWith(".pls") }

            fun scanRecursive(file: java.io.File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { scanRecursive(it) }
                    return
                }
                if (!isPlayable(file.name)) return
                val lower = file.name.lowercase()
                val fileUri = android.net.Uri.fromFile(file)
                when {
                    lower.endsWith(".m3u") || lower.endsWith(".m3u8") -> {
                        val parsed = try {
                            java.io.FileInputStream(file).use { parseM3uLocal(it, fileUri) }
                        } catch (e: Exception) { emptyList() }
                        if (parsed.isNotEmpty()) itemsToAdd.addAll(parsed)
                        else itemsToAdd.add(buildMediaItem(file, fileUri))
                    }
                    lower.endsWith(".pls") -> {
                        val parsed = try {
                            java.io.FileInputStream(file).use { parsePlsLocal(it, fileUri) }
                        } catch (e: Exception) { emptyList() }
                        if (parsed.isNotEmpty()) itemsToAdd.addAll(parsed)
                        else itemsToAdd.add(buildMediaItem(file, fileUri))
                    }
                    else -> itemsToAdd.add(buildMediaItem(file, fileUri))
                }
            }

            scanRecursive(root)

            runOnUiThread {
                loadingToast.cancel()
                if (itemsToAdd.isEmpty()) {
                    Toast.makeText(this, "No music found.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val sorted = if (isPlaylistFile) itemsToAdd
                else itemsToAdd.sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }

                if (replace) controller.setMediaItems(sorted) else controller.addMediaItems(sorted)
                controller.prepare()
                controller.play()
                Toast.makeText(this, "Added ${sorted.size} items", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }.start()
    }

    // ---------------------------------------------------------------------------
    // Playlist parsers (local copies used when adding from NowPlayingActivity)
    // ---------------------------------------------------------------------------

    private fun parseM3uLocal(inputStream: java.io.InputStream, baseUri: Uri? = null): List<MediaItem> {
        val items    = mutableListOf<MediaItem>()
        val basePath = baseUri?.toString()?.substringBeforeLast("/")
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null
            while (reader.readLine().also { line = it } != null) {
                var trimmed = line!!.trim().removePrefix("\uFEFF")
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#EXTINF:")) {
                    val comma = trimmed.indexOf(',')
                    if (comma != -1) currentTitle = trimmed.substring(comma + 1)
                } else if (!trimmed.startsWith("#")) {
                    val itemUri = resolvePlaylistEntry(trimmed.replace("\\", "/"), basePath)
                    if (itemUri != null) {
                        items.add(buildPlaylistItem(itemUri, currentTitle))
                    }
                    currentTitle = null
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

    private fun parsePlsLocal(inputStream: java.io.InputStream, baseUri: Uri? = null): List<MediaItem> {
        val items    = mutableListOf<MediaItem>()
        val basePath = baseUri?.toString()?.substringBeforeLast("/")
        try {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
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
                val file  = props.remove("file$i") ?: continue
                val itemUri = resolvePlaylistEntry(file.replace("\\", "/"), basePath) ?: continue
                val title = props.remove("title$i") ?: itemUri.lastPathSegment
                    ?: file.substringAfterLast("/").substringBeforeLast(".")
                items.add(buildPlaylistItem(itemUri, title))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return items
    }

    private fun resolvePlaylistEntry(path: String, basePath: String?): Uri? {
        var resolved = path
        if (basePath != null && !path.contains("://") && !path.startsWith("/")) {
            resolved = if (basePath.endsWith("/")) "$basePath$path" else "$basePath/$path"
        }
        return try {
            when {
                resolved.startsWith("/")          -> Uri.fromFile(java.io.File(resolved))
                resolved.startsWith("file://")    -> Uri.fromFile(java.io.File(resolved.substring(7)))
                resolved.startsWith("content://") ||
                resolved.startsWith("http://")    ||
                resolved.startsWith("https://")   ||
                resolved.startsWith("smb://")     -> Uri.parse(resolved)
                else                              -> null
            }
        } catch (e: Exception) { null }
    }

    private fun buildPlaylistItem(uri: Uri, title: String?): MediaItem {
        val meta = MediaMetadata.Builder()
        var finalTitle = title ?: uri.lastPathSegment ?: uri.toString()
        var artist = ""
        if (finalTitle.contains(" - ")) {
            val parts = finalTitle.split(" - ", limit = 2)
            artist     = parts[0].trim()
            finalTitle = parts[1].trim()
            meta.setArtist(artist)
        }
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMediaMetadata(meta.setTitle(finalTitle).build())
            .build()
    }

    // ---------------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------------

    private fun buildMediaItem(file: java.io.File, uri: Uri): MediaItem {
        val lower = file.name.lowercase()
        val mime  = when {
            lower.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3")  -> MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav")  -> MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg")  -> MimeTypes.AUDIO_OGG
            else                    -> null
        }
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name.substringBeforeLast(".")).build())
            .build()
    }

    private fun isPlayable(filename: String): Boolean {
        val ext = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".m3u", ".m3u8", ".pls")
        return ext.any { filename.lowercase().endsWith(it) }
    }

    private fun getThemeColor(): Int {
        val index = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
        return if (index in THEME_COLORS.indices) THEME_COLORS[index] else THEME_COLORS[0]
    }
}
