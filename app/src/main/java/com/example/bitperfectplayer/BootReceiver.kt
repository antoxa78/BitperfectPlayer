package com.example.bitperfectplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean("mpd_autostart", false)
        val enabled = prefs.getBoolean("mpd_enabled", true)
        if (!autostart || !enabled) {
            Log.i("BootReceiver", "MPD autostart disabled (autostart=$autostart enabled=$enabled)")
            return
        }
        Log.i("BootReceiver", "Starting PlaybackService for MPD autostart")
        val svc = Intent(context, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start PlaybackService", e)
        }
    }
}
