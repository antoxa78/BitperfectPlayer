package com.example.bitperfectplayer

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import kotlin.math.abs

abstract class BaseActivity : FragmentActivity() {

    companion object {
        private const val PREFS_APP           = "AppSettings"
        private const val KEY_SCREENSAVER     = "screensaver_delay"
        private const val SCREENSAVER_TAG     = "screensaver_overlay"
        private const val SCREENSAVER_TEXT_TAG   = "screensaver_text"
        private const val SCREENSAVER_CONT_TAG   = "screensaver_container"
        private const val BOUNCE_STEP_PX      = 2
        private const val BOUNCE_INTERVAL_MS  = 50L
        private const val TEXT_SIZE_SP        = 32f
    }

    protected val screensaverHandler  = Handler(Looper.getMainLooper())
    protected val screensaverRunnable = Runnable { showScreensaver() }
    protected var isScreensaverActive  = false

    // Bounce animation handler kept as a field so it can be cancelled when the
    // screensaver is hidden or the activity is destroyed (BUG-15).
    private val bounceHandler = Handler(Looper.getMainLooper())

    // Keep the TV box out of standby while music plays. On Android TV the
    // playback service's PARTIAL_WAKE_LOCK does not stop the normal inactivity
    // timeout (system screensaver → "Put device to sleep") — only a visible
    // window with FLAG_KEEP_SCREEN_ON (or a screen-level wake lock) does. The
    // flag is set only while PlaybackService reports active playback; the
    // app's own screensaver overlay still blanks the panel against burn-in.
    // (Android TV's separate inattentive-sleep timer ignores this flag — the
    // service handles that one itself, see PlaybackService.suppressAttentiveSleep.)
    private val keepAwakeListener: (Boolean) -> Unit = { awake -> applyKeepScreenOn(awake) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.addKeepAwakeListener(keepAwakeListener)
    }

    override fun onStop() {
        PlaybackService.removeKeepAwakeListener(keepAwakeListener)
        super.onStop()
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        // Stop the bounce animation and screensaver timer so the handlers do not
        // fire on a dead window after the activity is torn down (BUG-16).
        hideScreensaver()
        screensaverHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isScreensaverActive) hideScreensaver()
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
        val mins = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_SCREENSAVER, 0)
        if (mins != 0) {
            screensaverHandler.postDelayed(screensaverRunnable, abs(mins) * 60_000L)
        }
    }

    protected open fun showScreensaver() {
        if (isFinishing || isDestroyed || isScreensaverActive) return
        if (window.decorView.findViewWithTag<View>(SCREENSAVER_TAG) != null) return

        val mins = getSharedPreferences(PREFS_APP, MODE_PRIVATE).getInt(KEY_SCREENSAVER, 0)
        if (mins == 0) return

        isScreensaverActive = true

        // Full-screen black overlay
        val overlay = FrameLayout(this).apply {
            tag = SCREENSAVER_TAG
            setBackgroundColor(Color.BLACK)
            isClickable  = true
            isFocusable  = true
        }
        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Screen-off mode: just a black overlay, nothing more
        if (mins < 0) return

        // Bouncing content container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            tag         = SCREENSAVER_CONT_TAG
        }
        val containerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER
        }
        overlay.addView(container, containerParams)

        val textView = TextView(this).apply {
            tag       = SCREENSAVER_TEXT_TAG
            setTextColor(Color.LTGRAY)
            textSize  = TEXT_SIZE_SP
            gravity   = Gravity.CENTER
        }
        updateScreensaverText(textView)
        container.addView(textView)

        onScreensaverCreated(container)

        // Bouncing animation — uses the class-level bounceHandler so it can be
        // cancelled by hideScreensaver() / onDestroy() (BUG-15).
        bounceHandler.removeCallbacksAndMessages(null)
        bounceHandler.post(object : Runnable {
            private var dx = BOUNCE_STEP_PX
            private var dy = BOUNCE_STEP_PX

            override fun run() {
                if (!isScreensaverActive) return
                container.translationX += dx
                container.translationY += dy

                val ow = overlay.width
                val oh = overlay.height
                if (ow > 0 && oh > 0) {
                    if (container.x + container.width > ow || container.x < 0) dx = -dx
                    if (container.y + container.height > oh || container.y < 0) dy = -dy
                }
                bounceHandler.postDelayed(this, BOUNCE_INTERVAL_MS)
            }
        })
    }

    protected open fun onScreensaverCreated(container: ViewGroup) {}

    protected open fun updateScreensaverText(textView: TextView) {
        textView.text = "Bitperfect Player"
    }

    protected fun hideScreensaver() {
        isScreensaverActive = false
        bounceHandler.removeCallbacksAndMessages(null)   // stop the bounce loop (BUG-15)
        window.decorView.findViewWithTag<View>(SCREENSAVER_TAG)?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}
