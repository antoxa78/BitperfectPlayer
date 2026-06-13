package com.example.bitperfectplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

class AnimatedWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_COLOR = 0x8800E676.toInt()
        private const val ALPHA_MASK    = 0x88000000.toInt()
        private const val COLOR_MASK    = 0x00FFFFFF
        private const val BAR_COUNT     = 40
        private const val FRAME_DELAY_MS = 30L
        private const val PEAK_HOLD_FRAMES = 15
        private const val PEAK_DECAY = 0.92f
        private const val MAG_LERP   = 0.15f
        private const val MAG_SNAP   = 0.05f
        private const val SETTLED_DECAY = 0.85f
        private const val MIN_ACTIVE_MAGNITUDE = 0.01f
    }

    private val paint = Paint().apply {
        color = DEFAULT_COLOR
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** Apply a new theme colour, keeping the fixed alpha overlay. */
    fun setColor(newColor: Int) {
        paint.color = (newColor and COLOR_MASK) or ALPHA_MASK
        invalidate()
    }

    private var isPlaying = false
    private var waveformType = 0
    private val magnitudes      = FloatArray(BAR_COUNT)
    private val targetMagnitudes = FloatArray(BAR_COUNT)
    private val peakMagnitudes  = FloatArray(BAR_COUNT)
    private val peakAges        = IntArray(BAR_COUNT)
    private val random          = Random(System.currentTimeMillis())

    fun setPlaying(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            if (playing) invalidate()
        }
    }

    fun setWaveformType(type: Int) {
        waveformType = type
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        val barWidth = w / (BAR_COUNT * 1.5f)
        val gap      = barWidth / 2f
        val startX   = (w - BAR_COUNT * (barWidth + gap)) / 2f

        for (i in 0 until BAR_COUNT) {
            if (isPlaying) {
                // Smoothly animate toward a random target magnitude
                if (abs(magnitudes[i] - targetMagnitudes[i]) < MAG_SNAP) {
                    targetMagnitudes[i] = 0.1f + random.nextFloat() * 0.9f
                }
                magnitudes[i] += (targetMagnitudes[i] - magnitudes[i]) * MAG_LERP

                // Peak hold / decay
                if (magnitudes[i] > peakMagnitudes[i]) {
                    peakMagnitudes[i] = magnitudes[i]
                    peakAges[i] = 0
                } else {
                    peakAges[i]++
                    if (peakAges[i] > PEAK_HOLD_FRAMES) {
                        peakMagnitudes[i] *= PEAK_DECAY
                    }
                }
            } else {
                // Settle to a flat line when paused
                magnitudes[i]     *= SETTLED_DECAY
                peakMagnitudes[i] *= SETTLED_DECAY
            }

            val x = startX + i * (barWidth + gap)

            when (waveformType) {
                0 -> { // Mirrored bars
                    val mh = magnitudes[i] * (h / 2f)
                    canvas.drawRoundRect(x, centerY - mh, x + barWidth, centerY + mh, barWidth / 2f, barWidth / 2f, paint)
                }
                1 -> { // Single bars
                    val mh = magnitudes[i] * h
                    canvas.drawRoundRect(x, h - mh, x + barWidth, h, barWidth / 2f, barWidth / 2f, paint)
                }
                2 -> { // Bars with peak-hold line
                    val mh = magnitudes[i] * h
                    canvas.drawRoundRect(x, h - mh, x + barWidth, h, barWidth / 2f, barWidth / 2f, paint)
                    val peakY = h - peakMagnitudes[i] * h
                    canvas.drawRect(x, peakY, x + barWidth, peakY + 2f, paint)
                }
                3 -> { // Dot bars
                    drawDots(canvas, x, barWidth, h, magnitudes[i])
                }
                4 -> { // Dot bars with peak-hold dot
                    val dr = barWidth / 8f
                    drawDots(canvas, x, barWidth, h, magnitudes[i])
                    val peakY = h - dr - peakMagnitudes[i] * (h - dr * 2f)
                    canvas.drawCircle(x + barWidth / 2f, peakY, dr, paint)
                }
            }
        }

        if (isPlaying || magnitudes.any { it > MIN_ACTIVE_MAGNITUDE }) {
            postInvalidateDelayed(FRAME_DELAY_MS)
        }
    }

    private fun drawDots(canvas: Canvas, x: Float, barWidth: Float, height: Float, magnitude: Float) {
        val dotRadius = barWidth / 8f
        val dotGap    = dotRadius * 0.4f
        val maxDots   = (height / (dotRadius * 2f + dotGap)).toInt()
        val activeDots = (magnitude * maxDots).toInt()

        for (j in 0 until activeDots) {
            val y = height - dotRadius - j * (dotRadius * 2f + dotGap)
            canvas.drawCircle(x + barWidth / 2f, y, dotRadius, paint)
        }
    }
}
