package com.example.bitperfectplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class AnimatedWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = 0x8800E676.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setColor(newColor: Int) {
        paint.color = (newColor and 0x00FFFFFF) or 0x88000000.toInt()
        invalidate()
    }

    private var isPlaying = false
    private var waveformType = 0
    private val barCount = 40
    private val magnitudes = FloatArray(barCount)
    private val targetMagnitudes = FloatArray(barCount)
    private val peakMagnitudes = FloatArray(barCount)
    private val peakAges = IntArray(barCount)
    private val random = Random(System.currentTimeMillis())

    fun setPlaying(playing: Boolean) {
        if (this.isPlaying != playing) {
            this.isPlaying = playing
            if (playing) {
                invalidate()
            }
        }
    }

    fun setWaveformType(type: Int) {
        this.waveformType = type
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2f
        
        val barWidth = width / (barCount * 1.5f)
        val gap = barWidth / 2f
        val startX = (width - (barCount * (barWidth + gap))) / 2f

        for (i in 0 until barCount) {
            if (isPlaying) {
                // Smoothly transition to target magnitude
                if (Math.abs(magnitudes[i] - targetMagnitudes[i]) < 0.05f) {
                    targetMagnitudes[i] = 0.1f + random.nextFloat() * 0.9f
                }
                magnitudes[i] += (targetMagnitudes[i] - magnitudes[i]) * 0.15f
                
                // Update peaks
                if (magnitudes[i] > peakMagnitudes[i]) {
                    peakMagnitudes[i] = magnitudes[i]
                    peakAges[i] = 0
                } else {
                    peakAges[i]++
                    if (peakAges[i] > 15) {
                        peakMagnitudes[i] *= 0.92f
                    }
                }
            } else {
                // Settle to a flat line when paused
                magnitudes[i] *= 0.85f
                peakMagnitudes[i] *= 0.85f
            }

            val x = startX + i * (barWidth + gap)
            
            when (waveformType) {
                0 -> { // Mirrored Bars
                    val magnitudeHeight = magnitudes[i] * (height / 2f)
                    canvas.drawRoundRect(x, centerY - magnitudeHeight, x + barWidth, centerY + magnitudeHeight, barWidth / 2f, barWidth / 2f, paint)
                }
                1 -> { // Single Bars
                    val magnitudeHeight = magnitudes[i] * height
                    canvas.drawRoundRect(x, height - magnitudeHeight, x + barWidth, height, barWidth / 2f, barWidth / 2f, paint)
                }
                2 -> { // Bars with level hold
                    val magnitudeHeight = magnitudes[i] * height
                    canvas.drawRoundRect(x, height - magnitudeHeight, x + barWidth, height, barWidth / 2f, barWidth / 2f, paint)
                    // Draw peak
                    val peakY = height - (peakMagnitudes[i] * height)
                    canvas.drawRect(x, peakY, x + barWidth, peakY + 2f, paint)
                }
                3 -> { // Dots bars
                    drawDots(canvas, x, barWidth, height, magnitudes[i])
                }
                4 -> { // Dots bars with level holds
                    val dr = barWidth / 8f
                    drawDots(canvas, x, barWidth, height, magnitudes[i])
                    // Draw peak dot (same size as bar dots)
                    val peakY = height - dr - (peakMagnitudes[i] * (height - dr * 2))
                    canvas.drawCircle(x + barWidth / 2f, peakY, dr, paint)
                }
            }
        }

        if (isPlaying || magnitudes.any { it > 0.01f }) {
            postInvalidateDelayed(30)
        }
    }

    private fun drawDots(canvas: Canvas, x: Float, barWidth: Float, height: Float, magnitude: Float) {
        val dotRadius = barWidth / 8f
        val dotGap = dotRadius * 0.4f
        val maxDots = (height / (dotRadius * 2 + dotGap)).toInt()
        val activeDots = (magnitude * maxDots).toInt()
        
        for (j in 0 until activeDots) {
            val y = height - dotRadius - j * (dotRadius * 2 + dotGap)
            canvas.drawCircle(x + barWidth / 2f, y, dotRadius, paint)
        }
    }
}
