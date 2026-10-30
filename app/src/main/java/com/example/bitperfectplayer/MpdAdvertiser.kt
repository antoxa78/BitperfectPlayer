package com.example.bitperfectplayer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Advertises the embedded MPD server on the LAN through DNS-SD/mDNS as
 * `_mpd._tcp`, the service type MPD clients (including Bitperfect Remote)
 * browse for.
 *
 * Registration is best-effort: if Android's NSD service is missing or rejects
 * the registration, the MPD server keeps running and stays reachable by
 * address. Failed registrations are retried, since NSD may not be ready at boot
 * or right after a network change.
 */
class MpdAdvertiser(context: Context) {

    companion object {
        private const val TAG = "MpdAdvertiser"
        const val SERVICE_TYPE = "_mpd._tcp"
        private const val RETRY_DELAY_MS = 10_000L
    }

    private data class Target(val port: Int, val passwordRequired: Boolean)

    private enum class State { IDLE, REGISTERING, REGISTERED, UNREGISTERING }

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val handler = Handler(Looper.getMainLooper())

    // All state is touched on the main thread: public calls post here and so do
    // the NSD callbacks, so a registration/unregistration cannot race a toggle.
    private var wanted: Target? = null
    private var active: Target? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var state = State.IDLE
    private var retryScheduled = false

    init {
        if (nsdManager == null) Log.w(TAG, "Android NSD service unavailable; mDNS discovery disabled")
    }

    /** Starts advertising [port], or updates the advertisement if it changed. */
    fun start(port: Int, passwordRequired: Boolean) {
        handler.post { setWanted(Target(port, passwordRequired)) }
    }

    /** Stops advertising. Safe to call when nothing is advertised. */
    fun stop() {
        handler.post { setWanted(null) }
    }

    private fun setWanted(target: Target?) {
        wanted = target
        reconcile()
    }

    private fun reconcile() {
        if (nsdManager == null) return
        when (state) {
            State.IDLE -> wanted?.let { register(it) }
            State.REGISTERED -> if (active != wanted) listener?.let { unregister(it) }
            // The pending callback re-checks state and wanted when it arrives.
            State.REGISTERING, State.UNREGISTERING -> Unit
        }
    }

    private fun register(target: Target) {
        val nsd = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = advertisedServiceName()
            serviceType = SERVICE_TYPE
            port = target.port
            setAttribute("txtvers", "1")
            setAttribute("version", MpdServer.REPORTED_VERSION)
            setAttribute("password_required", if (target.passwordRequired) "1" else "0")
        }
        val newListener = object : NsdManager.RegistrationListener {
            private fun isCurrent() = listener === this

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (!isCurrent()) return@post
                    state = State.REGISTERED
                    active = target
                    Log.i(TAG, "Advertising \"${serviceInfo.serviceName}\" as $SERVICE_TYPE on port ${target.port}")
                    reconcile()
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    if (!isCurrent()) return@post
                    Log.w(TAG, "mDNS registration failed (error $errorCode); server stays reachable by address")
                    listener = null
                    state = State.IDLE
                    active = null
                    scheduleRetry()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (!isCurrent()) return@post
                    listener = null
                    state = State.IDLE
                    active = null
                    reconcile()
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    if (!isCurrent()) return@post
                    Log.w(TAG, "mDNS unregistration failed (error $errorCode)")
                    listener = null
                    state = State.IDLE
                    active = null
                    scheduleRetry()
                }
            }
        }
        listener = newListener
        state = State.REGISTERING
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, newListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration could not start: ${e.message}")
            listener = null
            state = State.IDLE
            active = null
            scheduleRetry()
        }
    }

    private fun unregister(registrationListener: NsdManager.RegistrationListener) {
        state = State.UNREGISTERING
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unregistration could not start: ${e.message}")
            listener = null
            state = State.IDLE
            active = null
            reconcile()
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled || wanted == null) return
        retryScheduled = true
        handler.postDelayed({
            retryScheduled = false
            reconcile()
        }, RETRY_DELAY_MS)
    }

    /**
     * A label with the app name and the device model, so two players on the
     * same network are distinguishable in a client's discovery list. NSD
     * resolves any remaining conflict by renaming the service.
     */
    private fun advertisedServiceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val base = if (model.isEmpty() || model.equals("unknown", ignoreCase = true)) {
            "Bitperfect Player"
        } else {
            "Bitperfect Player ($model)"
        }
        return base.take(63)
    }
}
