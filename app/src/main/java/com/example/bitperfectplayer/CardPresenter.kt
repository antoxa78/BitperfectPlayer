package com.example.bitperfectplayer

import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import androidx.media3.common.MediaItem

class CardPresenter(private val onLongClickListener: ((MediaItem) -> Unit)? = null) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        return ViewHolder(cardView)
    }

    private fun getThemeColor(context: android.content.Context): Int {
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
        val index = context.getSharedPreferences("AppSettings", android.content.Context.MODE_PRIVATE)
            .getInt("color_scheme", 0)
        return if (index in colors.indices) colors[index] else colors[0]
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val mediaItem = item as? MediaItem ?: return
        val cardView = viewHolder.view as? ImageCardView ?: return
        val context = cardView.context
        val themeColor = getThemeColor(context)
        
        cardView.titleText = mediaItem.mediaMetadata.title
        cardView.contentText = mediaItem.mediaMetadata.artist ?: mediaItem.mediaMetadata.subtitle
        cardView.setMainImageDimensions(313, 176)
        cardView.mainImageView?.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        cardView.mainImageView?.setPadding(24, 24, 24, 24)
        cardView.mainImageView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Set icon based on media ID or extras
        val iconRes = when {
            mediaItem.mediaId.startsWith("action:Add Local") -> R.drawable.ic_add
            mediaItem.mediaId.startsWith("action:External Drive") -> R.drawable.ic_usb
            mediaItem.mediaId.startsWith("action:Add SMB") -> R.drawable.ic_network_music
            mediaItem.mediaId.startsWith("action:Screensaver") -> R.drawable.ic_screensaver
            mediaItem.mediaId.startsWith("action:Resume") -> R.drawable.ic_play
            mediaItem.mediaId.startsWith("action:Recent") -> R.drawable.ic_history
            mediaItem.mediaId.startsWith("action:Scan Library") -> R.drawable.ic_sync
            mediaItem.mediaId.startsWith("action:About") -> R.drawable.ic_info
            mediaItem.mediaId.startsWith("action:Music Folders") -> R.drawable.ic_audio
            mediaItem.mediaId.startsWith("action:Internal Storage") -> R.drawable.ic_storage
            mediaItem.mediaId.startsWith("smb://") -> R.drawable.ic_network_music
            mediaItem.mediaId.startsWith("content://") -> R.drawable.ic_folder_music
            mediaItem.mediaId.lowercase().endsWith(".m3u") || 
            mediaItem.mediaId.lowercase().endsWith(".m3u8") -> {
                if (mediaItem.mediaId.startsWith("content://")) R.drawable.ic_playlist_local else R.drawable.ic_playlist
            }
            mediaItem.mediaMetadata.artist?.toString()?.contains("Playlist") == true -> {
                if (mediaItem.mediaId.startsWith("content://")) R.drawable.ic_playlist_local else R.drawable.ic_playlist
            }
            mediaItem.mediaId.startsWith("action:NOW_") -> R.drawable.anim_playing
            mediaItem.mediaId.startsWith("action:Exit") -> R.drawable.ic_exit
            mediaItem.mediaId.startsWith("action:Player Color Scheme") -> R.drawable.ic_palette
            mediaItem.mediaId.startsWith("action:Waveform Type") -> R.drawable.anim_playing
            else -> R.drawable.ic_audio
        }
        val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)?.mutate()
        
        // Apply theme color to all icons except the exit button which stays red
        if (iconRes == R.drawable.ic_exit) {
            drawable?.setTint(0xFFFF5252.toInt())
        } else {
            drawable?.setTint(themeColor)
        }

        cardView.mainImage = drawable
        if (drawable is android.graphics.drawable.Animatable) {
            if (mediaItem.mediaId.startsWith("action:NOW_PAUSED:")) {
                drawable.stop()
            } else {
                drawable.start()
            }
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
}
