package com.example.bitperfectplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.TitleViewAdapter

/*
 * Main-menu chrome: the branded title in the top-right corner and the icon headers in the
 * Leanback sidebar. Both follow the current ColorThemes theme.
 */

private fun View.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

/**
 * Replaces Leanback's plain title text with the app logo and a "BitPerfect / PLAYER" wordmark
 * whose second half is drawn in the theme accent, echoing the launcher banner.
 *
 * Installed through [MainFragment.onInflateTitleView]; it carries the
 * `browse_title_group` id and implements [TitleViewAdapter.Provider], which is all
 * BrowseSupportFragment requires of a title view. The app has no search, so the search
 * affordance is a permanently hidden view.
 */
class BrandTitleView(context: Context) : LinearLayout(context), TitleViewAdapter.Provider {

    private val wordmark = TextView(context)
    private val tagline = TextView(context)
    private val branding = LinearLayout(context)
    private val hiddenSearch = View(context).apply { visibility = View.GONE }

    private val titleAdapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View = hiddenSearch

        // The wordmark is fixed; the fragment's title string is not shown.
        override fun setTitle(titleText: CharSequence?) = Unit

        override fun updateComponentsVisibility(flags: Int) {
            branding.visibility =
                if ((flags and TitleViewAdapter.BRANDING_VIEW_VISIBLE) != 0) View.VISIBLE else View.INVISIBLE
        }
    }

    init {
        id = androidx.leanback.R.id.browse_title_group
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding(dp(48f), dp(22f), dp(48f), 0)

        branding.orientation = HORIZONTAL
        branding.gravity = Gravity.CENTER_VERTICAL

        val logo = ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher_cat)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        branding.addView(logo, LayoutParams(dp(52f), dp(52f)).apply { marginEnd = dp(10f) })

        val words = LinearLayout(context).apply { orientation = VERTICAL }
        wordmark.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
        }
        tagline.apply {
            text = "PLAYER"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.42f
            setTextColor(0xFFB0B0B0.toInt())
            includeFontPadding = false
        }
        words.addView(wordmark)
        words.addView(tagline, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3f) })
        branding.addView(words)

        addView(branding)
        addView(hiddenSearch)
        applyTheme(ColorThemes.current(context))
    }

    /** Recolours the accent half of the wordmark. */
    internal fun applyTheme(theme: ColorThemes.Theme) {
        wordmark.text = SpannableStringBuilder("BitPerfect").apply {
            setSpan(ForegroundColorSpan(theme.accent), 3, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tagline.setTextColor(theme.cardSubtitle)
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = titleAdapter
}

/**
 * Sidebar headers with an icon per section. The header that has focus gets a soft accent pill
 * and an accent icon; the others fade back, as Leanback's own headers do.
 */
class IconHeaderItemPresenter : RowHeaderPresenter() {

    private class Holder(view: View, val pill: View, val icon: ImageView, val label: TextView) :
        RowHeaderPresenter.ViewHolder(view) {
        var theme: ColorThemes.Theme? = null
        var iconTint: Int = Color.WHITE
        val pillBackground = GradientDrawable().also { pill.background = it }
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val context = parent.context
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            alpha = UNSELECTED_ALPHA
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = FitTextView(context, maxSp = 18f, minSp = 13f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        pill.addView(icon, LinearLayout.LayoutParams(root.dp(24f), root.dp(24f)).apply { marginEnd = root.dp(12f) })
        pill.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The 12dp end margin keeps the pill clear of Leanback's 12dp fade edge on the
        // sidebar's right side, which would otherwise fade out the end of long labels.
        root.addView(pill, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, root.dp(2f), root.dp(12f), root.dp(2f)) })
        return Holder(root, pill, icon, label)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val holder = viewHolder as Holder
        val header: HeaderItem? = (item as? Row)?.headerItem
        val theme = ColorThemes.current(holder.view.context)
        holder.theme = theme
        holder.label.text = header?.name
        // A fixed-width TextView may skip re-measuring on setText; FitTextView picks its
        // size in onMeasure, so a recycled header must measure again for its new label.
        holder.label.requestLayout()
        holder.view.contentDescription = header?.name
        holder.icon.setImageResource(iconFor(header))
        holder.iconTint = if (header?.id == EXIT_HEADER_ID) EXIT_TINT else theme.accent
        holder.pillBackground.cornerRadius = holder.view.dp(12f).toFloat()
        applyLevel(holder, holder.selectLevel)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val holder = viewHolder as Holder
        holder.label.text = null
        holder.icon.setImageDrawable(null)
    }

    override fun onSelectLevelChanged(holder: RowHeaderPresenter.ViewHolder) {
        (holder as? Holder)?.let { applyLevel(it, it.selectLevel) }
    }

    private fun applyLevel(holder: Holder, level: Float) {
        holder.view.alpha = UNSELECTED_ALPHA + level * (1f - UNSELECTED_ALPHA)
        val theme = holder.theme ?: return
        holder.pillBackground.setColor(ColorUtils.setAlphaComponent(theme.accent, (level * PILL_ALPHA).toInt()))
        holder.icon.setColorFilter(ColorUtils.blendARGB(0xFFDDDDDD.toInt(), holder.iconTint, 0.35f + 0.65f * level))
    }

    private fun iconFor(header: HeaderItem?): Int = when (header?.id) {
        0L -> R.drawable.ic_play_circle      // Now Playing
        1L -> R.drawable.ic_compass          // Actions (browse sources)
        2L -> R.drawable.ic_folder_music     // Local Music Library
        3L -> R.drawable.ic_network_music    // Network Shares (SMB)
        4L -> R.drawable.ic_settings         // Settings
        5L -> R.drawable.ic_history          // Recently Played
        6L -> R.drawable.ic_playlist         // Playlists
        EXIT_HEADER_ID -> R.drawable.ic_exit // Exit
        else -> R.drawable.ic_audio
    }

    /**
     * Single-line label that steps its text size down from [maxSp] towards [minSp] until the
     * text fits its width, and only ellipsizes below that. The sidebar is sized so every
     * header fits at [maxSp]; this covers a larger system font scale or a longer label.
     * (Platform autosizing is avoided: with a wrap_content height and recycled header views
     * it can stay shrunk after a long label is replaced by a short one.)
     */
    private class FitTextView(context: Context, maxSp: Float, minSp: Float) : TextView(context) {
        private val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, resources.displayMetrics)
        private val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, resources.displayMetrics)
        private val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)
        private val probe = android.graphics.Paint()

        init { setTextSize(TypedValue.COMPLEX_UNIT_PX, maxPx) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                val available = MeasureSpec.getSize(widthMeasureSpec) - totalPaddingLeft - totalPaddingRight
                val label = text?.toString().orEmpty()
                probe.set(paint)
                var size = maxPx
                probe.textSize = size
                while (size > minPx && probe.measureText(label) > available) {
                    size = (size - stepPx).coerceAtLeast(minPx)
                    probe.textSize = size
                }
                if (size != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private companion object {
        /** Same as Leanback's lb_browse_header_unselect_alpha. */
        const val UNSELECTED_ALPHA = 0.5f
        /** Maximum opacity of the focused header's accent pill. */
        const val PILL_ALPHA = 0x3A
        const val EXIT_HEADER_ID = 7L
        val EXIT_TINT = 0xFFFF5252.toInt()
    }
}
