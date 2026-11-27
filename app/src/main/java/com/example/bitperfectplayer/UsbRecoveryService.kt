package com.example.bitperfectplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Small always-on foreground service that keeps the USB DAC usable after an
 * unrelated USB attach (the TV remote's microphone).
 *
 * It runs in its own `:usbmonitor` process so the player's Exit path
 * (`System.exit(0)`) does not take it down with the playback process. It
 * registers a runtime receiver (Android blocks the manifest USB broadcast for a
 * background app), waits for the system's audio-policy update, then uses
 * [UsbDacRecovery] to re-enumerate the host controller when the DAC is present
 * on USB but missing from AudioPolicy.
 *
 * Started only when Settings → USB DAC → Host Rebind is on, and stopped when it
 * is turned off. The ongoing low-importance notification is the price of a
 * reliable post-Exit monitor on Android 11.
 */
class UsbRecoveryService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            Log.i(TAG, "USB attach — scheduling DAC recovery")
            // Let the USB stack and UsbAlsaManager settle before checking the
            // audio policy; coalesce multiple attaches into one check. Check
            // twice: UsbAlsaManager sometimes marks the DAC unavailable a few
            // seconds after the attach, not immediately.
            worker.removeCallbacks(recoverRunnable)
            worker.postDelayed(recoverRunnable, SETTLE_MS)
            worker.postDelayed(recoverRunnable, SETTLE_MS + 2500L)
        }
    }

    private val recoverRunnable = Runnable {
        try {
            UsbDacRecovery.recoverIfNeeded(this, settleMs = 0)
        } catch (t: Throwable) {
            Log.w(TAG, "DAC recovery failed: ${t.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Always call startForeground() first: a service started with
        // startForegroundService() must satisfy the 5 s contract even if the
        // setting was turned off between start and creation. onStartCommand()
        // stops it when the setting is no longer enabled.
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException or a missing
            // foreground-service-type permission on a future Android release.
            // Degrade to no monitor instead of crashing the app process.
            Log.w(TAG, "Could not start foreground USB monitor: ${t.message}")
            stopSelf()
            return
        }
        workerThread = HandlerThread("usbRecovery").also { it.start() }
        worker = Handler(workerThread.looper)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        // Also check on start: the service may be launched after the USB attach
        // that left the DAC stale (e.g. from the attach activity itself, or at
        // boot while the boot receiver's process is still finishing). A second
        // check catches a policy update that arrives late.
        worker.postDelayed(recoverRunnable, SETTLE_MS)
        worker.postDelayed(recoverRunnable, SETTLE_MS + 4000L)
        Log.i(TAG, "USB recovery monitor started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!UsbDacRecovery.isHostRebindEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "USB recovery monitor stopped")
        runCatching { unregisterReceiver(usbReceiver) }
        if (::workerThread.isInitialized) workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USB audio monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the USB DAC available after USB device changes"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dac)
            .setContentTitle("USB audio monitor")
            .setContentText("Keeps the DAC available after USB changes")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "UsbRecoveryService"
        private const val CHANNEL_ID = "usb_recovery"
        private const val NOTIFICATION_ID = 0x5553
        private const val SETTLE_MS = 1200L

        /** Starts the monitor when the setting is on; stops it when off. */
        fun sync(context: Context) {
            if (UsbDacRecovery.isHostRebindEnabled(context)) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun start(context: Context) {
            if (!UsbDacRecovery.isHostRebindEnabled(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, UsbRecoveryService::class.java)
                )
            }.onFailure { e ->
                Log.w(TAG, "Could not start USB recovery monitor: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, UsbRecoveryService::class.java))
            }
        }
    }
}
