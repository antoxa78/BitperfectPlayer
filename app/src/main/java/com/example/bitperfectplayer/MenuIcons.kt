package com.example.bitperfectplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.SparseArray
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Icons for the Leanback menu cards.
 *
 * Leanback zooms the focused card by 14 % through the view's scale. A VectorDrawable is
 * rasterised once at the un-zoomed size and that bitmap is then stretched, so the focused
 * icon (the one the user is looking at) goes soft. These drawables draw their paths straight
 * onto the canvas instead, so they are rasterised at the final on-screen size and stay sharp
 * at any zoom.
 *
 * They have no intrinsic size: the ImageView gives them its whole content area and they
 * centre a square icon in it, optionally on a soft circular backdrop in the tint colour.
 */
internal object MenuIcons {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val cache = SparseArray<Glyph>()

    class Glyph(val path: Path, val viewport: Float)

    /** Path of a single-colour vector drawable resource, or null if it can't be read. */
    fun glyph(context: Context, resId: Int): Glyph? = synchronized(cache) {
        cache[resId] ?: parse(context, resId)?.also { cache.put(resId, it) }
    }

    private fun parse(context: Context, resId: Int): Glyph? {
        val parser = try { context.resources.getXml(resId) } catch (e: Exception) { return null }
        return try {
            val path = Path()
            var viewport = 0f
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "vector" -> viewport = parser.getAttributeFloatValue(ANDROID_NS, "viewportWidth", 0f)
                        "path" -> parser.getAttributeValue(ANDROID_NS, "pathData")?.let {
                            path.addPath(PathParser.createPathFromPathData(it))
                        }
                        // Groups/clip-paths would need transforms we don't implement: fall back.
                        "group", "clip-path" -> return null
                    }
                }
                event = parser.next()
            }
            if (viewport > 0f && !path.isEmpty) Glyph(path, viewport) else null
        } catch (e: Exception) {
            null
        } finally {
            parser.close()
        }
    }
}

/** Shared tint / alpha / backdrop handling for the card icons. */
internal abstract class CardIconDrawable(private val backdrop: Boolean) : Drawable() {
    protected val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tint: ColorStateList? = null
    private var color = Color.WHITE
    private var drawableAlpha = 255

    /** Draws the glyph centred at ([cx], [cy]) inside a square of side [size]. */
    protected abstract fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float)

    final override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = min(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (backdrop) {
            backdropPaint.color = color
            backdropPaint.alpha = Color.alpha(color) * BACKDROP_ALPHA / 255 * drawableAlpha / 255
            canvas.drawCircle(cx, cy, side / 2f, backdropPaint)
        }
        glyphPaint.color = color
        glyphPaint.alpha = Color.alpha(color) * drawableAlpha / 255
        drawGlyph(canvas, cx, cy, side * if (backdrop) GLYPH_ON_BACKDROP else GLYPH_ALONE)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateColor(state)
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateColor(state)

    private fun updateColor(state: IntArray): Boolean {
        val t = tint
        val c = t?.getColorForState(state, t.defaultColor) ?: Color.WHITE
        if (c == color) return false
        color = c
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glyphPaint.colorFilter = colorFilter
        backdropPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Backdrop circle opacity relative to the tint colour (0-255). */
        const val BACKDROP_ALPHA = 51
        /** Glyph size as a fraction of the icon square, with and without the backdrop. */
        const val GLYPH_ON_BACKDROP = 0.6f
        const val GLYPH_ALONE = 0.72f
    }
}

/** A vector-resource icon drawn as a path, sharp at any scale. */
internal class MenuIconDrawable(
    private val glyph: MenuIcons.Glyph,
    backdrop: Boolean,
) : CardIconDrawable(backdrop) {
    override fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val scale = size / glyph.viewport
        val save = canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(glyph.path, glyphPaint)
        canvas.restoreToCount(save)
    }
}

/**
 * "Now playing" equalizer: rounded bars centred on the midline, moving smoothly at ~30 fps
 * while running and settling into a low, even shape when stopped (paused).
 * Replaces the 5-frame, 5 fps animation-list whose bars jumped up and down.
 */
internal class EqualizerDrawable(backdrop: Boolean) : CardIconDrawable(backdrop), Animatable, Runnable {
    private val bar = RectF()
    private var running = false
    private val startMs = SystemClock.uptimeMillis()

    override fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val t = (SystemClock.uptimeMillis() - startMs) / 1000f
        val gap = size * 0.09f
        val w = (size - gap * (BARS - 1)) / BARS
        var x = cx - size / 2f
        for (i in 0 until BARS) {
            val h = size * if (running) level(i, t) else REST[i]
            bar.set(x, cy - h / 2f, x + w, cy + h / 2f)
            canvas.drawRoundRect(bar, w / 2f, w / 2f, glyphPaint)
            x += w + gap
        }
    }

    private fun level(i: Int, t: Float): Float {
        val a = sin(2.0 * PI * F1[i] * t + P1[i])
        val b = sin(2.0 * PI * F2[i] * t + P2[i])
        return (0.58 + 0.30 * a + 0.14 * b).toFloat().coerceIn(0.24f, 1f)
    }

    override fun start() {
        if (running) return
        running = true
        invalidateSelf()
        scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun stop() {
        if (!running) return
        running = false
        unscheduleSelf(this)
        invalidateSelf()
    }

    override fun isRunning(): Boolean = running

    override fun run() {
        invalidateSelf()
        // No callback (drawable detached) -> scheduleSelf is a no-op and the loop ends.
        if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        unscheduleSelf(this)
        if (visible && running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS)
        return changed
    }

    private companion object {
        const val BARS = 5
        const val FRAME_MS = 33L
        val REST = floatArrayOf(0.30f, 0.46f, 0.62f, 0.46f, 0.30f)
        val F1 = doubleArrayOf(1.10, 1.70, 1.30, 1.90, 1.45)
        val F2 = doubleArrayOf(2.30, 2.90, 3.30, 2.60, 3.70)
        val P1 = doubleArrayOf(0.0, 1.9, 4.1, 0.9, 2.8)
        val P2 = doubleArrayOf(1.3, 0.4, 2.2, 3.6, 5.0)
    }
}
