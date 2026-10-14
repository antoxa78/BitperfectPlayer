package com.example.bitperfectplayer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.media3.common.MediaItem

class CardPresenter(private val onLongClickListener: ((MediaItem) -> Unit)? = null) : Presenter() {

    companion object {
        /** Accent colours of all themes, indexed by theme id (see [ColorThemes]). */
        val THEME_COLORS: IntArray get() = ColorThemes.ALL.map { it.accent }.toIntArray()

        private val EXIT_TINT = 0xFFFF5252.toInt()

        /** Draw card icons on a soft circle in the tint colour (false = bare glyph). */
        private const val ICON_BACKDROP = true

        fun themeColorFor(context: Context): Int = ColorThemes.current(context).accent

        /** Corner radius of the cards: the row presenter's clip and the focus ring share it. */
        fun cornerRadiusPx(context: Context): Float = 8f * context.resources.displayMetrics.density
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = object : ImageCardView(parent.context) {
            // Leanback marks the focused card selected: brighten its title area in the theme tint.
            override fun setSelected(selected: Boolean) {
                super.setSelected(selected)
                applyInfoAreaColor(this, selected)
            }
        }.apply {
            isFocusable         = true
            isFocusableInTouchMode = true
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return
        val cardView  = viewHolder.view as? ImageCardView ?: return
        val context   = cardView.context
        val theme      = ColorThemes.current(context)
        val themeColor = theme.accent
        cardView.setTag(R.id.card_theme_tag, theme)
        applyInfoAreaColor(cardView, cardView.isSelected)

        cardView.titleText   = mediaItem.mediaMetadata.title
        cardView.contentText = mediaItem.mediaMetadata.artist ?: mediaItem.mediaMetadata.subtitle
        cardView.setMainImageDimensions(313, 176)
        cardView.mainImageView?.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
            // A soft theme-tinted tile behind the icon, so the card reads as one surface
            // with its title area instead of an icon floating over the page.
            background = ColorThemes.cardImageBackground(theme)
        }
        cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.setTextColor(Color.WHITE)
        cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.setTextColor(theme.cardSubtitle)

        val iconRes = resolveIconRes(context, mediaItem)
        // Path-drawn icons stay sharp when Leanback zooms the focused card; fall back to the
        // plain resource if a drawable can't be parsed as a single-colour vector.
        val drawable: Drawable? = if (mediaItem.mediaId.startsWith("action:NOW_")) {
            EqualizerDrawable(ICON_BACKDROP)
        } else {
            MenuIcons.glyph(context, iconRes)?.let { MenuIconDrawable(it, ICON_BACKDROP) }
                ?: AppCompatResources.getDrawable(context, iconRes)?.mutate()
        }

        // Exit button is always red; everything else uses the current theme colour
        drawable?.setTint(if (iconRes == R.drawable.ic_exit) EXIT_TINT else themeColor)

        cardView.mainImage = drawable

        if (drawable is Animatable) {
            if (mediaItem.mediaId.startsWith("action:NOW_PAUSED:")) drawable.stop() else drawable.start()
        }

        cardView.setOnLongClickListener {
            onLongClickListener?.invoke(mediaItem)
            true
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as? ImageCardView
        cardView?.mainImage = null
        cardView?.setOnLongClickListener(null)
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private fun applyInfoAreaColor(cardView: ImageCardView, selected: Boolean) {
        val theme = cardView.getTag(R.id.card_theme_tag) as? ColorThemes.Theme
            ?: ColorThemes.current(cardView.context)
        cardView.setInfoAreaBackgroundColor(if (selected) theme.cardInfoFocused else theme.cardInfo)
        // Accent ring around the focused card, on top of Leanback's zoom and dimmer.
        cardView.foreground = if (selected)
            ColorThemes.cardFocusRing(cardView.context, theme, cornerRadiusPx(cardView.context))
        else null
    }

    private fun resolveIconRes(context: Context, mediaItem: MediaItem): Int {
        val id = mediaItem.mediaId
        val lower = id.lowercase()
        return when {
            id.startsWith("action:USB DAC")              -> R.drawable.ic_dac
            id.startsWith("action:Audio Output")         -> PlaybackService.currentAudioOutputIconRes(context)
            id.startsWith("action:Add Local")            -> R.drawable.ic_add
            id.startsWith("action:External Drive")       -> R.drawable.ic_usb
            id.startsWith("action:Add SMB")              -> R.drawable.ic_add_network
            id.startsWith("action:Screensaver")          -> R.drawable.ic_screensaver
            id.startsWith("action:DSD Output")           -> R.drawable.ic_dsd
            id.startsWith("action:Resume Last")          -> R.drawable.ic_resume_last
            id.startsWith("action:Resume")               -> R.drawable.ic_resume_playback
            id.startsWith("action:Recent")               -> R.drawable.ic_history
            id.startsWith("action:Network Settings")     -> R.drawable.ic_network_settings
            id.startsWith("action:Scan Library")         -> R.drawable.ic_scan_library
            id.startsWith("action:About")                -> R.drawable.ic_info
            id.startsWith("action:Music Folders")        -> R.drawable.ic_folder_music
            id.startsWith("action:Internal Storage")     -> R.drawable.ic_storage
            id.startsWith("action:NOW_")                 -> R.drawable.anim_playing
            id.startsWith("action:Exit")                 -> R.drawable.ic_exit
            id.startsWith("action:Player Color Scheme")  -> R.drawable.ic_palette
            id.startsWith("action:Waveform Type")        -> R.drawable.ic_waveform
            id.startsWith("action:LAN Player Control")   -> R.drawable.ic_lan_control
            id.startsWith("smb://")                   -> R.drawable.ic_network_music
            lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls") || lower.endsWith(".cue") -> {
                if (id.startsWith("content://")) R.drawable.ic_playlist_local else R.drawable.ic_playlist
            }
            mediaItem.mediaMetadata.artist?.contains("Playlist") == true -> {
                if (id.startsWith("content://")) R.drawable.ic_playlist_local else R.drawable.ic_playlist
            }
            id.startsWith("content://")               -> R.drawable.ic_folder_music
            else                                      -> R.drawable.ic_audio
        }
    }
}
