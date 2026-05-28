package com.example.bitperfectplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

abstract class BaseActivity : FragmentActivity() {
    protected val screensaverHandler = Handler(Looper.getMainLooper())
    protected val screensaverRunnable = Runnable { showScreensaver() }
    protected var isScreensaverActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isScreensaverActive) {
            hideScreensaver()
        }
        resetScreensaverTimer()
    }

    override fun onResume() {
        super.onResume()
        resetScreensaverTimer()
    }

    override fun onPause() {
        super.onPause()
        screensaverHandler.removeCallbacks(screensaverRunnable)
    }

    protected fun resetScreensaverTimer() {
        screensaverHandler.removeCallbacks(screensaverRunnable)
        val mins = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("screensaver_delay", 0)
        if (mins != 0) {
            val finalMins = Math.abs(mins)
            screensaverHandler.postDelayed(screensaverRunnable, finalMins * 60 * 1000L)
        }
    }

    protected open fun showScreensaver() {
        if (isFinishing || isDestroyed || isScreensaverActive) return
        if (window.decorView.findViewWithTag<android.view.View>("screensaver_overlay") != null) return
        
        val mins = getSharedPreferences("AppSettings", MODE_PRIVATE).getInt("screensaver_delay", 0)
        if (mins == 0) return

        isScreensaverActive = true
        val overlay = android.widget.FrameLayout(this)
        overlay.tag = "screensaver_overlay"
        overlay.setBackgroundColor(android.graphics.Color.BLACK)
        overlay.isClickable = true
        overlay.isFocusable = true
        
        val params = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        addContentView(overlay, params)
        
        if (mins < 0) return

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            tag = "screensaver_container"
        }
        val containerParams = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        containerParams.gravity = android.view.Gravity.CENTER
        overlay.addView(container, containerParams)

        val textView = TextView(this)
        textView.tag = "screensaver_text"
        updateScreensaverText(textView)
        textView.setTextColor(android.graphics.Color.LTGRAY)
        textView.textSize = 32f
        textView.gravity = android.view.Gravity.CENTER
        container.addView(textView)
        
        onScreensaverCreated(container)
        
        val handler = Handler(Looper.getMainLooper())
        val moveRunnable = object : Runnable {
            var dx = 2
            var dy = 2
            override fun run() {
                if (!isScreensaverActive) return
                container.translationX += dx
                container.translationY += dy
                
                val width = overlay.width
                val height = overlay.height
                if (width > 0 && height > 0) {
                    if (container.x + container.width > width || container.x < 0) dx = -dx
                    if (container.y + container.height > height || container.y < 0) dy = -dy
                }
                handler.postDelayed(this, 50)
            }
        }
        handler.post(moveRunnable)
    }

    protected open fun onScreensaverCreated(container: android.view.ViewGroup) {}

    protected open fun updateScreensaverText(textView: TextView) {
        textView.text = "Bitperfect Player"
    }

    protected fun hideScreensaver() {
        isScreensaverActive = false
        val overlay = window.decorView.findViewWithTag<android.view.View>("screensaver_overlay")
        if (overlay != null) {
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        }
    }
}
