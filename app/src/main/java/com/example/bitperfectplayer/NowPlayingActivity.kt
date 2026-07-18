package com.example.bitperfectplayer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NowPlayingActivity : BaseActivity() {

    companion object {
        private const val PREFS_APP          = "AppSettings"
        private const val KEY_COLOR_SCHEME   = "color_scheme"
        private const val KEY_WAVEFORM_TYPE  = "waveform_type"
        private const val PURE_BLACK_INDEX   = 5
        private const val BG_DARK            = 0xFF121212.toInt()
        private const val BG_PURE_BLACK      = 0xFF000000.toInt()
        private const val PROGRESS_TICK_MS   = 1_000L
        private const val METADATA_DELAY_1   = 1_000L
        private const val METADATA_DELAY_2   = 3_000L

        val THEME_COLORS = intArrayOf(
            0xFF00E676.toInt(), 0xFF2979FF.toInt(), 0xFFFFC400.toInt(),
            0xFF7C4DFF.toInt(), 0xFFFF4081.toInt(), 0xFF888888.toInt(),
            0xFFFF5252.toInt(), 0xFF1B5E20.toInt(), 0xFFFFFFFF.toInt(),
        )
    }

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var animatedWaveform: AnimatedWaveformView

    // Album art: track last fetched key to avoid redundant network requests
    private var lastArtKey: String = ""
    private val artHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)       // CAA /front-250 is a 302 redirect to actual image
            .followSslRedirects(true)
            .build()
    }

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
    private lateinit var btnDacReset: ImageButton
    private var isSeekBarTracking = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
                updateDacButton()          // colour the DAC button based on current status
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

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        textTitle        = findViewById(R.id.text_title)
        textArtist       = findViewById(R.id.text_artist)
        textAlbum        = findViewById(R.id.text_album)
        textPlaylistPos  = findViewById(R.id.text_playlist_pos)
        textBitrate      = findViewById(R.id.text_bitrate)
        textCurrentTime  = findViewById(R.id.text_current_time)
        textTotalTime    = findViewById(R.id.text_total_time)
        imgTrackIcon     = findViewById(R.id.img_track_icon)
        animatedWaveform = findViewById(R.id.animated_waveform)
        seekBar          = findViewById(R.id.seek_bar)
        btnPlayPause     = findViewById(R.id.btn_play_pause)
        btnShuffle       = findViewById(R.id.btn_shuffle)
        btnRepeat        = findViewById(R.id.btn_repeat)
        btnDacReset      = findViewById(R.id.btn_dac_reset)

        // Ensure compound drawables are visible and tinted
        setupCompoundDrawables()
    }

    private fun setupCompoundDrawables() {
        val colorPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val colorSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val colorArtist = Color.parseColor("#CCFFFFFF")

        fun setTintedDrawable(tv: TextView, drawableRes: Int, color: Int) {
            val drawable = ContextCompat.getDrawable(this, drawableRes)?.mutate()
            drawable?.setTint(color)
            tv.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
        }

        setTintedDrawable(textTitle, R.drawable.ic_audio, colorPrimary)
        setTintedDrawable(textArtist, R.drawable.ic_artist, colorArtist)
        setTintedDrawable(textAlbum, R.drawable.ic_album_art, colorSecondary)
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    textCurrentTime.text = formatTime(progress.toLong())
                    if (!isSeekBarTracking) mediaController?.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isSeekBarTracking = true; handler.removeCallbacksAndMessages(null)
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                mediaController?.seekTo(sb?.progress?.toLong() ?: 0L)
                isSeekBarTracking = false; startProgressUpdate()
            }
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { mediaController?.seekToPrevious() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { mediaController?.seekToNext() }
        findViewById<ImageButton>(R.id.btn_playlist).setOnClickListener { showPlaylist() }

        btnPlayPause.setOnClickListener {
            mediaController?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        btnShuffle.setOnClickListener {
            mediaController?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; updateShuffleRepeatUI() }
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

        // DAC reset button — tap to reset, long-press for device info
        btnDacReset.setOnClickListener { triggerDacReset() }
        btnDacReset.setOnLongClickListener { showDacInfo(); true }
    }

    /** Resets the audio sink via PlaybackService, with visual feedback. */
    private fun triggerDacReset() {
        val svc = PlaybackService.instance
        if (svc == null) {
            Toast.makeText(this, "Playback service not running", Toast.LENGTH_SHORT).show()
            return
        }
        val dac = PlaybackService.findUsbAudioDevice(this)
        if (dac == null) {
            Toast.makeText(this, "No USB DAC detected", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Resetting DAC: ${dac.productName ?: "USB Audio"}…", Toast.LENGTH_SHORT).show()
        svc.resetAudioSink()
        btnDacReset.animate().alpha(0.2f).setDuration(120).withEndAction {
            btnDacReset.animate().alpha(1f).setDuration(280).start()
        }.start()
    }

    /** Shows a dialog with USB DAC info. Long-press on the DAC button. */
    private fun showDacInfo() {
        val svc = PlaybackService.instance
        val dac = PlaybackService.findUsbAudioDevice(this)
        // AudioDeviceInfo for audio capabilities (sample rates, bit depth, channels)
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val audioDac = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE    ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }

        val msg = if (dac != null) buildString {
            append("Product: ${dac.productName?.takeIf { it.isNotBlank() } ?: dac.deviceName}\n")
            if (audioDac != null) {
                val rates = audioDac.sampleRates
                if (rates.isNotEmpty()) append("Sample rates: ${rates.joinToString(", ")} Hz\n")
                val encs = audioDac.encodings.joinToString(", ") { enc ->
                    when (enc) {
                        android.media.AudioFormat.ENCODING_PCM_16BIT        -> "16-bit"
                        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
                        android.media.AudioFormat.ENCODING_PCM_32BIT        -> "32-bit"
                        android.media.AudioFormat.ENCODING_PCM_FLOAT        -> "Float"
                        android.media.AudioFormat.ENCODING_DTS              -> "DTS"
                        android.media.AudioFormat.ENCODING_AC3              -> "AC3"
                        else -> "enc:$enc"
                    }
                }
                if (encs.isNotEmpty()) append("Encodings: $encs\n")
                val ch = audioDac.channelCounts
                if (ch.isNotEmpty()) {
                    val native = ch.filter { it <= 2 }.maxOrNull() ?: ch.min()
                    val label = when (native) {
                        1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$native ch"
                    }
                    append("Channels: $label")
                    if (ch.max() > native) append(" (upmix up to ${ch.max()})")
                    append("\n")
                }
            }
            append("\nTap the DAC button to reset if output\nsounds wrong after power-cycling the DAC.")
        } else {
            "No USB DAC detected.\n\nConnect a USB DAC — it is detected\nautomatically on startup or when plugged in."
        }

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("USB DAC Status")
            .setIcon(R.drawable.ic_dac)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .apply { if (dac != null) setNeutralButton("Reset Now") { _, _ -> triggerDacReset() } }
            .show()
    }

    /** Updates DAC button: applies theme color tint and dims when no DAC is present. */
    private fun updateDacButton() {
        val dacPresent = PlaybackService.findUsbAudioDevice(this) != null
        val color = getThemeColor()
        btnDacReset.imageTintList = android.content.res.ColorStateList.valueOf(color)
        btnDacReset.alpha = if (dacPresent) 1.0f else 0.3f
    }

    // ── Controller / playback ─────────────────────────────────────────────────

    private fun setupController() {
        val controller = mediaController ?: return
        animatedWaveform.setPlaying(controller.isPlaying)
        animatedWaveform.setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
        animatedWaveform.setColor(getThemeColor())
        updateControlButtonsTint()

        controller.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int)         { updateUI(); refreshScreensaver() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata)              { updateUI(); refreshScreensaver() }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks)            { updateUI(); refreshScreensaver() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateUI()
                if (playbackState == Player.STATE_READY) {
                    handler.postDelayed({ updateUI() }, METADATA_DELAY_1)
                    handler.postDelayed({ updateUI() }, METADATA_DELAY_2)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                animatedWaveform.setPlaying(isPlaying)
                window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")?.setPlaying(isPlaying)
                if (isPlaying) { startProgressUpdate(); updateUI(); handler.postDelayed({ updateUI() }, 500) }
                resetScreensaverTimer()
                updateDacButton()   // refresh DAC indicator whenever play state changes
            }
            override fun onRepeatModeChanged(repeatMode: Int)                              { updateShuffleRepeatUI() }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean)          { updateShuffleRepeatUI() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                textBitrate.text = "Error: ${error.errorCodeName}"
                Toast.makeText(this@NowPlayingActivity, "Playback error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })

        updateUI()
        startProgressUpdate()
    }

    // ── Screensaver ───────────────────────────────────────────────────────────

    private fun refreshScreensaver() {
        if (!isScreensaverActive) return
        window.decorView.findViewWithTag<TextView>("screensaver_text")?.let { updateScreensaverText(it) }
        val wv = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
        wv?.setPlaying(mediaController?.isPlaying == true)
        wv?.setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
        wv?.setColor(getThemeColor())
    }

    override fun onScreensaverCreated(container: android.view.ViewGroup) {
        val dp = resources.displayMetrics.density
        AnimatedWaveformView(this).apply {
            tag = "screensaver_waveform"
            layoutParams = android.widget.LinearLayout.LayoutParams((400 * dp).toInt(), (64 * dp).toInt())
                .also { it.topMargin = (24 * dp).toInt() }
            setPlaying(mediaController?.isPlaying == true)
            setWaveformType(getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_WAVEFORM_TYPE, 0))
            setColor(getThemeColor())
            container.addView(this)
        }
    }

    @OptIn(UnstableApi::class)
    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController ?: return
        val metadata   = controller.mediaMetadata
        val itemMeta   = controller.currentMediaItem?.mediaMetadata
        var title  = metadata.title?.toString() ?: itemMeta?.title?.toString() ?: metadata.displayTitle?.toString() ?: "Bitperfect Player"
        var artist = metadata.artist?.toString() ?: itemMeta?.artist?.toString() ?: metadata.albumArtist?.toString() ?: ""
        var album = metadata.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString() ?: ""

        val delims = arrayOf(" - ", " – ", " — ", " : ", " | ")
        if (artist.isEmpty() || artist.equals("Unknown Artist", ignoreCase = true)) {
            for (d in delims) { if (title.contains(d)) { val p = title.split(d, limit=2); artist = p[0].trim(); title = p[1].trim(); break } }
        }
        val station = metadata.station?.toString() ?: itemMeta?.station?.toString()
        if (artist.isEmpty() || artist.equals("Unknown Artist", ignoreCase = true)) artist = station ?: ""

        val sb = SpannableStringBuilder()
        sb.append("Now Playing:\n\n")

        val iconColor = Color.LTGRAY
        val iconSize = (textView.textSize * 1.2f).toInt()

        fun appendRow(text: String, iconRes: Int) {
            if (text.isBlank()) return
            val start = sb.length
            sb.append("  ") // placeholder for icon
            val drawable = ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply {
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

        if (!station.isNullOrBlank() && station != artist && station != title) {
            sb.append("(").append(station).append(")\n")
        }

        controller.currentTracks.groups.forEach { g ->
            if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected)
                for (i in 0 until g.length) {
                    if (g.isTrackSelected(i)) {
                        buildFormatInfo(g.getTrackFormat(i))?.let { sb.append("\n").append(it) }
                        break
                    }
                }
        }
        textView.text = sb
        textView.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
    }

    // ── UI updates ────────────────────────────────────────────────────────────

    private fun updateControlButtonsTint() {
        val tc = getThemeColor()
        val tl = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf((tc and 0x00FFFFFF) or 0x66000000, (tc and 0x00FFFFFF) or 0x44000000, Color.TRANSPARENT)
        )
        listOf(R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play_pause, R.id.btn_next,
               R.id.btn_repeat, R.id.btn_playlist, R.id.btn_dac_reset)
            .forEach { id -> findViewById<ImageButton>(id)?.backgroundTintList = tl }
    }

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return

        val colorIndex = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
        val bgColor = if (colorIndex >= PURE_BLACK_INDEX) BG_PURE_BLACK else BG_DARK
        window.decorView.setBackgroundColor(bgColor)
        findViewById<android.widget.LinearLayout>(R.id.now_playing_root)?.setBackgroundColor(bgColor)

        val metadata  = controller.mediaMetadata
        val mediaItem = controller.currentMediaItem
        val itemMeta  = mediaItem?.mediaMetadata
        val title        = metadata.title?.toString()        ?: itemMeta?.title?.toString()
        val displayTitle = metadata.displayTitle?.toString() ?: itemMeta?.displayTitle?.toString()
        val artist       = metadata.artist?.toString()       ?: itemMeta?.artist?.toString()
        val albumArtist  = metadata.albumArtist?.toString()  ?: itemMeta?.albumArtist?.toString()
        val station      = metadata.station?.toString()      ?: itemMeta?.station?.toString()
        val subtitle     = metadata.subtitle?.toString()     ?: itemMeta?.subtitle?.toString()
        val description  = metadata.description?.toString()  ?: itemMeta?.description?.toString()

        var dispArtist = artist ?: albumArtist ?: ""
        var dispTrack  = title  ?: displayTitle ?: "Unknown Title"
        if (dispArtist.isEmpty() || dispArtist.equals("Unknown Artist", ignoreCase = true)) {
            dispArtist = when {
                !subtitle.isNullOrBlank()    && subtitle    != dispTrack -> subtitle
                !description.isNullOrBlank() && description != dispTrack -> description
                else -> dispArtist
            }
        }
        val delims = arrayOf(" - ", " – ", " — ", " : ", " | ")
        var split = false
        for (d in delims) { if (dispTrack.contains(d)) { val p = dispTrack.split(d, limit=2); dispArtist = p[0].trim(); dispTrack = p[1].trim(); split = true; break } }
        if (!split && dispArtist.contains(" - ")) { val p = dispArtist.split(" - ", limit=2); dispArtist = p[0].trim(); dispTrack = p[1].trim() }
        if (dispArtist.isEmpty() || dispArtist.equals("Unknown Artist", ignoreCase = true)) dispArtist = station ?: displayTitle ?: "Unknown Artist"
        if (dispArtist == dispTrack && !station.isNullOrBlank()) dispArtist = station

        textTitle.text  = dispTrack
        textArtist.text = dispArtist

        val finalAlbum = when {
            !station.isNullOrBlank()      && station      != dispTrack && station      != dispArtist -> station
            !displayTitle.isNullOrBlank() && displayTitle != dispTrack && displayTitle != dispArtist -> displayTitle
            else -> metadata.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString()
        }
        if (!finalAlbum.isNullOrEmpty()) { textAlbum.text = finalAlbum; textAlbum.visibility = android.view.View.VISIBLE }
        else textAlbum.visibility = android.view.View.GONE

        textPlaylistPos.text = "${controller.currentMediaItemIndex + 1} / ${controller.mediaItemCount}"

        val uri = mediaItem?.mediaId ?: ""
        loadAlbumArt(dispArtist, metadata.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString(), dispTrack)

        btnPlayPause.setImageResource(if (controller.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateShuffleRepeatUI()
        updateDacButton()

        var format: Format? = null
        for (g in controller.currentTracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected) {
                for (i in 0 until g.length) { if (g.isTrackSelected(i)) { format = g.getTrackFormat(i); break } }
                if (format != null) break
            }
        }
        val infoList = mutableListOf<String>()
        val icyName = station ?: albumArtist
        if (!icyName.isNullOrBlank() && icyName != artist && icyName != dispArtist) infoList.add(icyName)
        format?.let { infoList.addAll(formatInfoParts(it)) }

        if (infoList.isNotEmpty()) { textBitrate.text = infoList.joinToString(" | "); textBitrate.setTextColor(Color.WHITE) }
        else {
            textBitrate.setTextColor(0xFF888888.toInt())
            textBitrate.text = when (controller.playbackState) {
                Player.STATE_BUFFERING -> "Buffering…"; Player.STATE_IDLE -> "Idle"
                Player.STATE_ENDED -> "Playback ended"; else -> "Detecting format…"
            }
        }
    }

    // ── Album art ─────────────────────────────────────────────────────────────

    /**
     * Fetches album art and displays it in imgTrackIcon.
     * Skips network if artist+album key is unchanged since last successful fetch.
     *
     * Priority:
     *  1. Embedded artwork in MediaMetadata.artworkData (ExoPlayer reads ID3/FLAC tags)
     *  2. MusicBrainz release search → Cover Art Archive (redirects followed automatically)
     *  3. iTunes Search API (free, no key)
     *  4. Fallback: ic_audio icon tinted with theme color
     */
    private fun loadAlbumArt(artist: String, album: String?, track: String) {
        val artKey = "$artist|${album ?: track}"
        if (artKey == lastArtKey) return
        lastArtKey = artKey

        // 1 — Embedded artwork from ExoPlayer (ID3v2 APIC / FLAC PICTURE tag)
        val artworkData = mediaController?.mediaMetadata?.artworkData
            ?: mediaController?.currentMediaItem?.mediaMetadata?.artworkData
        if (artworkData != null && artworkData.isNotEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            if (bmp != null) {
                imgTrackIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                imgTrackIcon.setImageBitmap(bmp)
                imgTrackIcon.clearColorFilter()
                return
            }
        }

        // Placeholder while loading
        imgTrackIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        imgTrackIcon.setImageResource(R.drawable.ic_audio)
        imgTrackIcon.setColorFilter(getThemeColor())

        if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) return

        // Capture key so closure can detect stale results after track change
        val capturedKey = artKey

        Thread {
            val bitmap = fetchFromMusicBrainz(artist, album, track)
                ?: fetchFromItunes(artist, album ?: track)

            runOnUiThread {
                if (capturedKey != lastArtKey) return@runOnUiThread   // track changed while fetching
                if (bitmap != null) {
                    imgTrackIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    imgTrackIcon.setImageBitmap(bitmap)
                    imgTrackIcon.clearColorFilter()
                } else {
                    imgTrackIcon.scaleType = ImageView.ScaleType.FIT_CENTER
                    imgTrackIcon.setImageResource(R.drawable.ic_audio)
                    imgTrackIcon.setColorFilter(getThemeColor())
                }
            }
        }.start()
    }

    /**
     * MusicBrainz release search → Cover Art Archive.
     * CAA /front-250 returns HTTP 307 redirect; OkHttp follows it automatically.
     */
    private fun fetchFromMusicBrainz(artist: String, album: String?, track: String): Bitmap? {
        return try {
            val query = if (!album.isNullOrBlank())
                "release:\"${album.mbEscape()}\" AND artist:\"${artist.mbEscape()}\""
            else
                "recording:\"${track.mbEscape()}\" AND artist:\"${artist.mbEscape()}\""

            val searchUrl = "https://musicbrainz.org/ws/2/release/?query=${Uri.encode(query)}&limit=5&fmt=json"
            val mbJson = artGet(searchUrl, "application/json") ?: return null

            val releases = JSONObject(mbJson).optJSONArray("releases") ?: return null
            if (releases.length() == 0) return null

            var bestMbid: String? = null
            var bestScore = -1
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                val score = rel.optInt("score", 0)
                if (score > bestScore) { bestScore = score; bestMbid = rel.optString("id").takeIf { it.isNotBlank() } }
            }
            if (bestMbid.isNullOrBlank()) return null

            // /front-250 redirects to the real image — OkHttp follows automatically
            val caaUrl = "https://coverartarchive.org/release/$bestMbid/front-250"
            val imgBytes = artGetBytes(caaUrl) ?: return null
            BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
        } catch (e: Exception) {
            Log.d("AlbumArt", "MusicBrainz failed: ${e.message}")
            null
        }
    }

    /**
     * iTunes Search API — free, no key, returns 100×100 artwork URL that can be
     * upscaled to 600×600 by replacing the size suffix.
     */
    private fun fetchFromItunes(artist: String, albumOrTrack: String): Bitmap? {
        return try {
            val term = Uri.encode("$artist $albumOrTrack")
            val url = "https://itunes.apple.com/search?term=$term&media=music&entity=album&limit=5"
            val json = artGet(url, "application/json") ?: return null
            val results = JSONObject(json).optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            // artwork URL is e.g. "…/100x100bb.jpg" — replace with 600x600
            val artUrl = results.getJSONObject(0)
                .optString("artworkUrl100").takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb") ?: return null

            val imgBytes = artGetBytes(artUrl) ?: return null
            BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
        } catch (e: Exception) {
            Log.d("AlbumArt", "iTunes failed: ${e.message}")
            null
        }
    }

    /** GET request returning response body as String, or null on non-2xx. */
    private fun artGet(url: String, accept: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "BitperfectPlayer/2.3 (https://github.com/antoxa78/BitperfectPlayer)")
            .header("Accept", accept)
            .build()
        val resp = artHttpClient.newCall(req).execute()
        return if (resp.isSuccessful) resp.body?.string() else null
    }

    /** GET request returning response body as ByteArray, or null on non-2xx. */
    private fun artGetBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "BitperfectPlayer/2.3 (https://github.com/antoxa78/BitperfectPlayer)")
            .build()
        val resp = artHttpClient.newCall(req).execute()
        return if (resp.isSuccessful) resp.body?.bytes() else null
    }

    /** Escapes Lucene special characters for MusicBrainz query strings. */
    private fun String.mbEscape() = this.replace("\"", "\\\"").take(100)

    private fun updateShuffleRepeatUI() {
        val c = mediaController ?: return
        btnShuffle.alpha = if (c.shuffleModeEnabled) 1.0f else 0.5f
        when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> { btnRepeat.setImageResource(R.drawable.ic_repeat);     btnRepeat.alpha = 0.5f }
            Player.REPEAT_MODE_ALL -> { btnRepeat.setImageResource(R.drawable.ic_repeat);     btnRepeat.alpha = 1.0f }
            Player.REPEAT_MODE_ONE -> { btnRepeat.setImageResource(R.drawable.ic_repeat_one); btnRepeat.alpha = 1.0f }
        }
    }

    // ── Progress bar ──────────────────────────────────────────────────────────

    private fun startProgressUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val c = mediaController ?: return
                val pos = c.currentPosition; val dur = c.duration
                textCurrentTime.text = formatTime(pos)
                if (dur > 0 && dur != Long.MAX_VALUE && dur != C.TIME_UNSET) {
                    seekBar.max = dur.toInt(); seekBar.progress = pos.toInt(); textTotalTime.text = formatTime(dur)
                } else { seekBar.max = 100; seekBar.progress = 0; textTotalTime.text = "∞" }
                handler.postDelayed(this, PROGRESS_TICK_MS)
            }
        })
    }

    private fun formatTime(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ── Format helpers ────────────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun formatInfoParts(f: Format): List<String> {
        val p = mutableListOf<String>()
        if (f.bitrate != Format.NO_VALUE && f.bitrate > 0) p.add("${f.bitrate / 1000} kbps")
        if (f.sampleRate != Format.NO_VALUE && f.sampleRate > 0) p.add("${f.sampleRate} Hz")
        val bd = when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; C.ENCODING_PCM_FLOAT -> "Float"; else -> "" }
        if (bd.isNotEmpty()) p.add(bd)
        return p
    }
    @OptIn(UnstableApi::class)
    private fun buildFormatInfo(f: Format): String? = formatInfoParts(f).takeIf { it.isNotEmpty() }?.joinToString(" | ")

    // ── Playlist dialog ───────────────────────────────────────────────────────

    private fun showPlaylist() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        val items = List(count) { i -> val it = controller.getMediaItemAt(i); "${i+1}. ${it.mediaMetadata.title ?: "Unknown"}" }
        val adapter = object : android.widget.ArrayAdapter<String>(this, R.layout.list_item_browse, items) {
            override fun getView(pos: Int, cv: android.view.View?, p: android.view.ViewGroup): android.view.View {
                val v = cv ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, p, false)
                v.findViewById<TextView>(R.id.item_text).text = getItem(pos)
                val mi = controller.getMediaItemAt(pos)
                val ico = when { mi.mediaId.startsWith("smb://") -> R.drawable.ic_network; mi.mediaId.startsWith("content://") -> R.drawable.ic_folder; else -> R.drawable.ic_audio }
                v.findViewById<ImageView>(R.id.item_icon).setImageResource(ico)
                return v
            }
        }
        val dv = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(16,16,16,16) }
        val lv = android.widget.ListView(this).apply { setAdapter(adapter); descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS; layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        dv.addView(lv)
        val br = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(dv).create()
        fun btn(lbl: String, onClick: () -> Unit) = android.widget.Button(this).apply { text=lbl; setOnClickListener { onClick(); dlg.dismiss() }; layoutParams=android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f); isFocusable=true }
        br.addView(btn("Clear") { controller.clearMediaItems(); Toast.makeText(this@NowPlayingActivity, "Playlist cleared", Toast.LENGTH_SHORT).show(); updateUI() })
        br.addView(btn("Add Files")     { checkAndBrowseInternalStorage(isSelectionMode=false, isDirectoryMode=false) })
        br.addView(btn("Add Directory") { checkAndBrowseInternalStorage(isSelectionMode=true,  isDirectoryMode=true) })
        dv.addView(br)
        lv.setOnItemClickListener { _, _, w, _ -> controller.seekTo(w, 0); controller.play(); dlg.dismiss() }
        dlg.show()
    }

    // ── Internal storage browsing ─────────────────────────────────────────────

    private fun checkAndBrowseInternalStorage(isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
            } else {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("All Files Access Required")
                    .setMessage("Please grant 'All Files Access' in system settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        try { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).also { it.data = "package:$packageName".toUri() }) }
                        catch (e: Exception) { startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        } else browseFileStorage(android.os.Environment.getExternalStorageDirectory().absolutePath, isSelectionMode, isDirectoryMode)
    }

    private data class BrowseItem(val name: String, val path: String, val icon: Int, val isDirectory: Boolean)

    private inner class BrowseAdapter(items: List<BrowseItem>) : android.widget.ArrayAdapter<BrowseItem>(this, R.layout.list_item_browse, items) {
        override fun getView(pos: Int, cv: android.view.View?, p: android.view.ViewGroup): android.view.View {
            val v = cv ?: android.view.LayoutInflater.from(context).inflate(R.layout.list_item_browse, p, false)
            val it = getItem(pos)!!
            v.findViewById<TextView>(R.id.item_text).text = it.name
            v.findViewById<ImageView>(R.id.item_icon).setImageResource(it.icon)
            return v
        }
    }

    private fun browseFileStorage(path: String, isSelectionMode: Boolean, isDirectoryMode: Boolean) {
        val dir = java.io.File(path)
        val toast = Toast.makeText(this, "Reading folder…", Toast.LENGTH_SHORT); toast.show()
        Thread {
            try {
                val sorted = (dir.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .mapNotNull { f -> when {
                        f.isDirectory -> BrowseItem(f.name, f.absolutePath, R.drawable.ic_folder, true)
                        !isDirectoryMode && isPlayable(f.name) -> BrowseItem(f.name, f.absolutePath, if (f.name.lowercase().run { endsWith(".m3u")||endsWith(".m3u8")||endsWith(".pls") }) R.drawable.ic_playlist else R.drawable.ic_audio, false)
                        else -> null
                    } }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                runOnUiThread {
                    toast.cancel()
                    val title = if (isDirectoryMode) "Select Directory" else dir.name.ifEmpty { "Storage" }
                    val dv = android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.VERTICAL; setPadding(16,16,16,16) }
                    val lv = android.widget.ListView(this).apply { adapter=BrowseAdapter(sorted); descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS; layoutParams=android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT,0,1f) }
                    dv.addView(lv)
                    val br = android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER; layoutParams=android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }
                    val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(dv).create()
                    fun btn(lbl: String, cb: () -> Unit) = android.widget.Button(this).apply { text=lbl; setOnClickListener { cb(); dlg.dismiss() }; layoutParams=android.widget.LinearLayout.LayoutParams(0,android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,1f); isFocusable=true }
                    br.addView(btn("Back") { val p=dir.parentFile; val root=android.os.Environment.getExternalStorageDirectory().parent; if (p!=null && p.absolutePath!=root) browseFileStorage(p.absolutePath, isSelectionMode, isDirectoryMode) })
                    if (isDirectoryMode) br.addView(btn("Add Folder") { addFilesToPlaylist(dir, false) })
                    else { br.addView(btn("Add All") { addFilesToPlaylist(dir, false) }); br.addView(btn("Replace All") { addFilesToPlaylist(dir, true) }) }
                    dv.addView(br)
                    lv.setOnItemClickListener { _, _, w, _ -> dlg.dismiss(); val it=sorted[w]; if (it.isDirectory) browseFileStorage(it.path, isSelectionMode, isDirectoryMode) else handleFileSelection(it.path, it.name) }
                    dlg.show()
                }
            } catch (e: Exception) { runOnUiThread { toast.cancel(); Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }.start()
    }

    private fun handleFileSelection(path: String, name: String) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setAdapter(MainFragment.DialogOptionAdapter(this, listOf(MainFragment.DialogOptionItem("Add to current playlist", R.drawable.ic_queue_music), MainFragment.DialogOptionItem("Replace current playlist", R.drawable.ic_playlist)))) { _, w -> addFilesToPlaylist(java.io.File(path), w==1) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addFilesToPlaylist(root: java.io.File, replace: Boolean) {
        val controller = mediaController ?: return
        val toast = Toast.makeText(this, "Processing files…", Toast.LENGTH_SHORT); toast.show()
        Thread {
            val items = mutableListOf<MediaItem>()
            val isPl = !root.isDirectory && isPlayable(root.name) && root.name.lowercase().run { endsWith(".m3u")||endsWith(".m3u8")||endsWith(".pls")||endsWith(".cue") }
            fun scan(f: java.io.File) {
                if (f.isDirectory) { f.listFiles()?.forEach { scan(it) }; return }
                if (!isPlayable(f.name)) return
                val lo = f.name.lowercase(); val fu = android.net.Uri.fromFile(f)
                when {
                    lo.endsWith(".m3u")||lo.endsWith(".m3u8") -> { val p = try { java.io.FileInputStream(f).use { parseM3uLocal(it, fu) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    lo.endsWith(".cue") -> { val p = try { java.io.FileInputStream(f).use { parseCueLocal(it, fu) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    lo.endsWith(".pls") -> { val p = try { java.io.FileInputStream(f).use { parsePlsLocal(it, fu) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    else -> items.add(buildMediaItem(f, fu))
                }
            }
            scan(root)
            runOnUiThread {
                toast.cancel()
                if (items.isEmpty()) { Toast.makeText(this, "No music found.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val sorted = if (isPl) items else items.sortedBy { it.mediaMetadata.title?.toString()?.lowercase() }
                if (replace) controller.setMediaItems(sorted) else controller.addMediaItems(sorted)
                controller.prepare(); controller.play()
                Toast.makeText(this, "Added ${sorted.size} items", Toast.LENGTH_SHORT).show(); updateUI()
            }
        }.start()
    }

    // ── Playlist parsers (local) ───────────────────────────────────────────────

    private fun parseM3uLocal(s: java.io.InputStream, base: Uri?): List<MediaItem> {
        val items = mutableListOf<MediaItem>(); val bp = base?.toString()?.substringBeforeLast("/")
        try { val r = java.io.BufferedReader(java.io.InputStreamReader(s)); var line: String?; var ct: String? = null
            while (r.readLine().also { line = it } != null) { val t = line!!.trim().removePrefix("\uFEFF"); if (t.isEmpty()) continue
                if (t.startsWith("#EXTINF:")) { val c = t.indexOf(','); if (c!=-1) ct = t.substring(c+1) }
                else if (!t.startsWith("#")) { val u = resolvePlaylistEntry(t.replace("\\","/"), bp); if (u!=null) items.add(buildPlaylistItem(u, ct)); ct=null }
            }
        } catch (e: Exception) { e.printStackTrace() }; return items
    }

    private fun parsePlsLocal(s: java.io.InputStream, base: Uri?): List<MediaItem> {
        val items = mutableListOf<MediaItem>(); val bp = base?.toString()?.substringBeforeLast("/")
        try { val r = java.io.BufferedReader(java.io.InputStreamReader(s)); val props = linkedMapOf<String,String>(); var line: String?
            while (r.readLine().also { line = it } != null) { val t = line!!.trim().removePrefix("\uFEFF"); if (t.isEmpty()||t.startsWith("[")) continue; val eq = t.indexOf('='); if (eq!=-1) props[t.substring(0,eq).trim().lowercase()] = t.substring(eq+1).trim() }
            val cnt = props.remove("numberofentries")?.toIntOrNull() ?: 0
            for (i in 1..cnt) { val f = props.remove("file$i") ?: continue; val u = resolvePlaylistEntry(f.replace("\\","/"), bp) ?: continue; val tl = props.remove("title$i") ?: u.lastPathSegment ?: f.substringAfterLast("/").substringBeforeLast("."); items.add(buildPlaylistItem(u, tl)) }
        } catch (e: Exception) { e.printStackTrace() }; return items
    }

    private fun parseCueLocal(s: java.io.InputStream, base: Uri?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val bp = base?.toString()?.substringBeforeLast("/")
        try {
            val r = java.io.BufferedReader(java.io.InputStreamReader(s))
            var line: String?; var curFile: String? = null; var albT: String? = null; var albA: String? = null
            class Trk(val num: Int, var t: String? = null, var a: String? = null, var start: Long = 0)
            val trks = mutableListOf<Trk>(); var curTrk: Trk? = null
            while (r.readLine().also { line = it } != null) {
                val t = line!!.trim().removePrefix("\uFEFF"); val u = t.uppercase()
                when {
                    u.startsWith("FILE") -> curFile = t.substringAfter("\"").substringBeforeLast("\"")
                    u.startsWith("TITLE") && curTrk == null -> albT = t.substringAfter("\"").substringBeforeLast("\"")
                    u.startsWith("PERFORMER") && curTrk == null -> albA = t.substringAfter("\"").substringBeforeLast("\"")
                    u.startsWith("TRACK") -> { curTrk = Trk(t.split(" ")[1].toIntOrNull() ?: 0); trks.add(curTrk) }
                    u.startsWith("TITLE") && curTrk != null -> curTrk.t = t.substringAfter("\"").substringBeforeLast("\"")
                    u.startsWith("PERFORMER") && curTrk != null -> curTrk.a = t.substringAfter("\"").substringBeforeLast("\"")
                    u.startsWith("INDEX 01") && curTrk != null -> {
                        val ts = t.substringAfter("INDEX 01").trim().split(":")
                        if (ts.size == 3) curTrk.start = (ts[0].toLong() * 60000) + (ts[1].toLong() * 1000) + (ts[2].toLong() * 1000 / 75)
                    }
                }
            }
            if (curFile != null && trks.isNotEmpty()) {
                val au = resolvePlaylistEntry(curFile, bp)
                if (au != null) {
                    for (i in trks.indices) {
                        val trk = trks[i]; val next = if (i + 1 < trks.size) trks[i+1].start else C.TIME_UNSET
                        val m = MediaMetadata.Builder().setTitle(trk.t ?: "Track ${trk.num}").setArtist(trk.a ?: albA ?: "Unknown Artist").setAlbumTitle(albT ?: "Unknown Album")
                        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(trk.start)
                        if (next != C.TIME_UNSET) clip.setEndPositionMs(next)
                        items.add(MediaItem.Builder().setMediaId("${au}_${trk.num}").setUri(au).setMimeType(mimeTypeFor(au.toString())).setMediaMetadata(m.build()).setClippingConfiguration(clip.build()).build())
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }; return items
    }

    private fun resolvePlaylistEntry(path: String, base: String?): Uri? {
        var r = path; if (base!=null && !path.contains("://") && !path.startsWith("/")) r = if (base.endsWith("/")) "$base$path" else "$base/$path"
        return try { when { r.startsWith("/") -> Uri.fromFile(java.io.File(r)); r.startsWith("file://") -> Uri.fromFile(java.io.File(r.substring(7))); r.startsWith("content://")||r.startsWith("http://")||r.startsWith("https://")||r.startsWith("smb://") -> r.toUri(); else -> null } } catch (e: Exception) { null }
    }

    private fun buildPlaylistItem(uri: Uri, title: String?): MediaItem {
        val m = MediaMetadata.Builder(); var t = title ?: uri.lastPathSegment ?: uri.toString()
        if (t.contains(" - ")) { val p = t.split(" - ", limit=2); m.setArtist(p[0].trim()); t=p[1].trim() }
        return MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMediaMetadata(m.setTitle(t).build()).build()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun buildMediaItem(f: java.io.File, uri: Uri): MediaItem {
        val lo = f.name.lowercase()
        val mime = mimeTypeFor(lo)
        return MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMimeType(mime).setMediaMetadata(MediaMetadata.Builder().setTitle(f.name.substringBeforeLast(".")).build()).build()
    }

    private fun mimeTypeFor(uriString: String): String? {
        val lo = uriString.lowercase()
        return when {
            lo.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
            lo.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            lo.endsWith(".wav") -> MimeTypes.AUDIO_WAV
            lo.endsWith(".m4a")||lo.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            lo.endsWith(".ogg") -> MimeTypes.AUDIO_OGG
            lo.endsWith(".ape") -> "audio/x-ape"
            else -> null
        }
    }

    private fun isPlayable(name: String) = listOf(".mp3",".flac",".wav",".m4a",".aac",".ogg",".wma",".m3u",".m3u8",".pls",".cue",".ape").any { name.lowercase().endsWith(it) }

    private fun getThemeColor(): Int {
        val idx = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
        return if (idx in THEME_COLORS.indices) THEME_COLORS[idx] else THEME_COLORS[0]
    }
}
