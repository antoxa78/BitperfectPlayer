package com.example.bitperfectplayer

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Invisible target of `USB_DEVICE_ATTACHED` for USB audio devices.
 *
 * Android only lets the *user* make a USB grant permanent: Android's own dialog
 * (the "Open … to handle …?" prompt on attach, and the permission dialog the
 * service raises) offers "Always open Bitperfect Player when <DAC> is connected"
 * — but only to an app with an activity whose intent filter matches the device.
 * Once it is ticked, Android stores the app as that DAC's default handler, and on
 * every later attach (a power cycle, a replug, a re-enumeration) it grants the
 * permission by itself and starts this activity — no dialog.
 *
 * This used to be MainActivity's job, which meant every DAC power cycle pulled
 * the full UI to the front. Now the attach lands here: the UI is opened only
 * when the app is not running at all (so turning the DAC on still starts the
 * player, as before); otherwise this finishes at once without showing anything,
 * and the running service picks the DAC up through its own attach receiver.
 *
 * Settings → USB DAC → "Remember DAC Permission" enables or disables this
 * component ([DacPermissionMemory]). Disabled, the app no longer matches the
 * DAC, so Android neither offers "Always open" nor grants anything on attach —
 * the permission is asked for every time the DAC (re)appears.
 *
 * Android 11 has an extra requirement: its `UsbDescriptorParser` treats every
 * UAC Input Terminal as a capture path, so it hides the "Always open" checkbox
 * for output-only DACs unless the app holds RECORD_AUDIO. Settings → USB DAC →
 * "Microphone Permission" requests that grant; the app never records audio.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        val granted = device != null && usbManager?.hasPermission(device) == true
        val running = PlaybackService.instance != null
        Log.i(TAG, "USB audio device attached via Android's USB handler: " +
            "'${device?.productName ?: device?.deviceName}' permission=$granted " +
            "serviceRunning=$running")
        if (UsbDacRecovery.isUiOpenSuppressed(this)) {
            // This attach is the result of our own host-controller recovery;
            // never pull the player UI over the app that is already playing.
            Log.i(TAG, "Suppressing UI for recovery-induced USB attach")
            finish()
            return
        }
        // Make sure the separate monitor is alive; it also runs an initial
        // recovery check, which covers a stale DAC route if this activity was
        // the first component started after the attach.
        UsbRecoveryService.sync(this)
        if (device != null && !device.hasAudioPlaybackStream()) {
            // The TV remote's USB microphone also advertises USB_CLASS_AUDIO;
            // never let it start the player or stand in for the output DAC.
            // Recovery is handled by the separate UsbRecoveryService; this
            // invisible activity only consumes the attach event.
            Log.i(TAG, "Ignoring input-only USB audio device " +
                "'${device.productName ?: device.deviceName}'")
            finish()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
            DacPermissionMemory.isEnabled(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED &&
            !micHintShown) {
            // Android 11 suppresses the USB dialog's "Always open …" checkbox for
            // every UAC device unless the app holds RECORD_AUDIO. Point the user
            // at the opt-in once per process; the dialog itself has no way to
            // explain why the checkbox is missing.
            micHintShown = true
            Toast.makeText(
                applicationContext,
                "Android 11 hides the \"Always open\" USB option until Microphone " +
                    "permission is granted: Settings → USB DAC → Microphone Permission.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (!running) {
            // Same as the old behaviour: connecting the DAC starts the player.
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    companion object {
        private const val TAG = "UsbAttach"
        @Volatile private var micHintShown = false
    }
}

/** Settings → USB DAC → "Remember DAC Permission" (the enabled state of [UsbAttachActivity]). */
object DacPermissionMemory {

    private fun component(context: Context) =
        ComponentName(context, UsbAttachActivity::class.java)

    /** On unless explicitly disabled (the manifest default is enabled). */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
