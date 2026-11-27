package com.example.bitperfectplayer

import android.app.ActivityManager
import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.decent.usbaudio.UsbAudioDevice

/**
 * Shared recovery for the DAC after an unrelated USB attach.
 *
 * On this box `UsbAlsaManager` marks the DAC's output unavailable when the TV
 * remote's microphone is reinserted, although the kernel still has the DAC's
 * sound card. AudioPolicy then has no USB output and Idan/other apps play from
 * the TV speakers. A root xHCI unbind/bind forces a real host detach/attach that
 * makes the framework re-read the DAC.
 *
 * Used by [UsbRecoveryService] (a foreground service in its own process, so it
 * survives Exit). No-op when the opt-in host-rebind setting is off or the DAC
 * is already available.
 */
object UsbDacRecovery {

    private const val TAG = "UsbDacRecovery"
    private const val PREFS = "AppSettings"
    private const val KEY_HOST_REBIND = "usb_host_rebind_on_exit"
    private const val REBIND_GUARD_MS = 10_000L

    /**
     * Cross-process marker: UsbAttachActivity runs in the main process while the
     * recovery runs in `:usbmonitor`, so a static flag would not be visible. The
     * file lives in the app's shared internal files directory.
     */
    private const val SUPPRESS_UI_FILE = "usb_recovery_suppress_ui"

    @Volatile
    private var lastRebindAt = 0L

    fun isUiOpenSuppressed(context: Context): Boolean {
        return try {
            val until = java.io.File(context.filesDir, SUPPRESS_UI_FILE)
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?: return false
            System.currentTimeMillis() < until
        } catch (_: Throwable) {
            false
        }
    }

    fun markUiSuppressed(context: Context) {
        runCatching {
            java.io.File(context.filesDir, SUPPRESS_UI_FILE)
                .writeText((System.currentTimeMillis() + REBIND_GUARD_MS).toString())
        }
    }

    fun isHostRebindEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOST_REBIND, false)

    fun findDac(context: Context): UsbDevice? = PlaybackService.findUsbAudioDevice(context)

    /**
     * True while the playback service exists (playing, paused or in its exit
     * hand-back). In that state the driver may legitimately own the DAC and its
     * ALSA card is absent, so the monitor must not mistake that for a stale
     * route and yank the USB bus out from under playback.
     *
     * The monitor runs in :usbmonitor; getRunningServices() reports the app's
     * own services across its processes.
     */
    private fun isPlaybackServiceRunning(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true // can't tell — be conservative
            @Suppress("DEPRECATION")
            if (am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == PlaybackService::class.java.name
                }) {
                return true
            }
            // Fallback for Android versions that scope getRunningServices() to
            // the caller's process: if the main app process is actively visible
            // or running a foreground service, the player may be using the DAC.
            // A cached process is ignored so recovery still works after Exit.
            @Suppress("DEPRECATION")
            val main = am.runningAppProcesses?.firstOrNull {
                it.processName == context.packageName
            }
            main != null &&
                main.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * True when AudioPolicy exposes a USB **output** matching [expectedDacName]
     * with real capabilities. Input-only devices are never accepted.
     */
    fun isDacInAudioPolicy(context: Context, expectedDacName: String?): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            val isUsb = device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            if (!isUsb || !device.isSink || device.sampleRates.isEmpty()) {
                false
            } else if (expectedDacName.isNullOrBlank()) {
                true
            } else {
                val product = device.productName?.toString()?.trim().orEmpty()
                // Empty productName: isSink + sampleRates already excludes the
                // input-only microphone, so accept it.
                product.isEmpty() ||
                    product.contains(expectedDacName, ignoreCase = true) ||
                    expectedDacName.contains(product, ignoreCase = true)
            }
        }
    }

    /**
     * Blocks the calling background thread while it gives UsbAlsaManager time to
     * settle, then re-enumerates the host controller when the DAC is present on
     * USB but absent from AudioPolicy.
     *
     * @return true when a rebind was performed and the DAC became available.
     */
    fun recoverIfNeeded(context: Context, settleMs: Long = 1500L): Boolean {
        try {
            if (settleMs > 0) Thread.sleep(settleMs)
            if (SystemClock.elapsedRealtime() - lastRebindAt < REBIND_GUARD_MS) {
                Log.i(TAG, "Skipping recovery — a host rebind just ran")
                return false
            }
            if (isPlaybackServiceRunning(context)) {
                Log.i(TAG, "PlaybackService is running — leaving the DAC to the player")
                return false
            }
            val dac = findDac(context) ?: return false
            val name = dac.productName
            if (isDacInAudioPolicy(context, name)) {
                Log.i(TAG, "DAC '$name' already available to AudioPolicy — no recovery needed")
                return false
            }
            if (!isHostRebindEnabled(context)) {
                Log.w(TAG, "DAC '$name' missing from AudioPolicy, but host rebind is off")
                return false
            }
            // Re-check immediately before the destructive step: PlaybackService
            // may have started (and claimed the DAC) since the first check.
            if (isPlaybackServiceRunning(context)) {
                Log.i(TAG, "PlaybackService started during the check — not rebinding")
                return false
            }
            Log.w(TAG, "DAC '$name' present on USB but missing from AudioPolicy — " +
                "re-enumerating the host controller")
            lastRebindAt = SystemClock.elapsedRealtime()
            // The rebind re-attaches the DAC and the microphone; do not let the
            // resulting UsbAttachActivity instances pull the player UI over Idan.
            // This marker is read across processes (monitor → main).
            markUiSuppressed(context)
            val forced = UsbAudioDevice.getInstance(context).forceHostControllerRebind(name)
            if (!forced) {
                Log.w(TAG, "Host-controller rebind failed (root required)")
                return false
            }
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (!isDacInAudioPolicy(context, name) &&
                SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(150)
            }
            val restored = isDacInAudioPolicy(context, name)
            Log.i(TAG, "DAC '$name' restored after host rebind: $restored")
            return restored
        } catch (t: Throwable) {
            Log.w(TAG, "USB DAC recovery failed: ${t.message}")
            return false
        }
    }
}
