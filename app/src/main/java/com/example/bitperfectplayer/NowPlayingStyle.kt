package com.example.bitperfectplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Visual helpers for the Now Playing screen: format "chips" and the ambient album backdrop. */
internal object NowPlayingStyle {

    /** Longest text shown in one chip; longer station / genre names are shortened. */
    private const val MAX_CHIP_CHARS = 32

    /**
     * Builds the info line as rounded pills: [meta] (station, genre — neutral pills) followed
     * by [format] (codec / bitrate / sample rate / bit depth — accent-tinted pills).
     */
    fun chips(meta: List<String>, format: List<String>, theme: ColorThemes.Theme, density: Float): CharSequence {
        val sb = SpannableStringBuilder()
        fun add(text: String, span: PillSpan) {
            val label = if (text.length > MAX_CHIP_CHARS) text.take(MAX_CHIP_CHARS - 1).trimEnd() + "…" else text
            val start = sb.length
            sb.append(label)
            sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val metaSpan = { PillSpan(
            fill = 0x18FFFFFF, stroke = 0x2EFFFFFF, textColor = 0xFFDDDDDD.toInt(), density = density) }
        val formatSpan = { PillSpan(
            fill = ColorUtils.setAlphaComponent(theme.accent, 0x30),
            stroke = ColorUtils.setAlphaComponent(theme.accent, 0x8C),
            textColor = theme.textTinted, density = density) }
        meta.forEach { add(it, metaSpan()) }
        format.forEach { add(it, formatSpan()) }
        return sb
    }

    /**
     * A tiny, smoothly upscaled copy of [src] — shown full-screen behind the UI it reads as a
     * heavy blur of the cover's colours. Two-step downscale so the colours are averaged rather
     * than point-sampled. (On Android 12+ a RenderEffect blur is added on top in the view.)
     */
    fun ambientBitmap(src: Bitmap): Bitmap {
        val aspect = src.height.toFloat() / max(1, src.width)
        val midW = min(256, src.width)
        val mid = Bitmap.createScaledBitmap(src, midW, max(1, (midW * aspect).roundToInt()), true)
        val tinyW = 24
        val tiny = Bitmap.createScaledBitmap(mid, tinyW, max(1, (tinyW * aspect).roundToInt()), true)
        return Bitmap.createScaledBitmap(tiny, tinyW * 4, max(4, (tinyW * 4 * aspect).roundToInt()), true)
    }

    /** Rounded pill behind a run of text, with a gap after it so pills sit in a row. */
    class PillSpan(
        private val fill: Int,
        private val stroke: Int,
        private val textColor: Int,
        density: Float,
    ) : ReplacementSpan() {
        private val padH = 10f * density
        private val padV = 4f * density
        private val gap = 8f * density
        private val strokeWidth = 1f * density
        private val rect = RectF()

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val pfm = paint.fontMetricsInt
                fm.ascent = (pfm.ascent - padV).toInt()
                fm.top = min(pfm.top, fm.ascent)
                fm.descent = (pfm.descent + padV).toInt()
                fm.bottom = max(pfm.bottom, fm.descent)
            }
            return (paint.measureText(text, start, end) + padH * 2 + gap).roundToInt()
        }

        override fun draw(
            canvas: Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val textWidth = paint.measureText(text, start, end)
            val fm = paint.fontMetrics
            rect.set(x, y + fm.ascent - padV, x + textWidth + padH * 2, y + fm.descent + padV)
            val radius = rect.height() / 2f
            val oldColor = paint.color
            val oldStyle = paint.style
            val oldStroke = paint.strokeWidth

            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (Color.alpha(stroke) > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth
                paint.color = stroke
                rect.inset(strokeWidth / 2f, strokeWidth / 2f)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = textColor
            canvas.drawText(text, start, end, x + padH, y.toFloat(), paint)

            paint.color = oldColor
            paint.style = oldStyle
            paint.strokeWidth = oldStroke
        }
    }
}
