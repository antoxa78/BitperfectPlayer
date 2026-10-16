package com.example.bitperfectplayer

import android.app.Application
import android.util.Log

/**
 * Starts the in-app debug log at process start, before any activity, service or
 * boot receiver runs, so the capture covers app startup too.
 *
 * See [DebugLog] for why this exists: on Xiaomi TV boxes, enabling USB
 * debugging switches the shared USB port to device mode and disconnects the
 * USB DAC, so logcat over adb is unavailable while the DAC is in use.
 */
class BitperfectApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            DebugLog.install(this)
            DebugLogServer.apply(this)
        } catch (t: Throwable) {
            Log.w("BitperfectApp", "Debug log init failed", t)
        }
    }
}
