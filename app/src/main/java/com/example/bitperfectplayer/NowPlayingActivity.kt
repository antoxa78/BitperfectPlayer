package com.example.bitperfectplayer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
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
import androidx.core.graphics.ColorUtils
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import jcifs.smb.SmbFile
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NowPlayingActivity : BaseActivity() {

    companion object {
        private const val PREFS_APP          = "AppSettings"
        private const val PROGRESS_TICK_MS   = 1_000L
        private const val METADATA_DELAY_1   = 1_000L
        private const val METADATA_DELAY_2   = 3_000L
        private const val DAC_RESET_SERVICE_RETRY_MS = 200L
        private const val ART_FADE_MS        = 350L
        /** Focus zoom of the transport buttons; play/pause is already the largest. */
        private const val BUTTON_FOCUS_SCALE = 1.15f
        private const val PLAY_FOCUS_SCALE   = 1.08f
        private const val FOCUS_ZOOM_MS      = 150L
        private const val BACKDROP_FADE_MS   = 600L
        /** Opacity of the blurred cover behind the UI (under the theme scrim). */
        private const val BACKDROP_ALPHA     = 0.85f

        private val CLEANUP_REGEX = Regex("(?i)(\\s*\\(.*?remaster.*?\\)|\\s*\\[.*?explicit.*?\\]|\\s*\\(.*?live.*?\\)|\\s*\\(.*?feat.*?\\)|\\s*-\\s*single|\\s*-\\s*ep)")
        private val FOLDER_ART_NAMES = arrayOf("folder.jpg", "cover.jpg", "album.jpg", "folder.png", "cover.png", "album.png", "front.jpg")

        /** Accent colours of all themes, indexed by theme id (see [ColorThemes]). */
        val THEME_COLORS: IntArray get() = ColorThemes.ALL.map { it.accent }.toIntArray()
    }

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var animatedWaveform: AnimatedWaveformView

    // Stored so the listener can be removed in onDestroy (BUG-11).
    private var playerListener: Player.Listener? = null

    // Album art: track last fetched key to avoid redundant network requests
    private var lastArtKey: String = ""
    private var lastIcyInfo: IcyStreamInfo? = null
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
    private lateinit var imgBackdrop: ImageView
    private lateinit var backdropScrim: android.view.View
    private lateinit var artFrame: android.view.View
    /** Cover currently shown (null while the placeholder is up), for re-theming the backdrop. */
    private var currentArt: Bitmap? = null
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnShuffle: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var btnDacReset: ImageButton
    /** Same logo + wordmark as the main menu, in the same top-right spot. */
    private lateinit var brandMark: BrandMark
    private var isSeekBarTracking = false
    private var progressUpdateRunnable: Runnable? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        bindViews()
        setupSeekBar()
        setupButtons()
        focusPlayPause()

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

    override fun onResume() {
        super.onResume()
        // Coming back with nothing focused (e.g. after the playlist/info dialogs or
        // the screensaver): put the D-pad selector back on play/pause.
        if (currentFocus == null) focusPlayPause()
        // The colour theme or output mode may have changed in Settings while this screen was hidden.
        if (::btnDacReset.isInitialized) {
            applyColorTheme()
            updateDacButton()
        }
        applyWaveformPreference()
    }

    /** Shows the selected type, or removes the view entirely for "Disabled". */
    private fun applyWaveformPreference() {
        val type = WaveformSettings.type(this)
        if (type == WaveformSettings.TYPE_DISABLED) {
            animatedWaveform.visibility = android.view.View.GONE
            return
        }
        animatedWaveform.visibility = android.view.View.VISIBLE
        animatedWaveform.setPlaying(mediaController?.isPlaying == true)
        animatedWaveform.setWaveformType(type)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Remove the player listener before releasing the controller so it cannot
        // fire on a released MediaController (BUG-11, BUG-12).
        playerListener?.let { mediaController?.removeListener(it) }
        playerListener = null
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        textTitle        = findViewById(R.id.text_title)
        textArtist       = findViewById(R.id.text_artist)
        textAlbum        = findViewById(R.id.text_album)
        textPlaylistPos  = findViewById(R.id.text_playlist_pos)
        textPlaylistPos.ellipsize = android.text.TextUtils.TruncateAt.END
        textBitrate      = findViewById(R.id.text_bitrate)
        textCurrentTime  = findViewById(R.id.text_current_time)
        textTotalTime    = findViewById(R.id.text_total_time)
        imgTrackIcon     = findViewById(R.id.img_track_icon)
        animatedWaveform = findViewById(R.id.animated_waveform)
        applyWaveformPreference()
        seekBar          = findViewById(R.id.seek_bar)
        btnPlayPause     = findViewById(R.id.btn_play_pause)
        btnShuffle       = findViewById(R.id.btn_shuffle)
        btnRepeat        = findViewById(R.id.btn_repeat)
        btnDacReset      = findViewById(R.id.btn_dac_reset)
        imgBackdrop      = findViewById(R.id.img_backdrop)
        backdropScrim    = findViewById(R.id.view_backdrop_scrim)
        artFrame         = findViewById(R.id.art_frame)

        // Brand mark in the top-right corner, at the main menu's insets so it does not
        // jump between screens. Added last to the root so it draws above the backdrop.
        val density = resources.displayMetrics.density
        brandMark = BrandMark(this)
        findViewById<android.widget.FrameLayout>(R.id.now_playing_root).addView(
            brandMark,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ).apply {
                topMargin = (BrandMark.TOP_INSET_DP * density).toInt()
                marginEnd = (BrandMark.END_INSET_DP * density).toInt()
            }
        )
        // With a two-line title the text column reaches up to the brand mark's rows;
        // keep the header line (track position + audio output) from running under it.
        brandMark.viewTreeObserver.addOnGlobalLayoutListener { fitHeaderBesideBrand() }

        // Rounded cover: the frame's rounded background is its outline, clip the art to it.
        artFrame.clipToOutline = true
        // Android 12+: a real blur on top of the down-scaled backdrop smooths out any banding.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val r = 28f * resources.displayMetrics.density
            imgBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
        }

        // Ensure compound drawables are visible and tinted
        setupCompoundDrawables()
    }

    /**
     * The title stands on its own (largest type); artist and album get small leading icons,
     * sized to their text and tinted with a soft version of the theme accent.
     */
    private fun setupCompoundDrawables() {
        val theme = ColorThemes.current(this)
        val iconTint = androidx.core.graphics.ColorUtils.blendARGB(0xFFBBBBBB.toInt(), theme.accent, 0.55f)

        fun setTintedDrawable(tv: TextView, drawableRes: Int?, color: Int) {
            val drawable = drawableRes?.let { ContextCompat.getDrawable(this, it)?.mutate() }
            drawable?.let {
                val size = (tv.textSize * 1.05f).toInt()
                it.setBounds(0, 0, size, size)
                it.setTint(color)
            }
            tv.setCompoundDrawablesRelative(drawable, null, null, null)
        }

        setTintedDrawable(textTitle, null, 0)
        setTintedDrawable(textArtist, R.drawable.ic_artist, iconTint)
        setTintedDrawable(textAlbum, R.drawable.ic_album_art, iconTint)
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                textCurrentTime.text = formatTime(progress.toLong())
                // D-pad/arrow keys change a SeekBar's progress through its key
                // handler without ever calling onStart/onStopTrackingTouch, so the
                // only chance to apply a remote seek is here. A touch drag sets
                // isSeekBarTracking in onStartTrackingTouch and is applied once on
                // release in onStopTrackingTouch instead.
                if (!isSeekBarTracking) seekFromUser(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isSeekBarTracking = true
                // Pause the position poller so it does not fight the drag; it is
                // restarted in onStopTrackingTouch. Only the progress runnable is
                // removed — removeCallbacksAndMessages(null) also cancelled
                // unrelated one-shot posts (DAC reset retry, deferred UI refresh).
                progressUpdateRunnable?.let { handler.removeCallbacks(it) }
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeekBarTracking = false
                seekFromUser(sb?.progress?.toLong() ?: 0L)
                startProgressUpdate()
            }
        })
    }

    /**
     * Focus cue for the seek bar. The idle thumb is a plain accent dot; when the
     * D-pad lands on the bar, the dot turns white inside a soft accent halo. A
     * full-width focus pill looked heavy against the artwork.
     */
    private fun buildSeekThumb(accent: Int): Drawable {
        val density = resources.displayMetrics.density
        val halo = (26 * density).toInt()
        val dot = (15 * density).toInt()
        val inset = (halo - dot) / 2

        fun circle(color: Int, size: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(size, size)
        }

        fun glow(size: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(accent, 0x7A), Color.TRANSPARENT)
            gradientRadius = size / 2f
            setSize(size, size)
        }

        fun state(focused: Boolean): Drawable {
            val outer = if (focused) glow(halo) else circle(Color.TRANSPARENT, halo)
            val inner = circle(if (focused) Color.WHITE else accent, dot)
            return LayerDrawable(arrayOf(outer, inner)).apply {
                setLayerInset(1, inset, inset, inset, inset)
            }
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), state(true))
            addState(intArrayOf(), state(false))
        }
    }

    /** Applies a user seek for seekable items; live streams have no real position. */
    private fun seekFromUser(positionMs: Long) {
        val c = mediaController ?: return
        val dur = c.duration
        if (dur <= 0L || dur == Long.MAX_VALUE || dur == C.TIME_UNSET) return
        c.seekTo(positionMs.coerceIn(0L, dur))
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    /**
     * Opening Now Playing puts the D-pad selector on play/pause — the button the user
     * almost always wants here. Without this Android picked the first focusable view in
     * the layout, the seek bar. Posted so it wins over the window's own initial focus.
     */
    private fun focusPlayPause() {
        btnPlayPause.post { btnPlayPause.requestFocus() }
    }

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

        // "Off" look until the controller connects and updateShuffleRepeatUI/updateDacButton run.
        btnShuffle.imageAlpha = 0x8C
        btnRepeat.imageAlpha = 0x8C
        btnDacReset.imageAlpha = 0x4D

        // The focused button grows slightly (as Leanback cards do), on top of its focus ring,
        // so it is easy to find from across the room on every theme.
        val zoom = android.view.View.OnFocusChangeListener { v, hasFocus ->
            val scale = when {
                !hasFocus -> 1f
                v.id == R.id.btn_play_pause -> PLAY_FOCUS_SCALE
                else -> BUTTON_FOCUS_SCALE
            }
            v.animate().scaleX(scale).scaleY(scale)
                .setDuration(FOCUS_ZOOM_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        listOf(R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play_pause, R.id.btn_next,
               R.id.btn_repeat, R.id.btn_playlist, R.id.btn_dac_reset)
            .forEach { findViewById<ImageButton>(it)?.onFocusChangeListener = zoom }
    }

    /** Resets the audio sink via PlaybackService, with visual feedback. */
    private fun triggerDacReset() {
        val svc = PlaybackService.ensureRunning(this)
        if (svc == null) {
            // Service was killed while the app UI was closed (Android TV does
            // this). startService has been sent; onCreate sets
            // PlaybackService.instance on the main looper, so retry briefly
            // before giving up — previously this path only showed a Toast and
            // the tap appeared to do nothing.
            Toast.makeText(this, "Starting playback service…", Toast.LENGTH_SHORT).show()
            blinkDacButton()
            handler.postDelayed({
                val retried = PlaybackService.instance
                if (retried != null) doDacReset(retried)
                else Toast.makeText(this, "Playback service not running", Toast.LENGTH_SHORT).show()
            }, DAC_RESET_SERVICE_RETRY_MS)
            return
        }
        doDacReset(svc)
    }

    private fun doDacReset(svc: PlaybackService) {
        val dac = PlaybackService.findUsbAudioDevice(this)
        if (dac == null) {
            Toast.makeText(this, "No USB DAC detected", Toast.LENGTH_SHORT).show()
            blinkDacButton()
            return
        }
        Toast.makeText(this, "Resetting DAC: ${dac.productName ?: "USB Audio"}…", Toast.LENGTH_SHORT).show()
        svc.resetAudioSink()
        blinkDacButton()
    }

    /** Pulses the DAC button so every tap has visible feedback. */
    private fun blinkDacButton() {
        // "No DAC" is shown by dimming the icon (imageAlpha), so the view itself rests at 1.
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

    /** Drawable currently shown on the DAC button, so frequent refreshes don't re-inflate it. */
    private var dacButtonIconRes = 0

    /**
     * Updates DAC button: shows the icon of the active audio output mode, applies
     * theme color tint and dims when no DAC is present.
     */
    private fun updateDacButton() {
        val iconRes = PlaybackService.currentAudioOutputIconRes(this)
        if (iconRes != dacButtonIconRes) {
            btnDacReset.setImageResource(iconRes)
            dacButtonIconRes = iconRes
        }
        val dacPresent = PlaybackService.findUsbAudioDevice(this) != null
        val color = getThemeColor()
        btnDacReset.imageTintList = android.content.res.ColorStateList.valueOf(color)
        // Dim the icon only: dimming the whole view (alpha) also faded its focus halo,
        // making focus on this button hard to see.
        btnDacReset.imageAlpha = if (dacPresent) 255 else 0x4D
    }

    // ── Controller / playback ─────────────────────────────────────────────────

    private fun setupController() {
        val controller = mediaController ?: return
        applyWaveformPreference()
        animatedWaveform.setColor(getThemeColor())
        applyColorTheme()

        // Store the listener so it can be removed in onDestroy (BUG-11).
        val listener = object : Player.Listener {
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
        }
        playerListener = listener
        controller.addListener(listener)

        updateUI()
        startProgressUpdate()
    }

    // ── Screensaver ───────────────────────────────────────────────────────────

    private fun refreshScreensaver() {
        if (!isScreensaverActive) return
        window.decorView.findViewWithTag<TextView>("screensaver_text")?.let { updateScreensaverText(it) }
        val wv = window.decorView.findViewWithTag<AnimatedWaveformView>("screensaver_waveform")
        if (WaveformSettings.isDisabled(this)) {
            wv?.visibility = android.view.View.GONE
        } else {
            wv?.visibility = android.view.View.VISIBLE
            wv?.setPlaying(mediaController?.isPlaying == true)
            wv?.setWaveformType(WaveformSettings.type(this))
            wv?.setColor(getThemeColor())
        }
    }

    override fun onScreensaverCreated(container: android.view.ViewGroup) {
        if (WaveformSettings.isDisabled(this)) return
        val dp = resources.displayMetrics.density
        AnimatedWaveformView(this).apply {
            tag = "screensaver_waveform"
            layoutParams = android.widget.LinearLayout.LayoutParams((400 * dp).toInt(), (64 * dp).toInt())
                .also { it.topMargin = (24 * dp).toInt() }
            setPlaying(mediaController?.isPlaying == true)
            setWaveformType(WaveformSettings.type(this@NowPlayingActivity))
            setColor(getThemeColor())
            container.addView(this)
        }
    }

    @OptIn(UnstableApi::class)
    override fun updateScreensaverText(textView: TextView) {
        val controller = mediaController ?: return
        val metadata   = controller.mediaMetadata
        val itemMeta   = controller.currentMediaItem?.mediaMetadata

        // Same track/artist/album resolution as the full screen and the card.
        val info = TrackInfoResolver.resolve(controller, PlaybackService.icyInfo)
        val title  = info.track
        var artist = info.artist
        val album  = info.album

        val mediaId  = controller.currentMediaItem?.mediaId ?: ""
        val icy      = PlaybackService.icyInfo
        val station  = metadata.station?.toString()?.takeIf { it.isNotBlank() }
            ?: itemMeta?.station?.toString()?.takeIf { it.isNotBlank() }
            ?: icy?.takeIf { it.mediaId == mediaId }?.station
        val isStream = mediaId.startsWith("http://") || mediaId.startsWith("https://")
        // Streams: never use the live extractor tags (the stream's own junk);
        // the station name or host is the artist until the ICY song title arrives.
        if (isStream && (artist.isBlank() || artist == "Unknown Artist"))
            artist = station ?: TrackInfoResolver.streamHost(mediaId) ?: ""

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
        // Skip the artist row when the artist is already part of the title
        // (e.g. station "Solar Radio High" in title "Solar Radio High [256kbps]")
        if (artist.isNotBlank() && artist != title && !title.startsWith(artist)) appendRow(artist, R.drawable.ic_artist)
        if (album.isNotBlank() && album != title && album != artist) appendRow(album, R.drawable.ic_album_art)

        // The station name is already shown as the album row for streams — don't
        // repeat it in the parentheses (BUG: duplicate items in the screensaver).
        if (!station.isNullOrBlank() && station != artist && station != title && station != album) {
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

    /** Theme id whose surfaces are currently applied, so frequent UI refreshes stay cheap. */
    private var appliedThemeId = -1

    /**
     * Applies the colour theme: accent-wash background gradient, transport-button focus
     * halos, the solid accent play/pause disc, seek bar and waveform. No-op when unchanged.
     */
    private fun applyColorTheme() {
        val theme = ColorThemes.current(this)
        if (theme.id == appliedThemeId) return
        appliedThemeId = theme.id

        window.decorView.setBackgroundColor(theme.backgroundDeep)
        findViewById<android.view.View>(R.id.now_playing_root)?.background =
            ColorThemes.nowPlayingBackground(theme)

        listOf(R.id.btn_shuffle, R.id.btn_prev, R.id.btn_play_pause, R.id.btn_next,
               R.id.btn_repeat, R.id.btn_playlist, R.id.btn_dac_reset)
            .forEach { id ->
                findViewById<ImageButton>(id)?.apply {
                    backgroundTintList = null
                    background = ColorThemes.controlButtonBackground(
                        this@NowPlayingActivity, theme, filled = id == R.id.btn_play_pause)
                }
            }
        btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(theme.onAccent)
        brandMark.applyTheme(theme)

        seekBar.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        seekBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
        seekBar.thumbTintList = null
        seekBar.thumb = buildSeekThumb(theme.accent)

        animatedWaveform.setColor(theme.accent)
        backdropScrim.background = ColorThemes.nowPlayingScrim(theme)
        setupCompoundDrawables()
        // Re-tint the placeholder, or re-decide the backdrop (Pure Black themes have none).
        currentArt?.let { showBackdrop(it) } ?: showArtPlaceholder()
        updateShuffleRepeatUI()
        updateDacButton()
    }

    @OptIn(UnstableApi::class)
    private fun updateUI() {
        val controller = mediaController ?: return

        applyColorTheme()

        val metadata  = controller.mediaMetadata
        val mediaItem = controller.currentMediaItem
        val itemMeta  = mediaItem?.mediaMetadata
        val artist       = metadata.artist?.toString()       ?: itemMeta?.artist?.toString()
        val albumArtist  = metadata.albumArtist?.toString()  ?: itemMeta?.albumArtist?.toString()

        // Shared with the main-screen Now Playing card so both always agree.
        val info = TrackInfoResolver.resolve(controller, PlaybackService.icyInfo)
        var dispArtist = info.artist
        var dispTrack  = info.track

        // ICY streams: the item's static title takes precedence over the in-band
        // StreamTitle in Player.mediaMetadata, so prefer the service-captured info.
        val mediaId  = mediaItem?.mediaId ?: ""
        val icy      = PlaybackService.icyInfo
        val icyForItem = icy?.takeIf { it.mediaId == mediaId }
        val icyDesc    = icyForItem?.description
        val station    = metadata.station?.toString()?.takeIf { it.isNotBlank() }
                         ?: itemMeta?.station?.toString()?.takeIf { it.isNotBlank() }
                         ?: icyForItem?.station
        val genre      = metadata.genre?.toString()?.takeIf { it.isNotBlank() }
                         ?: icyForItem?.genre?.takeIf { it.isNotBlank() }

        textTitle.text  = dispTrack
        textArtist.text = dispArtist

        val finalAlbum = info.album
        // INVISIBLE (not GONE) so the album row keeps its space — the seek bar and
        // controls below must not shift when stream metadata adds/removes the album.
        if (!finalAlbum.isNullOrEmpty()) { textAlbum.text = finalAlbum; textAlbum.visibility = android.view.View.VISIBLE }
        else textAlbum.visibility = android.view.View.INVISIBLE

        updateHeaderLine()

        val uri = mediaItem?.mediaId ?: ""
        loadAlbumArt(uri, dispArtist, metadata.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString(), dispTrack)

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
        val metaList = mutableListOf<String>()
        val icyName = station ?: albumArtist
        if (!icyName.isNullOrBlank() && icyName != artist && icyName != dispArtist && icyName != dispTrack) metaList.add(icyName)
        if (!genre.isNullOrBlank() && genre != icyName && genre != dispArtist && genre != dispTrack) metaList.add(genre)
        val formatList = format?.let { formatInfoParts(it) } ?: emptyList()

        if (metaList.isNotEmpty() || formatList.isNotEmpty()) {
            // Pills: neutral for station/genre, accent-tinted for the audio format.
            textBitrate.ellipsize = null
            textBitrate.text = NowPlayingStyle.chips(metaList, formatList, ColorThemes.current(this), resources.displayMetrics.density)
        } else {
            textBitrate.ellipsize = android.text.TextUtils.TruncateAt.END
            textBitrate.setTextColor(0xFF888888.toInt())
            textBitrate.text = when (controller.playbackState) {
                Player.STATE_BUFFERING -> "Buffering…"; Player.STATE_IDLE -> "Idle"
                Player.STATE_ENDED -> "Playback ended"; else -> "Detecting format…"
            }
        }
    }

    /**
     * The small header above the title: "TRACK 3 OF 12 · USB AUDIO DRIVER". The
     * second half is the audio output in use (PlaybackService.outputPathLabel) — the
     * mode's name, or the real path when playback fell back from it.
     */
    private fun updateHeaderLine() {
        val controller = mediaController ?: return
        val parts = mutableListOf<String>()
        if (controller.mediaItemCount > 0) {
            parts += "TRACK ${controller.currentMediaItemIndex + 1} OF ${controller.mediaItemCount}"
        }
        parts += PlaybackService.outputPathLabel(this, controller.isPlaying)
        val text = parts.joinToString("  ·  ").uppercase()
        if (textPlaylistPos.text.toString() != text) textPlaylistPos.text = text
    }

    /** Caps the header's width at the brand mark's left edge when they share rows. */
    private fun fitHeaderBesideBrand() {
        if (brandMark.width == 0 || textPlaylistPos.height == 0) return
        val header = IntArray(2).also { textPlaylistPos.getLocationInWindow(it) }
        val brand = IntArray(2).also { brandMark.getLocationInWindow(it) }
        val gap = (16 * resources.displayMetrics.density).toInt()
        val sharesRows = header[1] < brand[1] + brandMark.height + gap &&
            header[1] + textPlaylistPos.height > brand[1]
        val limit = if (sharesRows) (brand[0] - header[0] - gap).coerceAtLeast(gap * 6) else Int.MAX_VALUE
        if (textPlaylistPos.maxWidth != limit) textPlaylistPos.maxWidth = limit
    }

    // ── Album art ─────────────────────────────────────────────────────────────

    /**
     * Fetches album art and displays it in imgTrackIcon.
     * Skips network if artist+album key is unchanged since last successful fetch.
     *
     * Priority:
     *  1. Embedded artwork in MediaMetadata.artworkData (ExoPlayer reads ID3/FLAC tags)
     *  2. Local folder artwork (folder.jpg, cover.jpg, etc.)
     *  3. MusicBrainz release search → Cover Art Archive
     *  4. iTunes Search API
     *  5. Fallback: ic_audio icon tinted with theme color
     */
    private fun loadAlbumArt(uri: String, artist: String, album: String?, track: String) {
        // SACD tracks carry their source ISO/share after the encoded mediaId fields;
        // folder artwork lives beside that file, not beside the "sacd:" pseudo-URI.
        val sourceUri = SacdSupport.parseTrackInfo(uri)?.srcUri ?: uri
        // The cache key must include the track's source folder, not just its tags.
        // Multi-disc releases (and duplicate rips of the same album in different
        // folders) commonly share identical artist/album metadata across discs while
        // each disc's folder has its own cover.jpg — keying on metadata alone meant
        // the first disc's art (or a stale online match) stuck for every later disc,
        // showing the wrong disc's cover once playback moved on.
        val artKey = "$artist|${album ?: track}|${folderKeyFor(sourceUri)}"
        if (artKey == lastArtKey) return
        lastArtKey = artKey

        // 1 — Embedded artwork from ExoPlayer (ID3v2 APIC / FLAC PICTURE tag)
        val artworkData = mediaController?.mediaMetadata?.artworkData
            ?: mediaController?.currentMediaItem?.mediaMetadata?.artworkData
        if (artworkData != null && artworkData.isNotEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
            if (bmp != null) {
                showArt(bmp)
                return
            }
        }

        // Placeholder while loading
        showArtPlaceholder()

        if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) return

        // Capture key so closure can detect stale results after track change
        val capturedKey = artKey

        Thread {
            // 2 — Local folder artwork
            var bitmap = fetchLocalFolderArt(sourceUri)

            if (bitmap == null) {
                val sArtist = sanitizeForSearch(artist)
                val sAlbum  = album?.let { sanitizeForSearch(it) }
                val sTrack  = sanitizeForSearch(track)

                // 3 & 4 — Online search with sanitized metadata
                bitmap = fetchFromMusicBrainz(sArtist, sAlbum, sTrack)
                    ?: fetchFromItunes(sArtist, sAlbum ?: sTrack)
            }

            runOnUiThread {
                if (capturedKey != lastArtKey) return@runOnUiThread   // track changed while fetching
                if (bitmap != null) showArt(bitmap) else showArtPlaceholder()
            }
        }.start()
    }

    /** Shows a cover: fades it in and paints the blurred backdrop from it. */
    private fun showArt(bmp: Bitmap) {
        currentArt = bmp
        imgTrackIcon.background = null
        imgTrackIcon.setPadding(0, 0, 0, 0)
        imgTrackIcon.scaleType = ImageView.ScaleType.CENTER_CROP
        imgTrackIcon.clearColorFilter()
        imgTrackIcon.setImageBitmap(bmp)
        imgTrackIcon.alpha = 0f
        imgTrackIcon.animate().alpha(1f).setDuration(ART_FADE_MS).start()
        showBackdrop(bmp)
    }

    /** No cover: a theme-tinted tile with a note icon, and no backdrop. */
    private fun showArtPlaceholder() {
        currentArt = null
        val theme = ColorThemes.current(this)
        val pad = (84 * resources.displayMetrics.density).toInt()
        imgTrackIcon.animate().cancel()
        imgTrackIcon.alpha = 1f
        imgTrackIcon.background = ColorThemes.cardImageBackground(theme)
        imgTrackIcon.setPadding(pad, pad, pad, pad)
        imgTrackIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        imgTrackIcon.setImageResource(R.drawable.ic_audio)
        imgTrackIcon.setColorFilter(theme.accent)
        hideBackdrop()
    }

    /** Full-screen blurred cover under a scrim; skipped on Pure Black (OLED) themes. */
    private fun showBackdrop(bmp: Bitmap) {
        if (ColorThemes.current(this).pureBlack) { hideBackdrop(); return }
        val ambient = try { NowPlayingStyle.ambientBitmap(bmp) } catch (e: Exception) { null }
        if (ambient == null) { hideBackdrop(); return }
        imgBackdrop.setImageBitmap(ambient)
        backdropScrim.visibility = android.view.View.VISIBLE
        imgBackdrop.visibility = android.view.View.VISIBLE
        imgBackdrop.animate().alpha(BACKDROP_ALPHA).setDuration(BACKDROP_FADE_MS).start()
    }

    private fun hideBackdrop() {
        imgBackdrop.animate().cancel()
        imgBackdrop.alpha = 0f
        imgBackdrop.visibility = android.view.View.GONE
        imgBackdrop.setImageDrawable(null)
        backdropScrim.visibility = android.view.View.GONE
    }

    private fun sanitizeForSearch(text: String): String {
        return text.replace(CLEANUP_REGEX, "").trim()
    }

    /**
     * Identifies the folder a track lives in, so the art cache/dedup key (see
     * [loadAlbumArt]) distinguishes tracks that share artist/album tags but live in
     * different folders — e.g. a "Disc 1"/"Disc 2" box-set split, where each disc's
     * folder has its own cover.jpg. Falls back to the full URI (e.g. for http(s)
     * streams, where there is no folder to speak of and each source is distinct anyway).
     */
    private fun folderKeyFor(uriString: String): String {
        return try {
            when {
                uriString.startsWith("smb://") -> {
                    uriString.substringBeforeLast("/", uriString)
                }
                uriString.startsWith("file://") || uriString.startsWith("/") -> {
                    java.io.File(localPathFor(uriString)).parent ?: uriString
                }
                uriString.startsWith("content://") -> {
                    val uri = uriString.toUri()
                    if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                        val docId = android.provider.DocumentsContract.getDocumentId(uri)
                        if (docId.contains("/")) "${uri.authority}:${docId.substringBeforeLast("/")}" else uriString
                    } else uriString
                }
                else -> uriString
            }
        } catch (e: Exception) {
            uriString
        }
    }

    /**
     * Filesystem path of a `file://` (percent-encoded) or bare-path URI. Decoding is
     * essential: `Uri.fromFile()` escapes spaces and non-ASCII characters, so the raw
     * URI text contains `%20` and `File(...)` would look for a folder literally named
     * `My%20Music` — which never exists, so no folder art was ever found.
     */
    private fun localPathFor(uriString: String): String =
        if (uriString.startsWith("file://")) {
            uriString.toUri().path ?: uriString.substring(7)
        } else {
            uriString
        }

    private fun fetchLocalFolderArt(uriString: String): Bitmap? {
        try {
            if (uriString.startsWith("smb://")) {
                val smbFile = SmbFile(uriString, SmbContext.getContextForUri(uriString))
                val parent = smbFile.parent ?: return null
                for (name in FOLDER_ART_NAMES) {
                    try {
                        val artFile = SmbFile(parent + name, SmbContext.getContextForUri(uriString))
                        if (artFile.exists()) {
                            artFile.getInputStream().use { return BitmapFactory.decodeStream(it) }
                        }
                    } catch (_: Exception) {}
                }
            } else if (uriString.startsWith("file://") || uriString.startsWith("/")) {
                val file = java.io.File(localPathFor(uriString))
                val parent = file.parentFile ?: return null
                for (name in FOLDER_ART_NAMES) {
                    val artFile = java.io.File(parent, name)
                    if (artFile.exists()) {
                        return BitmapFactory.decodeFile(artFile.absolutePath)
                    }
                }
            } else if (uriString.startsWith("content://")) {
                val uri = uriString.toUri()
                if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    if (docId.contains("/")) {
                        val parentId = docId.substringBeforeLast("/")
                        val authority = uri.authority ?: return null
                        val treeId = if (docId.contains(":")) docId.substringBefore(":") else ""
                        for (name in FOLDER_ART_NAMES) {
                            try {
                                val childId = if (parentId.endsWith("/")) "$parentId$name" else "$parentId/$name"
                                val childUri = android.provider.DocumentsContract.buildDocumentUri(authority, childId)
                                contentResolver.openInputStream(childUri)?.use { return BitmapFactory.decodeStream(it) }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("AlbumArt", "Local folder art failed: ${e.message}")
        }
        return null
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
        // Use resp.use{} so the ResponseBody is always closed, including on
        // non-2xx responses where body?.string() would otherwise leave the
        // connection open and exhaust the OkHttp pool (BUG-9).
        return artHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    /** GET request returning response body as ByteArray, or null on non-2xx. */
    private fun artGetBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "BitperfectPlayer/2.3 (https://github.com/antoxa78/BitperfectPlayer)")
            .build()
        return artHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /** Escapes Lucene special characters for MusicBrainz query strings. */
    private fun String.mbEscape() = this.replace("\"", "\\\"").take(100)

    private fun updateShuffleRepeatUI() {
        val c = mediaController ?: return
        // On = accent colour at full opacity, off = dimmed white, so the state reads at a glance.
        // Only the icon is dimmed (imageAlpha): fading the whole view also faded the focus
        // halo, so focus on an "off" button was barely visible.
        val accent = android.content.res.ColorStateList.valueOf(getThemeColor())
        val off = android.content.res.ColorStateList.valueOf(Color.WHITE)
        fun style(button: ImageButton, on: Boolean) {
            button.imageTintList = if (on) accent else off
            button.imageAlpha = if (on) 255 else 0x8C
        }
        style(btnShuffle, c.shuffleModeEnabled)
        when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> btnRepeat.setImageResource(R.drawable.ic_repeat)
            Player.REPEAT_MODE_ALL -> btnRepeat.setImageResource(R.drawable.ic_repeat)
            Player.REPEAT_MODE_ONE -> btnRepeat.setImageResource(R.drawable.ic_repeat_one)
        }
        style(btnRepeat, c.repeatMode != Player.REPEAT_MODE_OFF)
    }

    // ── Progress bar ──────────────────────────────────────────────────────────

    private fun startProgressUpdate() {
        // Cancel any existing loop before starting a new one so there is never
        // more than one concurrent progress-update Runnable (BUG-10).
        progressUpdateRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val c = mediaController ?: return
                val pos = c.currentPosition; val dur = c.duration
                textCurrentTime.text = formatTime(pos)
                if (dur > 0 && dur != Long.MAX_VALUE && dur != C.TIME_UNSET) {
                    seekBar.max = dur.toInt(); seekBar.progress = pos.toInt(); textTotalTime.text = formatTime(dur)
                } else { seekBar.max = 100; seekBar.progress = 0; textTotalTime.text = "∞" }

                // ICY stream titles change without firing onMediaMetadataChanged (the
                // item's static title masks them), so poll the service-captured title.
                val icy = PlaybackService.icyInfo
                if (icy != lastIcyInfo) { lastIcyInfo = icy; updateUI(); refreshScreensaver() }
                // The output path can change without a player event (the USB driver
                // claims the DAC a moment after play, or falls back), so poll it.
                updateHeaderLine()

                handler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
        progressUpdateRunnable = runnable
        handler.post(runnable)
    }

    private fun formatTime(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ── Format helpers ────────────────────────────────────────────────────────

    /**
     * The bit-perfect modes feed the source rate straight through, so the decoded
     * sample rate is what the DAC receives.
     */
    @OptIn(UnstableApi::class)
    private fun displaySampleRate(f: Format): Int {
        if (f.sampleRate == Format.NO_VALUE || f.sampleRate <= 0) return -1
        return f.sampleRate
    }

    @OptIn(UnstableApi::class)
    private fun formatInfoParts(f: Format): List<String> {
        val p = mutableListOf<String>()
        // DSD sources label themselves ("DSD64 DoP" / "DSD64 → PCM"): the PCM
        // rate/bits alone would misdescribe a DoP stream.
        f.label?.takeIf { it.startsWith("DSD") }?.let { p.add(it) }
        if (f.bitrate != Format.NO_VALUE && f.bitrate > 0) p.add("${f.bitrate / 1000} kbps")
        val sr = displaySampleRate(f)
        if (sr > 0) p.add(formatSampleRate(sr))
        val bd = when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; C.ENCODING_PCM_FLOAT -> "Float"; else -> "" }
        if (bd.isNotEmpty()) p.add(bd)
        return p
    }
    /** 44100 → "44.1 kHz", 96000 → "96 kHz". */
    private fun formatSampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz"
        else "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)

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
        // Open the queue at the playing track instead of always at position 1.
        val playingIndex = controller.currentMediaItemIndex
        if (playingIndex in items.indices) lv.post { lv.setSelection(playingIndex) }
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
            val it = getItem(pos) ?: return v
            v.findViewById<TextView>(R.id.item_text).text = it.name
            v.findViewById<ImageView>(R.id.item_icon).setImageResource(it.icon)
            return v
        }
    }

    /** [rootPath] is the folder the browser was opened at; Back stops there. */
    private fun browseFileStorage(path: String, isSelectionMode: Boolean, isDirectoryMode: Boolean, rootPath: String = path) {
        val dir = java.io.File(path)
        val toast = Toast.makeText(this, "Reading folder…", Toast.LENGTH_SHORT); toast.show()
        Thread {
            try {
                val sorted = (dir.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .mapNotNull { f -> when {
                        f.isDirectory -> BrowseItem(f.name, f.absolutePath, R.drawable.ic_folder, true)
                        !isDirectoryMode && isPlayable(f.name) -> BrowseItem(f.name, f.absolutePath, if (f.name.lowercase().run { endsWith(".m3u")||endsWith(".m3u8")||endsWith(".pls")||endsWith(".cue") }) R.drawable.ic_playlist else R.drawable.ic_audio, false)
                        else -> null
                    } }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                runOnUiThread {
                    toast.cancel()
                    val folderName = StorageNavigation.displayName(
                        dir.absolutePath,
                        android.os.Environment.getExternalStorageDirectory().absolutePath
                    )
                    val title = if (isDirectoryMode) "Select Directory: $folderName" else folderName
                    val dv = android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.VERTICAL; setPadding(16,16,16,16) }
                    val lv = android.widget.ListView(this).apply { adapter=BrowseAdapter(sorted); descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS; layoutParams=android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT,0,1f) }
                    dv.addView(lv)
                    val br = android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER; layoutParams=android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }
                    val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(dv).create()
                    fun btn(lbl: String, cb: () -> Unit) = android.widget.Button(this).apply { text=lbl; setOnClickListener { cb(); dlg.dismiss() }; layoutParams=android.widget.LinearLayout.LayoutParams(0,android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,1f); isFocusable=true }
                    // Up one folder; at the browser's root (internal storage or a USB drive), just close
                    fun goUp() {
                        StorageNavigation.parentWithinRoot(dir.absolutePath, rootPath)?.let {
                            browseFileStorage(it, isSelectionMode, isDirectoryMode, rootPath)
                        }
                    }
                    br.addView(btn("Back") { goUp() })
                    // Remote Back = the Back button. Back cancels the dialog on every API level
                    // (incl. predictive back); the buttons and list clicks dismiss(), not cancel().
                    dlg.setCanceledOnTouchOutside(false)
                    dlg.setOnCancelListener { goUp() }
                    if (isDirectoryMode) br.addView(btn("Add Folder") { addFilesToPlaylist(dir, false) })
                    else { br.addView(btn("Add All") { addFilesToPlaylist(dir, false) }); br.addView(btn("Replace All") { addFilesToPlaylist(dir, true) }) }
                    dv.addView(br)
                    lv.setOnItemClickListener { _, _, w, _ -> dlg.dismiss(); val it=sorted[w]; if (it.isDirectory) browseFileStorage(it.path, isSelectionMode, isDirectoryMode, rootPath) else handleFileSelection(it.path, it.name) }
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
            fun scan(f: java.io.File) {
                if (f.isDirectory) {
                    f.listFiles()?.let { k ->
                        val (dirs, files) = NaturalOrder.sortChildren(k.asList(), { it.isDirectory }, { it.name }).partition { it.isDirectory }
                        val folderStart = items.size
                        files.forEach { scan(it) }
                        if (files.none { NaturalOrder.isListFile(it.name) }) NaturalOrder.applyTagOrder(items, folderStart)
                        dirs.forEach { scan(it) }
                    }
                    return
                }
                if (!isPlayable(f.name)) return
                val lo = f.name.lowercase(); val fu = android.net.Uri.fromFile(f)
                when {
                    lo.endsWith(".iso") -> { try { val a = LocalSacdRandomAccess(f); val r = SacdSupport.buildTrackMediaItems(a, SacdSupport.AREA_STEREO, null, fu.toString()); a.close(); r.onSuccess { android.util.Log.i("SacdAdd", "local iso ${f.path} -> ${it.size} items") }; r.onFailure { android.util.Log.w("SacdAdd", "local iso ${f.path} failed", it) }; items.addAll(r.getOrDefault(emptyList())) } catch (e: Exception) { android.util.Log.w("SacdAdd", "local iso ${f.path} threw", e); e.printStackTrace() } }
                    lo.endsWith(".m3u")||lo.endsWith(".m3u8") -> { val p = try { java.io.FileInputStream(f).use { parseM3uLocal(it, fu) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    lo.endsWith(".cue") -> { val p = try { java.io.FileInputStream(f).use { PlaylistParser.parseCueFromStream(it, f.parent) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    lo.endsWith(".pls") -> { val p = try { java.io.FileInputStream(f).use { parsePlsLocal(it, fu) } } catch (e: Exception) { emptyList() }; if (p.isNotEmpty()) items.addAll(p) else items.add(buildMediaItem(f, fu)) }
                    else -> items.add(buildMediaItem(f, fu))
                }
            }
            scan(root)
            runOnUiThread {
                toast.cancel()
                if (items.isEmpty()) { Toast.makeText(this, "No music found.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val sorted = items   // already in play order: natural file-name walk, playlist/CUE/ISO order kept
                if (replace) controller.setMediaItems(sorted) else controller.addMediaItems(sorted)
                controller.prepare(); controller.play()
                Toast.makeText(this, "Added ${sorted.size} items", Toast.LENGTH_SHORT).show(); updateUI()
            }
        }.start()
    }

    // ── Playlist parsers (local) ───────────────────────────────────────────────
    // Shared with the MPD server and the menu's folder scan, so attributes after the
    // duration in #EXTINF (tvg-id=…, group-title=…) are handled the same everywhere and
    // every item carries a MIME type.

    private fun parseM3uLocal(s: java.io.InputStream, base: Uri?): List<MediaItem> =
        PlaylistParser.parseM3uFromStream(s, base?.toString()?.substringBeforeLast("/"))

    private fun parsePlsLocal(s: java.io.InputStream, base: Uri?): List<MediaItem> =
        PlaylistParser.parsePlsFromStream(s, base?.toString()?.substringBeforeLast("/"))

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun buildMediaItem(f: java.io.File, uri: Uri): MediaItem {
        val lo = f.name.lowercase()
        val mime = mimeTypeFor(lo)
        val meta = MetadataUtils.getMetadata(this, uri)
        return MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(meta.title ?: f.name.substringBeforeLast("."))
                    .setArtist(meta.artist ?: "")
                    .setAlbumTitle(meta.album ?: "")
                    .setTrackNumber(meta.trackNumber)
                    .setDiscNumber(meta.discNumber)
                    .build()
            )
            .build()
    }

    private fun mimeTypeFor(uriString: String): String? = PlaylistParser.mimeTypeFor(uriString)

    private fun isPlayable(name: String) = listOf(".mp3",".flac",".wav",".m4a",".aac",".ogg",".wma",".m3u",".m3u8",".pls",".cue",".ape",".iso",".wv",".dsf",".dff").any { name.lowercase().endsWith(it) }

    private fun getThemeColor(): Int = ColorThemes.current(this).accent
}
