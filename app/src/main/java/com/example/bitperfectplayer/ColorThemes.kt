package com.example.bitperfectplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * The player colour themes (Settings → Player Color Scheme), in one place.
 *
 * Each theme is defined by an accent and, optionally, a hand-picked near-black base; every
 * other surface (sidebar, card info area, Now Playing gradient, button states) is derived
 * from those two, so all screens stay consistent and a new theme is a single line.
 *
 * The stored preference ("color_scheme") is the theme's [Theme.id]. Ids 0-8 are the
 * original schemes and must never be renumbered — a saved choice keeps meaning the same
 * theme after an update. New themes take the next free id; the picker's grouping comes
 * from [PICKER_ORDER], not from the ids.
 */
internal object ColorThemes {
    private const val PREFS_APP = "AppSettings"
    private const val KEY_COLOR_SCHEME = "color_scheme"

    /** Neutral near-black the derived surfaces start from when a theme has no own base. */
    private const val NEUTRAL_BASE = 0xFF0B0B0F.toInt()

    class Theme(
        val id: Int,
        val name: String,
        /** Icons, waveform, seek bar, play button, focus rings. */
        val accent: Int,
        /** OLED theme: true black backgrounds, neutral surfaces, no tinted glow. */
        val pureBlack: Boolean,
        base: Int? = null,
    ) {
        private val base = base ?: NEUTRAL_BASE

        /** Main background (browse screen, Now Playing body). */
        val background: Int = if (pureBlack) Color.BLACK else blend(this.base, accent, 0.05f)

        /** Top of the Now Playing gradient — a soft wash of the accent. */
        val glow: Int = if (pureBlack) Color.BLACK else blend(this.base, accent, 0.22f)

        /** Bottom of the Now Playing gradient, a touch deeper than the background. */
        val backgroundDeep: Int = if (pureBlack) Color.BLACK else blend(this.base, Color.BLACK, 0.35f)

        /** Leanback headers sidebar: dark and accent-tinted so white header text stays readable. */
        val sidebar: Int = if (pureBlack) 0xFF111111.toInt() else blend(this.base, accent, 0.16f)

        /** Card title area, at rest and when the card has focus. */
        val cardInfo: Int = if (pureBlack) 0xFF161616.toInt() else blend(this.base, accent, 0.10f)
        val cardInfoFocused: Int =
            if (pureBlack) blend(0xFF1C1C1C.toInt(), accent, 0.22f) else blend(this.base, accent, 0.30f)

        /** Card icon area: a soft vertical wash, lighter at the top. */
        val cardImageTop: Int = if (pureBlack) 0xFF1F1F1F.toInt() else blend(this.base, accent, 0.18f)
        val cardImageBottom: Int = if (pureBlack) 0xFF181818.toInt() else blend(this.base, accent, 0.11f)

        /** Card subtitle text: muted grey with a hint of the accent. */
        val cardSubtitle: Int = blend(0xFFB4B4B4.toInt(), accent, 0.25f)

        /** Icon/text colour drawn on top of a solid accent fill (dark on light accents). */
        val onAccent: Int =
            if (ColorUtils.calculateLuminance(accent) > 0.35) NEUTRAL_BASE else Color.WHITE

        /** Light, accent-tinted text for secondary info (format / bitrate line). */
        val textTinted: Int = blend(Color.WHITE, accent, 0.35f)

        fun withAlpha(color: Int, alpha: Int): Int = ColorUtils.setAlphaComponent(color, alpha)
    }

    val ALL: List<Theme> = listOf(
        // ── Original schemes (ids 0-8 are persisted — never renumber) ──
        Theme(0, "Neon Green", 0xFF00E676.toInt(), pureBlack = false),
        Theme(1, "Electric Blue", 0xFF448AFF.toInt(), pureBlack = false),
        Theme(2, "Amber Gold", 0xFFFFC400.toInt(), pureBlack = false),
        Theme(3, "Deep Purple", 0xFF8C6BFF.toInt(), pureBlack = false),
        Theme(4, "Hot Pink", 0xFFFF4081.toInt(), pureBlack = false),
        Theme(5, "Pure Black with Grey", 0xFFA8A8A8.toInt(), pureBlack = true),
        Theme(6, "Pure Black with Red", 0xFFFF5252.toInt(), pureBlack = true),
        Theme(7, "Pure Black with Green", 0xFF43C04B.toInt(), pureBlack = true),
        Theme(8, "Pure Black with White", 0xFFF5F5F5.toInt(), pureBlack = true),
        // ── Added themes ──
        Theme(9, "Ocean Teal", 0xFF1DE9B6.toInt(), pureBlack = false, base = 0xFF061110.toInt()),
        Theme(10, "Sunset Orange", 0xFFFF7A45.toInt(), pureBlack = false, base = 0xFF120A08.toInt()),
        Theme(11, "Arctic Frost", 0xFF8FC7DA.toInt(), pureBlack = false, base = 0xFF0D1217.toInt()),
        Theme(12, "Tube Amp Glow", 0xFFFFA54A.toInt(), pureBlack = false, base = 0xFF120C07.toInt()),
        Theme(13, "Midnight Indigo", 0xFF7486FF.toInt(), pureBlack = false, base = 0xFF08091A.toInt()),
        Theme(14, "Crimson Velvet", 0xFFF0475E.toInt(), pureBlack = false, base = 0xFF13070A.toInt()),
        Theme(15, "Lime Pulse", 0xFFC6FF00.toInt(), pureBlack = false),
        Theme(16, "Rose Gold", 0xFFEDAA9C.toInt(), pureBlack = false, base = 0xFF130D0D.toInt()),
        Theme(17, "Cyber Cyan", 0xFF00E5FF.toInt(), pureBlack = false, base = 0xFF050F14.toInt()),
        Theme(18, "Pure Black with Blue", 0xFF5C9DFF.toInt(), pureBlack = true),
        Theme(19, "Pure Black with Amber", 0xFFFFB300.toInt(), pureBlack = true),
        Theme(20, "Pure Black with Purple", 0xFFB388FF.toInt(), pureBlack = true),
    )

    /** Order shown in the picker: tinted themes first, then the OLED "Pure Black" family. */
    val PICKER_ORDER: List<Int> = listOf(
        0, 9, 17, 11, 1, 13, 3, 4, 14, 10, 12, 2, 16, 15,
        5, 8, 6, 7, 18, 19, 20,
    )

    init {
        check(ALL.withIndex().all { (i, t) -> t.id == i }) { "Theme ids must equal their list index" }
        check(PICKER_ORDER.sorted() == ALL.indices.toList()) { "PICKER_ORDER must list every theme once" }
    }

    fun byId(id: Int): Theme = ALL.getOrNull(id) ?: ALL[0]

    fun currentId(context: Context): Int =
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE).getInt(KEY_COLOR_SCHEME, 0)
            .let { if (it in ALL.indices) it else 0 }

    fun current(context: Context): Theme = byId(currentId(context))

    fun select(context: Context, id: Int) {
        context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COLOR_SCHEME, id).apply()
    }

    // ── Drawables built from a theme ──────────────────────────────────────────

    /** Now Playing background: accent wash at the top fading into the deep base. */
    fun nowPlayingBackground(theme: Theme): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.glow, theme.background, theme.backgroundDeep),
        )

    /**
     * Scrim over the blurred album backdrop on Now Playing: keeps the cover's colours visible
     * at the top-left (behind the art) and darkens towards the text and controls.
     */
    fun nowPlayingScrim(theme: Theme): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                theme.withAlpha(theme.background, 0x70),
                theme.withAlpha(theme.backgroundDeep, 0xB8),
                theme.withAlpha(theme.backgroundDeep, 0xE8),
            ),
        )

    /** Icon area of a menu card (see [Theme.cardImageTop]). */
    fun cardImageBackground(theme: Theme): Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.cardImageTop, theme.cardImageBottom),
        )

    /** Browse screen background: a much fainter version of the same wash. */
    fun browseBackground(theme: Theme): Drawable =
        if (theme.pureBlack) GradientDrawable().apply { setColor(Color.BLACK) }
        else GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(blend(theme.background, theme.accent, 0.06f), theme.background, theme.backgroundDeep),
        )

    /**
     * Round transport-button background: transparent at rest, an accent halo with a ring when
     * focused, a stronger fill when pressed. [filled] makes the solid accent disc used for the
     * play/pause button, which gets a white focus ring instead so focus stays visible on it.
     */
    fun controlButtonBackground(context: Context, theme: Theme, filled: Boolean): Drawable {
        val dp = context.resources.displayMetrics.density
        val inset = (3 * dp).toInt()
        val ring = (2 * dp).toInt().coerceAtLeast(1)
        fun oval(fill: Int, stroke: Int? = null, strokeWidth: Int = ring) = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                if (stroke != null) setStroke(strokeWidth, stroke)
            },
            inset,
        )
        return StateListDrawable().apply {
            if (filled) {
                val pressed = blend(theme.accent, Color.BLACK, 0.25f)
                addState(intArrayOf(android.R.attr.state_pressed), oval(pressed, Color.WHITE, ring + ring / 2))
                addState(intArrayOf(android.R.attr.state_focused), oval(theme.accent, Color.WHITE, ring + ring / 2))
                addState(intArrayOf(), oval(theme.accent))
            } else {
                addState(intArrayOf(android.R.attr.state_pressed),
                    oval(theme.withAlpha(theme.accent, 0x66), theme.accent))
                addState(intArrayOf(android.R.attr.state_focused),
                    oval(theme.withAlpha(theme.accent, 0x38), theme.accent))
                addState(intArrayOf(), oval(Color.TRANSPARENT))
            }
        }
    }

    /**
     * Picker swatch: the theme's background tone as a ringed disc with its accent in the
     * middle; the selected theme shows a check mark on the accent.
     */
    fun swatch(context: Context, theme: Theme, selected: Boolean): Drawable = SwatchDrawable(
        backdrop = if (theme.pureBlack) Color.BLACK else theme.sidebar,
        accent = theme.accent,
        check = if (selected) MenuIcons.glyph(context, R.drawable.ic_check) else null,
        checkColor = theme.onAccent,
        strokePx = 1.5f * context.resources.displayMetrics.density,
    )

    /** Fills whatever bounds it is given (no intrinsic size), so every swatch matches. */
    private class SwatchDrawable(
        private val backdrop: Int,
        private val accent: Int,
        private val check: MenuIcons.Glyph?,
        private val checkColor: Int,
        private val strokePx: Float,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val r = min(b.width(), b.height()) / 2f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            paint.style = Paint.Style.FILL
            paint.color = backdrop
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.color = 0x55FFFFFF
            canvas.drawCircle(cx, cy, r - strokePx / 2f, paint)
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawCircle(cx, cy, r * 0.66f, paint)
            check?.let {
                val size = r * 1.05f
                val scale = size / it.viewport
                val save = canvas.save()
                canvas.translate(cx - size / 2f, cy - size / 2f)
                canvas.scale(scale, scale)
                paint.color = checkColor
                canvas.drawPath(it.path, paint)
                canvas.restoreToCount(save)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int = ColorUtils.blendARGB(from, to, ratio)
}
