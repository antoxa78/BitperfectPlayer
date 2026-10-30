package com.example.bitperfectplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.decent.usbaudio.UsbAudioDevice
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * True only for a USB Audio Class device that can actually play audio: an
 * AudioStreaming interface (class 1, subclass 2) with an isochronous OUT
 * endpoint. Microphone-only devices — e.g. a TV remote's USB microphone — have
 * only an IN endpoint, so they must never be mistaken for the output DAC.
 */
internal fun UsbDevice.hasAudioPlaybackStream(): Boolean =
    (0 until interfaceCount).any { i ->
        val iface = getInterface(i)
        iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
            iface.interfaceSubclass == 2 &&
            (0 until iface.endpointCount).any { e ->
                val ep = iface.getEndpoint(e)
                ep.direction == UsbConstants.USB_DIR_OUT &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            }
    }

/** Live ICY info for the stream currently being played, if any. */
data class IcyStreamInfo(
    val mediaId: String,
    val title: String?,
    val station: String?,
    val genre: String?,
    val description: String?,
    val url: String?
)

/** Whether Android TV's inattentive-sleep timer can interrupt playback (see PlaybackService). */
enum class AttentiveSleepStatus {
    /** WRITE_SECURE_SETTINGS granted — the app suspends the timer during playback. */
    HANDLED_BY_APP,
    /** The device's inattentive-sleep timer is already off ("Never"). */
    ALREADY_OFF,
    /** Timer is active and the app cannot touch it — needs the one-time adb grant. */
    NEEDS_GRANT
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        // ── Constants ─────────────────────────────────────────────────────────
        private const val TAG                  = "PlaybackService"
        private const val PREFS_APP            = "AppSettings"
        private const val KEY_NETWORK_BUFFER   = "network_buffer"
        private const val KEY_AUTO_RECONNECT   = "auto_reconnect"
        private const val KEY_RESUME_PLAYBACK  = "resume_playback"
        private const val KEY_RECENT_FILES     = "recent_files"
        private const val KEY_USBDEVFS_DRIVER  = "usbdevfs_driver"
        /** Soft-replug the DAC (USBDEVFS_RESET) when handing it back. Default true. */
        private const val KEY_USB_RESET_ON_RELEASE = "usb_reset_on_release"
        private const val KEY_USB_HOST_REBIND_ON_EXIT = "usb_host_rebind_on_exit"
        // Audio output method (Settings → Audio Output). Migrates from the old
        // boolean KEY_USBDEVFS_DRIVER on first read (true → USBDEVFS, false → BITPERFECT_ANDROID).
        private const val KEY_AUDIO_OUTPUT_MODE = "audio_output_mode"
        private const val AUDIO_OUTPUT_BITPERFECT_ANDROID = 1 // Bit-perfect via Android: direct AudioTrack at native rate
        private const val AUDIO_OUTPUT_USBDEVFS           = 2 // Bit-perfect (USB driver): userspace usbdevfs driver owns the DAC
        private const val AUDIO_OUTPUT_STANDARD_ANDROID   = 3 // Standard Android output: plain system AudioTrack through the normal mixer

        private const val HTTP_TIMEOUT_SECS    = 20L
        private const val USER_AGENT           = "BitperfectPlayer/1.1 (Android TV)"
        private const val POSITION_SAVE_MS     = 10_000L
        private const val RECONNECT_DELAY_MS   = 5_000L
        private const val MAX_RETRIES          = 5

        // A station link such as …/listen.pls?sid=1 is a tiny playlist document,
        // not audio; it is resolved to the stream URL before ExoPlayer opens it.
        // Timeouts are shorter than the stream ones so a dead link fails over to
        // the player's normal error/reconnect path quickly.
        private const val MAX_PLAYLIST_BYTES    = 32 * 1024
        private const val PLAYLIST_TIMEOUT_MS   = 6_000
        private const val MAX_PLAYLIST_HOPS     = 2

        private const val BUFFER_MIN_MS        = 5_000
        private const val BUFFER_MAX_MS        = 120_000
        private const val BUFFER_PLAYBACK_MS   = 5_000
        private const val BUFFER_REBUFFER_MS   = 5_000
        private const val BUFFER_MAX_BYTES     = 128 * 1024 * 1024

        private const val RECENT_LIST_MAX      = 20

        private const val USB_SETTLE_MS        = 1_500L
        private const val USB_RESET_GAP_MS     = 400L
        // A pause/stop/stale hand-back is deferred by this much so a stop that is
        // immediately followed by play (MPD clear→add→play, station change) does
        // not tear the bit-perfect USB stream down and rebuild it. See
        // scheduleIdleHandBack(). Must be long enough to span a client's
        // clear→play sequence but short enough that a genuine pause returns the
        // DAC to other apps promptly.
        private const val IDLE_HANDBACK_DEBOUNCE_MS = 1_200L
        // After a live Settings → Audio Output switch the USBDEVFS_RESET soft-replug
        // has re-enumerated the DAC, but the kernel needs a beat to re-probe and
        // register the USB sound card before a new system AudioTrack should open.
        // We poll /dev/snd for the unbind/rebind, but this budget is a bound, not
        // a guarantee: opening too early can land in a half-enumerated device and
        // wedge card 0 (silent until a physical replug), while a missed detection
        // must never stall the mode switch for long.
        private const val USB_REBIND_MAX_WAIT_MS = 10_000L
        // Startup find-USB retry: the DAC can be mid-re-enumeration when the
        // service starts (esp. after our USBDEVFS_RESET soft-replug), so the
        // first probe can miss it. Retry a few times; a soft reset does not
        // re-broadcast ACTION_USB_DEVICE_ATTACHED, so nothing else re-arms.
        private const val USB_PROBE_RETRY_MS    = 2_000L
        private const val MAX_USB_PROBE_RETRIES = 4
        // Upper bound on how long auto-resume waits for the DAC permission dialog.
        // The result broadcast normally arrives on grant, deny and cancel alike;
        // this only keeps a box whose dialog never reports back from never resuming.
        private const val USB_PERMISSION_TIMEOUT_MS = 60_000L
        // When to record the DAC's route after a hand-back: once the re-created
        // card normally exists, and once more after slow boxes have caught up.
        private const val HANDBACK_ROUTE_CHECK_EARLY_MS = 3_000L
        private const val HANDBACK_ROUTE_CHECK_LATE_MS  = 10_000L
        // Grace period after the player reports STATE_IDLE (sink released) before
        // rebuilding the AudioTrack, so the USB HAL finishes tearing down the old
        // session — a fixed 400ms delay alone is too short on slow TV boxes and
        // the rebuilt track can inherit the stale sample rate (reset does nothing).
        private const val USB_RESET_TEARDOWN_GRACE_MS = 600L
        // Safety net: if the player never reports STATE_IDLE after stop() (should
        // not happen), proceed with the rebuild anyway rather than stalling forever.
        private const val USB_RESET_IDLE_TIMEOUT_MS  = 2_000L
        private const val MAX_RESET_RETRIES           = 5

        private const val LAN_FG_NOTIF_ID      = 1002
        private const val MEDIA3_NOTIF_ID      = 1001 // Media3 DefaultMediaNotificationProvider default id
        private const val CHANNEL_LAN_CONTROL  = "lan_control"
        private const val LAN_FG_WATCHDOG_MS   = 60_000L

        private const val TAG_BITPERFECT       = "BitPerfectAudio"
        /** A second DoP failure within this window falls back to PCM. */
        private const val DOP_RETRY_WINDOW_MS  = 30_000L

        // ── DSD output (Settings → DSD Output) ────────────────────────────────
        /** "pcm" (default): SACD/DSF/DFF are converted to PCM in the app — works
         *  with any DAC. "dop": DSD is sent as DoP (DSD over PCM) so a DoP-capable
         *  DAC decodes native DSD itself; used only in "Bit-perfect (USB driver)"
         *  mode, the one path that delivers the samples bit-exactly (DoP through
         *  a mixer or a resampler would reach the DAC as noise). */
        const val KEY_DSD_OUTPUT = "dsd_output"
        const val DSD_OUTPUT_PCM = "pcm"
        const val DSD_OUTPUT_DOP = "dop"

        /** Output mode as the service will use it (legacy setting migration
         *  included), read without side effects. */
        fun effectiveAudioOutputMode(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_AUDIO_OUTPUT_MODE)) {
                return if (prefs.getBoolean(KEY_USBDEVFS_DRIVER, true)) AUDIO_OUTPUT_USBDEVFS
                       else AUDIO_OUTPUT_BITPERFECT_ANDROID
            }
            val stored = prefs.getInt(KEY_AUDIO_OUTPUT_MODE, AUDIO_OUTPUT_USBDEVFS)
            return if (stored == 0) AUDIO_OUTPUT_BITPERFECT_ANDROID else stored
        }

        /** Icon for an audio output mode — shared by Settings → Audio Output and the
         *  Now Playing DAC button so both always show the same glyph per mode. */
        fun audioOutputIconRes(mode: Int): Int = when (mode) {
            AUDIO_OUTPUT_USBDEVFS         -> R.drawable.ic_output_usb_driver
            AUDIO_OUTPUT_STANDARD_ANDROID -> R.drawable.ic_output_standard
            else                          -> R.drawable.ic_output_bitperfect_android
        }

        /** Short, user-facing name of an output mode, as Now Playing shows it. */
        fun outputModeName(mode: Int): String = when (mode) {
            AUDIO_OUTPUT_USBDEVFS         -> "USB Audio Driver"
            AUDIO_OUTPUT_STANDARD_ANDROID -> "Standard Android"
            else                          -> "Bit-perfect Android"
        }

        /**
         * What Now Playing shows as the audio output: the mode's name, or — while
         * playing — the path actually in use when it differs from the mode (USB
         * driver not engaged, no bit-perfect mixer granted). Main thread.
         */
        fun outputPathLabel(context: Context, isPlaying: Boolean): String =
            instance?.currentOutputPathLabel(isPlaying)
                ?: outputModeName(effectiveAudioOutputMode(context))

        /** Icon for the output mode currently in effect. */
        fun currentAudioOutputIconRes(context: Context): Int =
            audioOutputIconRes(effectiveAudioOutputMode(context))

        fun isDopSelected(context: Context): Boolean =
            context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .getString(KEY_DSD_OUTPUT, DSD_OUTPUT_PCM) == DSD_OUTPUT_DOP

        /** True when DSD sources should be emitted as DoP right now: selected,
         *  USB-driver mode, and a USB DAC actually attached (without one the
         *  sink falls back to system audio, where DoP would play as noise).
         *  Also false after a DoP stream could not be delivered bit-exactly
         *  (see [recoverFromDopError]) until the DAC is re-attached or the DSD
         *  setting is changed. */
        fun isDopOutputActive(context: Context): Boolean =
            !dopSuppressed &&
                isDopSelected(context) &&
                effectiveAudioOutputMode(context) == AUDIO_OUTPUT_USBDEVFS &&
                findUsbAudioDevice(context) != null

        /** Set when the USB driver refused / lost a DoP stream: DSD is converted
         *  to PCM instead, so the track keeps playing (instead of failing, or
         *  reaching a speaker as noise). */
        @Volatile private var dopSuppressed = false

        /** Allow DoP again (DAC re-attached, or the DSD Output setting changed). */
        fun clearDopSuppression() {
            dopSuppressed = false
        }

        // ── Keep-awake state for the UI ───────────────────────────────────────
        // True while playback is active (same condition as the playback wake
        // lock). BaseActivity mirrors it into FLAG_KEEP_SCREEN_ON: on Android TV
        // a PARTIAL_WAKE_LOCK does NOT stop the normal inactivity timeout
        // (screen_off_timeout → screensaver → sleep_timeout "Put device to
        // sleep") — PowerManager only counts the device as "kept awake" for a
        // screen-level wake lock or a visible window with FLAG_KEEP_SCREEN_ON.
        @Volatile var keepAwake: Boolean = false
            private set
        private val keepAwakeListeners =
            java.util.concurrent.CopyOnWriteArraySet<(Boolean) -> Unit>()

        /** Main thread only. [listener] is invoked immediately with the current state. */
        fun addKeepAwakeListener(listener: (Boolean) -> Unit) {
            keepAwakeListeners.add(listener)
            listener(keepAwake)
        }

        fun removeKeepAwakeListener(listener: (Boolean) -> Unit) {
            keepAwakeListeners.remove(listener)
        }

        /** Main thread only. */
        private fun publishKeepAwake(value: Boolean) {
            if (keepAwake == value) return
            keepAwake = value
            keepAwakeListeners.forEach { it(value) }
        }

        // ── Android TV inattentive sleep (attentive_timeout) ──────────────────
        // Settings.Secure "attentive_timeout" (Android TV: Energy saver → "Turn
        // off display" when inactive) puts the box to sleep after that long with
        // no remote input. Unlike the normal timeout above it ignores every wake
        // lock and FLAG_KEEP_SCREEN_ON by design — the only way an app can hold
        // it off is to write the setting, which needs WRITE_SECURE_SETTINGS, a
        // development permission the user grants once over adb:
        //   adb shell pm grant <applicationId> android.permission.WRITE_SECURE_SETTINGS
        // While playing, the value is set to "never" (-1). The user's own value
        // is persisted first (so a crash/kill can never lose it) and put back
        // once playback has been stopped for ATTENTIVE_RESTORE_GRACE_MS, on
        // service teardown / Exit, on the next service start, and on boot.
        private const val SETTING_ATTENTIVE_TIMEOUT  = "attentive_timeout" // Settings.Secure.ATTENTIVE_TIMEOUT is @hide

        // ── USB "match content audio resolution" (NVIDIA / Android TV) ──────────
        // Settings.System "usb_audio_mcar" — the user-facing switch "USB Match
        // content audio resolution" in Android Settings → Sound. There is no public
        // constant for it and the whole feature is vendor-specific.
        //
        // Read-only here on purpose: reading a Settings.System key needs no
        // permission, while writing one needs WRITE_SETTINGS, which this app does
        // not request. When it is ON, AudioPolicyManager forces DIRECT (no SRC)
        // output for any USB media stream whose rate the DAC supports, and DIRECT
        // requires an exact format match. A DAC that advertises no PCM_16_BIT
        // profile (common: UAC2 DACs whose alt settings all use 4-byte subslots)
        // then has no usable direct profile for 16-bit content, and since most
        // other apps ask for 16-bit PCM, AudioPolicyManager finds no output at all
        // and createTrack() fails with -38 (ENOSYS) — total silence, not even an
        // HDMI fallback. Measured on a SHIELD Android TV + Audalytic DR70:
        // usb_audio_mcar=1 → repeated "could not find output" + createTrack -38;
        // usb_audio_mcar=0 → the same 16-bit stream opens on a MIXER thread that
        // writes to AUDIO_DEVICE_OUT_USB_HEADSET, and sound is back. The DAC's
        // advertised profile list is identical in both cases, so this is purely
        // the policy's routing decision — nothing to do with who holds the DAC.
        private const val SETTING_USB_MATCH_CONTENT_RESOLUTION = "usb_audio_mcar"

        private const val ATTENTIVE_NEVER            = -1
        private const val KEY_ATTENTIVE_OVERRIDDEN   = "attentive_timeout_overridden"
        private const val KEY_ATTENTIVE_SAVED        = "attentive_timeout_saved"
        private const val ATTENTIVE_SAVED_UNSET      = "__unset__"
        private const val ATTENTIVE_RESTORE_GRACE_MS = 30_000L

        fun canOverrideAttentiveSleep(context: Context): Boolean =
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Framework default used when attentive_timeout is unset; null if unknown. */
        private fun frameworkAttentiveDefaultMs(): Long? = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_attentiveTimeout", "integer", "android")
            if (id != 0) res.getInteger(id).toLong() else null
        } catch (_: Exception) { null }

        /** Effective inattentive-sleep timeout in ms (<= 0 = off), or null if unknown. */
        fun deviceAttentiveTimeoutMs(context: Context): Long? {
            val raw = try {
                android.provider.Settings.Secure.getString(context.contentResolver, SETTING_ATTENTIVE_TIMEOUT)
            } catch (_: Exception) { null }
            return raw?.toLongOrNull() ?: frameworkAttentiveDefaultMs()
        }

        fun attentiveSleepStatus(context: Context): AttentiveSleepStatus {
            if (canOverrideAttentiveSleep(context)) return AttentiveSleepStatus.HANDLED_BY_APP
            val t = deviceAttentiveTimeoutMs(context)
            return if (t != null && t <= 0) AttentiveSleepStatus.ALREADY_OFF
                   else AttentiveSleepStatus.NEEDS_GRANT
        }

        /**
         * Suspend the inattentive-sleep timer for the duration of playback.
         * Idempotent. Returns true when the timer is (now) off, false when the
         * permission is missing or the write failed.
         */
        internal fun suppressAttentiveSleep(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_ATTENTIVE_OVERRIDDEN, false)) return true
            if (!canOverrideAttentiveSleep(context)) return false
            val cr = context.contentResolver
            return try {
                val current = android.provider.Settings.Secure.getString(cr, SETTING_ATTENTIVE_TIMEOUT)
                val currentMs = current?.toLongOrNull()
                if (currentMs != null && currentMs <= 0) return true // already "never": nothing to undo later
                // Persist the user's value BEFORE overwriting it, so a crash or
                // kill between the two writes can never lose it.
                prefs.edit()
                    .putString(KEY_ATTENTIVE_SAVED, current ?: ATTENTIVE_SAVED_UNSET)
                    .putBoolean(KEY_ATTENTIVE_OVERRIDDEN, true)
                    .commit()
                android.provider.Settings.Secure.putInt(cr, SETTING_ATTENTIVE_TIMEOUT, ATTENTIVE_NEVER)
                Log.i(TAG, "Inattentive sleep suspended for playback (was ${current ?: "unset"})")
                true
            } catch (e: Exception) {
                prefs.edit().remove(KEY_ATTENTIVE_OVERRIDDEN).remove(KEY_ATTENTIVE_SAVED).commit()
                Log.w(TAG, "Could not suspend inattentive sleep: ${e.message}")
                false
            }
        }

        /**
         * Put the user's attentive_timeout back if this app changed it. Safe to
         * call at any time (no-op when nothing is overridden). If the value was
         * changed from Android TV settings in the meantime, the user's new
         * choice is kept.
         */
        fun restoreAttentiveSleep(context: Context, reason: String) {
            val prefs = context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ATTENTIVE_OVERRIDDEN, false)) return
            if (!canOverrideAttentiveSleep(context)) {
                // Keep the saved value — retried on the next service start / boot.
                Log.w(TAG, "Cannot restore attentive_timeout [$reason]: WRITE_SECURE_SETTINGS no longer granted")
                return
            }
            val cr = context.contentResolver
            try {
                val now = android.provider.Settings.Secure.getString(cr, SETTING_ATTENTIVE_TIMEOUT)
                if (now?.toLongOrNull() == ATTENTIVE_NEVER.toLong()) {
                    val saved = prefs.getString(KEY_ATTENTIVE_SAVED, null)
                        ?.takeIf { it != ATTENTIVE_SAVED_UNSET }
                    if (saved != null) {
                        android.provider.Settings.Secure.putString(cr, SETTING_ATTENTIVE_TIMEOUT, saved)
                    } else {
                        // Was unset (framework default): clear it again, falling
                        // back to writing the default explicitly.
                        val cleared = runCatching {
                            android.provider.Settings.Secure.putString(cr, SETTING_ATTENTIVE_TIMEOUT, null)
                        }.getOrDefault(false)
                        if (!cleared) {
                            frameworkAttentiveDefaultMs()?.let {
                                android.provider.Settings.Secure.putInt(cr, SETTING_ATTENTIVE_TIMEOUT, it.toInt())
                            }
                        }
                    }
                    Log.i(TAG, "Inattentive sleep restored to ${saved ?: "default"} [$reason]")
                } else {
                    Log.i(TAG, "attentive_timeout changed externally to $now — keeping it [$reason]")
                }
                prefs.edit().remove(KEY_ATTENTIVE_OVERRIDDEN).remove(KEY_ATTENTIVE_SAVED).commit()
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore attentive_timeout [$reason]: ${e.message}")
            }
        }

        // ── USB DAC detection ─────────────────────────────────────────────────
        /**
         * Detects a live USB audio **output** device using UsbManager.
         * Unlike AudioManager.getDevices() which caches USB descriptors and keeps
         * returning the device after power-off, UsbManager.deviceList only contains
         * devices currently active on the bus.
         *
         * Input-only USB audio devices (a TV remote's microphone, for example) are
         * deliberately ignored: they advertise an AudioStreaming interface but no
         * isochronous OUT endpoint, so they can never be the playback DAC.
         *
         * Callable from any Context — does not require the service to be running.
         */
        fun findUsbAudioDevice(context: Context): android.hardware.usb.UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE)
                as? android.hardware.usb.UsbManager ?: return null
            return usbManager.deviceList.values.firstOrNull { it.hasAudioPlaybackStream() }
        }

        /** Live reference to the running service — used by UI for resetAudioSink(). */
        @Volatile var instance: PlaybackService? = null
            private set

        // ── USB DAC permission gate ───────────────────────────────────────────
        // The usbdevfs driver can only claim a DAC this app has been granted. The
        // service asks at start, but UsbManager.requestPermission() is
        // fire-and-forget: the system dialog takes a moment to come up, and the UI
        // auto-resume (MainFragment) used to start the queue — and open Now
        // Playing on top — before it was answered. The first track was then
        // configured without the driver (silent AudioTrack fallback) and the
        // dialog showed up over, or behind, playback that had already started.
        // While a request is outstanding, auto-resume waits here instead.

        /** True while the DAC permission dialog is up and unanswered. Main thread only. */
        var usbPermissionPending = false
            private set
        private val usbPermissionWaiters = mutableListOf<() -> Unit>()

        /**
         * Runs [action] once the outstanding DAC permission request has been
         * answered (granted, denied or timed out) — or right away when there is
         * none. Main thread only.
         */
        fun runWhenUsbPermissionSettled(action: () -> Unit) {
            if (!usbPermissionPending) action() else usbPermissionWaiters.add(action)
        }

        /**
         * Ends the permission wait and hands back what queued on it, without
         * running it yet. Main thread only.
         */
        private fun endUsbPermissionWait(): List<() -> Unit> {
            usbPermissionPending = false
            val waiters = usbPermissionWaiters.toList()
            usbPermissionWaiters.clear()
            return waiters
        }

        private fun runUsbPermissionWaiters(waiters: List<() -> Unit>) {
            waiters.forEach { waiter ->
                runCatching(waiter).onFailure { e ->
                    Log.w(TAG, "USB permission waiter failed: ${e.message}")
                }
            }
        }

        /** Ends the permission wait and runs everything that queued on it. Main thread only. */
        private fun settleUsbPermission() {
            if (!usbPermissionPending) return
            runUsbPermissionWaiters(endUsbPermissionWait())
        }

        /**
         * Returns the live service instance, starting the service first if it is
         * not running (Android TV kills it when the UI is closed). Note that
         * onCreate() — which sets [instance] — runs on the main looper, so the
         * caller may need to retry briefly after this returns null.
         */
        fun ensureRunning(context: Context): PlaybackService? {
            val s = instance
            if (s != null) return s
            try {
                context.startService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "startService for reset failed: ${e.message}")
            }
            return instance
        }

        /**
         * Latest ICY metadata from the stream currently being played.
         *
         * Media3 gives MediaItem metadata precedence over in-band ICY metadata, so
         * Player.mediaMetadata.title never reflects the stream's StreamTitle once a
         * static title is set on the item. The UI reads this field instead.
         */
        @Volatile var icyInfo: IcyStreamInfo? = null
            private set

        private fun audioDeviceTypeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
            else -> "TYPE_$type"
        }

        /**
         * Android TV's "USB Match content audio resolution" switch
         * (Settings → Sound), or null when this device/vendor has no such setting.
         * See [SETTING_USB_MATCH_CONTENT_RESOLUTION] for what it does to other apps.
         */
        fun usbMatchContentResolution(context: Context): Boolean? = try {
            android.provider.Settings.System
                .getString(context.contentResolver, SETTING_USB_MATCH_CONTENT_RESOLUTION)
                ?.trim()?.toIntOrNull()?.let { it != 0 }
        } catch (_: Exception) { null }

        private fun isUsbOutput(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET

        /**
         * One-line risk report: can other apps be expected to make
         * sound on the USB DAC, and if not, which switch to look at.
         *
         * Only the combination is a problem — "match content audio resolution" on
         * its own is fine for a DAC that advertises 16-bit, and a 16-bit-less DAC
         * is fine while the switch is off (the HAL then converts on the mixer
         * path).
         *
         * Deliberately phrased as a *risk*, not a verdict: the switch's value is
         * only half the story. AudioPolicyManager caches it per device, and when
         * the forced DIRECT search fails it may still fall back to the mixer — on
         * this device a 16-bit track was created successfully with the switch ON,
         * minutes after it had been failing with -38. So this reports the setting,
         * the profile set, the mechanism and the fix, and leaves the actual routing
         * to the framework's own logs.
         *
         * Deliberately says nothing about this app's own playback: it bypasses the
         * audio policy entirely in usbdevfs mode.
         */
        fun usbOtherAppsAudibleVerdict(context: Context, devices: Array<out AudioDeviceInfo>): String {
            val usb = devices.filter { isUsbOutput(it.type) }
            if (usb.isEmpty()) return "no USB output device connected — this app's DAC hand-back cannot affect other apps"
            val no16 = usb.filter { AudioFormat.ENCODING_PCM_16BIT !in it.encodings }
            val mcar = usbMatchContentResolution(context)
            return when {
                mcar == null ->
                    "\"USB match content audio resolution\" setting not present on this device (vendor-specific)"
                mcar == true && no16.isNotEmpty() ->
                    "OTHER APPS AT RISK: \"USB match content audio resolution\" is ON and " +
                        no16.joinToString { "${it.productName} (encodings=${it.encodings.toList()})" } +
                        " advertises no PCM_16_BIT. While the audio policy honors that setting it " +
                        "forces DIRECT output, which cannot be matched, and 16-bit apps then fail " +
                        "createTrack() with -38 (observed on this device). The policy caches the " +
                        "setting per device, so it can still be routing via the mixer afterwards — " +
                        "this is a risk report, not a live verdict, and turning it off may need a " +
                        "DAC replug or an audio-service restart to take effect. Fix: Android " +
                        "Settings → Sound → \"USB Match content audio resolution\" → off."
                mcar == true ->
                    "\"USB match content audio resolution\" is ON; every connected USB DAC advertises " +
                        "PCM_16_BIT, so DIRECT output can still be matched."
                else ->
                    "\"USB match content audio resolution\" is OFF — other apps use the HAL's mixer " +
                        "path, which is the combination that works on a 32-bit-only DAC."
            }
        }

        /**
         * Live snapshot of every output device AudioManager (i.e. Android's audio
         * policy layer — the same layer every other app's AudioTrack routes through)
         * currently reports, with no adb and no filesystem access required. Added
         * because on this hardware adb and the USB DAC are mutually exclusive
         * (enabling wireless debugging disables the USB port entirely), so
         * `adb shell dumpsys audio` can never be run while the bug is actually
         * reproducible — this is the adb-free substitute: it runs on the way out of
         * [prepareForProcessExit] (and can be called again at any time), so with
         * BitperfectPlayer not playing you can tell whether Android itself still
         * considers the USB DAC an available sink — and, from the encodings it
         * reports together with "USB match content audio resolution", whether other
         * apps are being routed into a DIRECT profile that cannot exist.
         */
        fun currentAudioOutputDevicesSnapshot(context: Context): String {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "AudioManager unavailable"
            val devices = try {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            } catch (e: Exception) {
                return "getDevices() failed: ${e.message}"
            }
            if (devices.isEmpty()) return "AudioManager reports NO output devices at all (unusual — even the built-in speaker should normally be listed)."
            val lines = devices.joinToString("\n") { d ->
                "  type=${audioDeviceTypeName(d.type)} id=${d.id} name=${d.productName} " +
                    "address='${d.address}' " +
                    "channelCounts=${d.channelCounts.toList()} " +
                    "encodings=${d.encodings.toList()} " +
                    "sampleRates=${d.sampleRates.toList()}"
            }
            return "USB DAC present in AudioManager's output device list: " +
                "${devices.any { isUsbOutput(it.type) }}\n" +
                "USB match content audio resolution (Android Settings → Sound): " +
                "${usbMatchContentResolution(context)}\n" +
                "Other apps on the USB DAC: ${usbOtherAppsAudibleVerdict(context, devices)}\n" +
                "USB route: ${usbRouteVerdict(devices)}\n" +
                alsaCardsSnapshot() + "\n" +
                lines
        }

        // ── USB route diagnostics (no adb) ────────────────────────────────────
        // The driver's force-claim tears the DAC's ALSA card down and the
        // hand-back's USBDEVFS_RESET re-creates it. Android sees neither as a USB
        // detach/attach, so its audio policy keeps the device address it learned
        // at the last real attach ("card=N;device=M"). If the card comes back
        // under another number — the kernel only reuses N once nothing holds the
        // old card open — or without its playback PCM, every Android-path
        // AudioTrack routes to a device that no longer exists, until a real
        // replug/power cycle re-announces it. These compare the two sides.

        private val ALSA_ADDRESS = Regex("""card=(\d+);device=(\d+)""")
        private val PROC_CARD_LINE = Regex("""^\s*(\d+)\s+\[""")

        /** Card numbers the kernel currently has, from /dev/snd or /proc/asound/cards; null if neither is readable. */
        private fun kernelCardNumbers(): Set<Int>? {
            File("/dev/snd").list()?.let { names ->
                return names.mapNotNullTo(HashSet()) { n ->
                    Regex("""^controlC(\d+)$""").find(n)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return try {
                File("/proc/asound/cards").readLines()
                    .mapNotNullTo(HashSet()) { PROC_CARD_LINE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            } catch (_: Exception) { null }
        }

        /** Raw kernel-side view: /proc/asound/cards and /dev/snd, or why each is unreadable. */
        fun alsaCardsSnapshot(): String {
            val proc = try {
                File("/proc/asound/cards").readLines().filter { it.isNotBlank() }
                    .joinToString(" | ") { it.trim() }.ifEmpty { "(empty)" }
            } catch (e: Exception) {
                "not readable (${e.javaClass.simpleName})"
            }
            val dev = File("/dev/snd").list()?.sorted()?.joinToString(",")?.ifEmpty { "(empty)" }
                ?: "not listable"
            return "Kernel: /proc/asound/cards: $proc\nKernel: /dev/snd: $dev"
        }

        /**
         * One line: does the address Android routes the USB DAC to still exist in
         * the kernel? "STALE ROUTE" is the state in which Android-path audio is
         * silent until the DAC is power-cycled.
         */
        fun usbRouteVerdict(devices: Array<out AudioDeviceInfo>): String {
            val usb = devices.filter { isUsbOutput(it.type) }
            if (usb.isEmpty()) return "no USB output in AudioManager"
            val addrs = usb.map { it.address }
            val parsed = usb.mapNotNull { d ->
                ALSA_ADDRESS.find(d.address)?.let { m ->
                    m.groupValues[1].toInt() to m.groupValues[2].toInt()
                }
            }
            if (parsed.isEmpty()) return "framework USB address(es) $addrs not in card=N;device=M form — cannot compare"
            val devSnd = File("/dev/snd").list()?.toSet()
            val cards = kernelCardNumbers()
                ?: return "framework routes the USB DAC to $addrs; kernel side not observable " +
                    "(/dev/snd and /proc/asound both unreadable)"
            val missing = parsed.filter { (card, device) ->
                card !in cards || (devSnd != null && "pcmC${card}D${device}p" !in devSnd)
            }
            return if (missing.isEmpty()) {
                "OK — framework address(es) $addrs exist in the kernel (cards=${cards.sorted()})"
            } else {
                "STALE ROUTE — Android routes the USB DAC to " +
                    missing.joinToString { "card=${it.first};device=${it.second}" } +
                    " but the kernel has cards=${cards.sorted()}" +
                    (devSnd?.let { s -> " pcm=${s.filter { it.startsWith("pcm") }.sorted()}" } ?: "") +
                    ". Android-path audio is silent until a real unplug/power cycle of the DAC."
            }
        }

        /** True when [usbRouteVerdict] can positively see that the route is stale. */
        fun usbRouteIsStale(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            return try {
                usbRouteVerdict(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).startsWith("STALE ROUTE")
            } catch (_: Exception) { false }
        }
    }

    // ── USB DAC monitoring ────────────────────────────────────────────────────

    // Runnable field so USB-settle delayed calls can be cancelled before re-posting,
    // preventing pileup on rapid hotplug events (BUG-18).
    private val usbSettleRunnableReason = "USB attach"
    private val usbSettleRunnable = Runnable { checkAndResetUsbAudio(usbSettleRunnableReason) }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached — will check for DAC in ${USB_SETTLE_MS}ms")
                    // A (re)attached DAC gets a fresh chance at DoP.
                    clearDopSuppression()
                    // A real attach re-announces the DAC to Android, which clears
                    // a stale route — warn again if one shows up later.
                    staleRouteWarned = false
                    // No attach-time claim: the usbdevfs driver must only take the
                    // DAC while actively streaming. Claiming at attach steals the
                    // device from the system audio HAL and silent-blocks every
                    // other app (no HDMI fallback on this box). The driver claims
                    // lazily in configure() on the next playback start.
                    // Cancel any previous pending settle check before re-posting (BUG-18).
                    mainHandler.removeCallbacks(usbSettleRunnable)
                    mainHandler.postDelayed(usbSettleRunnable, USB_SETTLE_MS)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    mainHandler.removeCallbacks(usbSettleRunnable)
                }
            }
        }
    }

    /**
     * If a live USB DAC is present, resets the audio sink to force bit-perfect re-negotiation.
     */
    fun checkAndResetUsbAudio(reason: String = "manual") {
        val dac = findUsbAudioDevice(this)
        if (dac != null) {
            // A reset while the permission dialog is up would rebuild the sink
            // without the driver all over again; the grant re-runs this check.
            // A DAC found late by the startup probe (it was mid-re-enumeration
            // when onCreate asked) or plugged in while the service runs gets its
            // permission request here, before anything plays on it — the sink no
            // longer asks from configure() (requestPermissionOnConfigure = false).
            // Wake/re-registration checks never ask: the DAC was already there.
            val mayAsk = reason.contains("startup", ignoreCase = true) ||
                usbSettleRunnableReason == reason
            if (usbPermissionPending || (mayAsk && requestUsbPermissionIfNeeded(reason))) {
                Log.i(TAG, "USB DAC live: '${dac.deviceName}' — sink reset deferred until the " +
                    "USB permission dialog is answered [$reason]")
                usbProbeRetries = 0
                return
            }
            // In "Bit-perfect (USB driver)" mode a DAC that is already there when
            // the service starts needs no reset: the player was only just built, and
            // its UsbAudioSink claims the DAC itself when the first track configures
            // it. The reset only stopped the queue the UI had just resumed and started
            // it again — repeating the whole open + seek, which for a FLAC file on an
            // SMB share means a second network binary search before any sound. A DAC
            // found late (the "startup probe N" retries) may have missed the first
            // track, so that case still resets.
            if (reason == "startup" && usbDevfsDriverEnabled()) {
                Log.i(TAG, "USB DAC live: '${dac.deviceName}' at service start — no sink reset " +
                    "needed in USB-driver mode (the sink claims it on the first track)")
                usbProbeRetries = 0
                return
            }
            Log.i(TAG, "USB DAC live: '${dac.deviceName}' — resetting sink [$reason]")
            usbProbeRetries = 0
            resetAudioSink()
        } else {
            Log.d(TAG, "No live USB DAC found [$reason]")
            // Bounded startup retry for the transient re-enumeration window
            // (see USB_PROBE_RETRY_MS). Other reasons re-arm via broadcasts.
            if (reason.contains("startup", ignoreCase = true)
                && usbDevfsDriverEnabled()
                && ++usbProbeRetries <= MAX_USB_PROBE_RETRIES) {
                mainHandler.postDelayed({ checkAndResetUsbAudio("startup probe $usbProbeRetries") },
                    USB_PROBE_RETRY_MS)
            }
        }
    }

    // ── Sleep/wake handling ────────────────────────────────────────────────────
    // When the Shield sleeps, the device suspends and the audio HAL session is
    // torn down. On wake the USB DAC must re-enumerate before it can be used
    // again — the USB host power-cycles the link during suspend. That
    // re-enumeration is slow and unreliable (the DAC can take seconds to come
    // back, or not come back at all), and while the audio stack is wedged, the
    // policy keeps routing streams at the pre-sleep rate — other apps get no
    // sound, or the player plays resampled. The AudioDeviceCallback below
    // re-negotiates the sink the instant the DAC's audio side re-registers,
    // which is the earliest moment a fresh bit-perfect session can be opened.
    //
    // On Android 14+ the uid-scoped preferred mixer attributes set by
    // BitPerfectManager also survive suspend and keep blocking other apps'
    // audio after wake; they are cleared on ACTION_SCREEN_ON below.

    // Runnable field so a quick sleep-after-wake cancels the pending reset
    // instead of running it while suspended, mirroring usbSettleRunnable.
    private val wakeResetRunnable = Runnable { checkAndResetUsbAudio("wake") }

    // Reacts to the USB DAC's audio-side (re)registration. Unlike the USB
    // attach broadcast — which fires at the bus level, before the audio HAL
    // and policy have picked the device up — this fires when the device is
    // actually openable, which is exactly when the sink reset will succeed.
    private val usbDeviceCallback = object : AudioDeviceCallback() {
        private fun isUsb(device: AudioDeviceInfo) =
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.any(::isUsb)) {
                Log.i(TAG, "USB audio device added — re-negotiating sink")
                checkAndResetUsbAudio("device added")
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any(::isUsb)) {
                Log.i(TAG, "USB audio device removed — clearing bit-perfect state")
                bitPerfectManager.clear()
                // The vendored usbdevfs driver (decent-player) has no disconnect
                // detection of its own — its native engine only reacts to I/O
                // errors on its next transfer, which may not happen promptly (or
                // at all, if playback is paused) after a physical unplug. Without
                // this, usbDriverOwnsDac can stay stale-true and every other DAC-
                // losing path here (pause/idle/screen off/onDestroy) already pairs
                // bitPerfectManager.clear() with releaseUsbDriverDac() — this was
                // the one asymmetric spot.
                releaseUsbDriverDac()
            }
        }
    }

    // ── decent-player userspace USB driver integration ────────────────────────

    /** Wrapped in PlaybackService.buildAudioSink when the usbdevfs driver is enabled. */
    private var usbAudioSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    /** Held during active playback to keep the CPU awake (the USB DAC stream is
     *  torn down if the CPU suspends mid-isochronous-URB). PARTIAL_WAKE_LOCK is
     *  used so it never forces the TV panel on by itself.
     *
     *  NOTE: on Android TV this alone does NOT stop the box from sleeping —
     *  "screen off" there IS standby. Staying awake during playback is handled
     *  by two more pieces, both driven from [updateSleepSuppression]:
     *   - the normal inactivity timeout (screen_off_timeout / sleep_timeout) is
     *     blocked by FLAG_KEEP_SCREEN_ON in the visible activity (BaseActivity,
     *     via [keepAwake]); the app's own screensaver still blanks the panel;
     *   - the inattentive-sleep timer (attentive_timeout) ignores all wake locks
     *     and is suspended by writing the setting (needs the one-time adb grant
     *     of WRITE_SECURE_SETTINGS — see [suppressAttentiveSleep]).
     *  Acquired on play, released on pause/stop/end. */
    private var playbackWakeLock: PowerManager.WakeLock? = null

    /** Posted when playback stops; puts the user's attentive_timeout back after a grace period. */
    private val attentiveRestoreRunnable = Runnable { restoreAttentiveSleep(this, "playback stopped") }

    /** Log the "needs adb grant" hint only once per service lifetime. */
    private var attentiveHintLogged = false

    /** Set on Exit / teardown: no further keep-awake or attentive_timeout
     *  override. Without it, a wake-lock update still in flight from the USB
     *  release (driver ownership flips false on its own thread) could re-apply
     *  the override just before System.exit(). */
    private var sleepSuppressionShutDown = false

    /** True while the usbdevfs driver owns the USB DAC (mirrors the wrapper's
     *  onDriverOwnsUsbDeviceChanged callback; written from the release thread). */
    @Volatile
    private var usbDriverOwnsDac = false

    /**
     * True while the driver's exclusive usbfs claims on the DAC are in place — the
     * state in which every other app is silent, because the kernel's snd-usb-audio
     * stays detached for as long as the claims are.
     *
     * [usbDriverOwnsDac] alone is not enough: the claims are taken in the sink's
     * configureUsbBitPerfect(), which the deferred cross-rate reconfiguration runs on
     * its own background thread without going through configure()'s callback, so the
     * flag could be false while the DAC was very much claimed. Every decision made
     * from that flag alone (hand the DAC back on pause/idle/exit, keep the process
     * alive while the DAC is claimed) was then skipped and the claims survived the
     * app. The sink itself is the authority, so ask it too.
     */
    private fun usbDacClaimedByDriver(): Boolean =
        usbDriverOwnsDac || usbAudioSink?.ownsUsbDevice == true

    /**
     * True while a DAC hand-back (stream stop → USBDEVFS_RESET soft-replug → close)
     * is still running on the sink's release thread. Ownership is reported as gone
     * at the *start* of that sequence, so this — not [usbDriverOwnsDac] — is what
     * says the process may not be killed yet.
     */
    private fun usbDacHandBackInFlight(): Boolean =
        usbAudioSink?.isIdleReleaseInFlight() == true

    /**
     * Release the usbdevfs driver's claim on the USB DAC so the system audio
     * HAL can use it again (pause, stop, sleep — whenever the player is not
     * actively streaming). The driver re-claims lazily on the next play().
     * The wrapper's onDriverOwnsUsbDeviceChanged(false) callback fires inside
     * releaseUsbStream() (now on the release thread), so a second call right
     * after (e.g. pause → STATE_IDLE) sees ownership already gone and is a no-op.
     *
     * @return true when a release (and USB reset) was actually triggered; false
     *         when the driver does not own the DAC and there is nothing to release.
     */
    private fun releaseUsbDriverDac(skipDrain: Boolean = false): Boolean {
        // This is the immediate hand-back used by the paths that must complete
        // now (focus loss, screen off/on, output-mode change, process exit).
        // Cancel any debounced pause/idle hand-back so it cannot fire later as a
        // duplicate.
        cancelIdleHandBack("immediate release")
        // NOTE: deliberately NOT gated on usbDevfsDriverEnabled(). If the wrapper
        // exists and currently owns the DAC, its usbfs claims must be dropped
        // regardless of the current mode — e.g. a live Settings → Audio Output
        // switch from usbdevfs to a system mode must return the DAC to the HAL
        // or the new AudioTrack opens into a still-claimed device and is silent.
        val sink = usbAudioSink ?: return false
        if (!usbDacClaimedByDriver()) {
            // Driver does not own the DAC (released, never engaged, or a failed
            // configure). Nothing to hand back — avoid a pointless USB reset.
            Log.d(TAG, "DAC release skipped — driver does not own the DAC")
            return false
        }
        return try {
            // A second caller (pause + STATE_IDLE + screen-off are different threads)
            // can still pass the ownership check above while the first hand-back is
            // running: the stream is already gone, but the device is not closed until
            // the release thread gets there. releaseUsbForIdle() then skips as a
            // duplicate, so report the verdict only when this call is the one that
            // actually started the hand-back.
            val alreadyInFlight = usbDacHandBackInFlight()
            // The exit path skips the native DISCARDURB phase, which can block
            // forever on the Amlogic kernel; its USBDEVFS_RESET/close aborts the
            // remaining URBs instead.
            sink.releaseUsbForIdle(skipDrain = skipDrain)
            if (!alreadyInFlight) {
                logOtherAppsAudibleVerdict("DAC hand-back")
                // The re-created card shows up (or not) a few seconds after the
                // reset; record where Android routes the DAC once it has settled.
                mainHandler.removeCallbacks(handBackRouteCheckEarly)
                mainHandler.removeCallbacks(handBackRouteCheckLate)
                mainHandler.postDelayed(handBackRouteCheckEarly, HANDBACK_ROUTE_CHECK_EARLY_MS)
                mainHandler.postDelayed(handBackRouteCheckLate, HANDBACK_ROUTE_CHECK_LATE_MS)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "usbdevfs DAC release failed: ${t.message}")
            false
        }
    }

    private val handBackRouteCheckEarly = Runnable {
        val owns = usbDacClaimedByDriver()
        logUsbRoute("DAC hand-back +${HANDBACK_ROUTE_CHECK_EARLY_MS / 1000}s, driverOwns=$owns", staleExpected = owns)
    }
    private val handBackRouteCheckLate = Runnable {
        val owns = usbDacClaimedByDriver()
        logUsbRoute("DAC hand-back +${HANDBACK_ROUTE_CHECK_LATE_MS / 1000}s, driverOwns=$owns", staleExpected = owns)
    }

    /**
     * Logs where Android's audio policy routes the USB DAC next to what the
     * kernel actually has (see [usbRouteVerdict]). Any thread. A "STALE ROUTE"
     * line is the evidence for the "Android audio silent after the USB driver,
     * until the DAC is power-cycled" bug; it is also logged as a warning.
     */
    private fun logUsbRoute(reason: String, staleExpected: Boolean = false) {
        try {
            val snapshot = currentAudioOutputDevicesSnapshot(this)
            Log.i(TAG, "USB route snapshot [$reason]:\n$snapshot")
            // While the driver holds the DAC its card is gone by design.
            if (!staleExpected) {
                snapshot.lineSequence().firstOrNull { it.startsWith("USB route: STALE ROUTE") }
                    ?.let { Log.w(TAG, "[$reason] $it") }
            }
        } catch (e: Exception) {
            Log.d(TAG, "USB route snapshot [$reason] failed: ${e.message}")
        }
    }

    /** One warning per service lifetime, so a stale route is not reported on every track. */
    private var staleRouteWarned = false

    /**
     * Main thread. In a system-audio output mode, tells the user when Android
     * routes the DAC to a sound card the kernel no longer has — playback would
     * otherwise be plain silence with no hint that a DAC power cycle fixes it.
     */
    private fun warnIfUsbRouteStale(reason: String) {
        if (staleRouteWarned || usbDevfsDriverEnabled()) return
        if (!usbRouteIsStale(this)) return
        staleRouteWarned = true
        logUsbRoute("stale route while playing via Android audio [$reason]")
        android.widget.Toast.makeText(
            applicationContext,
            "Android can't reach the USB DAC after the USB driver used it. " +
                "Switch the DAC off and on (or replug it) to get sound.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Records, in logcat, whether other apps can be expected to make sound
     * now that the DAC is (being) returned to the system — the one moment a user is
     * likely to leave this app and press play somewhere else, and the moment a
     * routing decision made entirely inside Android (see
     * [SETTING_USB_MATCH_CONTENT_RESOLUTION]) can make that pointless. Only ever
     * warns: the setting is vendor-specific and read-only for this app, so the
     * verdict is reported with the switch name and its current value and the user
     * decides.
     */
    private fun logOtherAppsAudibleVerdict(reason: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val verdict = usbOtherAppsAudibleVerdict(this, devices)
            Log.i(TAG, "$reason — $verdict")
            if (verdict.startsWith("OTHER APPS AT RISK")) {
                Log.w(TAG, "$reason — $verdict")
            }
        } catch (e: Exception) {
            Log.d(TAG, "$reason — could not evaluate other-app audibility: ${e.message}")
        }
    }

    /** Settings → Audio Output. Migrates the legacy usbdevfs_driver boolean on first read. */
    private fun getAudioOutputMode(): Int {
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        if (!prefs.contains(KEY_AUDIO_OUTPUT_MODE)) {
            val mapped = if (prefs.getBoolean(KEY_USBDEVFS_DRIVER, true)) {
                AUDIO_OUTPUT_USBDEVFS
            } else {
                AUDIO_OUTPUT_BITPERFECT_ANDROID
            }
            prefs.edit().putInt(KEY_AUDIO_OUTPUT_MODE, mapped).apply()
            return mapped
        }
        val stored = prefs.getInt(KEY_AUDIO_OUTPUT_MODE, AUDIO_OUTPUT_USBDEVFS)
        // The former "Default (Android audio)" mode (0) was removed; treat it as
        // bit-perfect via Android so a persisted 0 keeps a valid audio path.
        val mapped = if (stored == 0) AUDIO_OUTPUT_BITPERFECT_ANDROID else stored
        if (mapped != stored) {
            prefs.edit().putInt(KEY_AUDIO_OUTPUT_MODE, mapped).apply()
        }
        return mapped
    }

    private fun usbDevfsDriverEnabled(): Boolean = getAudioOutputMode() == AUDIO_OUTPUT_USBDEVFS

    /** See [outputPathLabel]. Main thread. */
    fun currentOutputPathLabel(isPlaying: Boolean): String {
        val mode = getAudioOutputMode()
        val name = outputModeName(mode)
        if (!isPlaying) return name
        return when (mode) {
            AUDIO_OUTPUT_USBDEVFS -> {
                if (usbDacClaimedByDriver()) return name
                // Playing, but the driver does not hold the DAC: the sink fell back
                // to the Android audio path. Say why.
                val dac = findUsbAudioDevice(this)
                val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                when {
                    dac == null -> "Android Audio · no USB DAC"
                    usbPermissionPending || usbManager?.hasPermission(dac) != true ->
                        "Android Audio · USB permission needed"
                    else -> "Android Audio · USB driver not active"
                }
            }
            AUDIO_OUTPUT_BITPERFECT_ANDROID ->
                if (bitPerfectManager.lastTrackBitPerfect == false) "Android Audio · resampled" else name
            else -> name
        }
    }

    private val usbPermissionTimeout = Runnable {
        if (!usbPermissionPending) return@Runnable
        Log.w(TAG, "USB permission dialog not answered within ${USB_PERMISSION_TIMEOUT_MS}ms — " +
            "releasing auto-resume (playback uses the system path until it is granted)")
        settleUsbPermission()
    }

    /**
     * In "Bit-perfect (USB driver)" mode, asks for the DAC permission the driver
     * needs and holds auto-resume ([runWhenUsbPermissionSettled]) until the
     * dialog is answered. Main thread only.
     *
     * @return true when a request is outstanding after this call.
     */
    private fun requestUsbPermissionIfNeeded(reason: String): Boolean {
        if (usbPermissionPending) return true
        if (!usbDevfsDriverEnabled()) return false
        return try {
            val driver = com.decent.usbaudio.UsbAudioDevice.getInstance(this)
            val dac = driver.findUsbAudioDevice() ?: return false
            if (driver.hasPermission(dac)) return false
            usbPermissionPending = true
            Log.i(TAG, "Requesting USB permission for '${dac.productName ?: dac.deviceName}' " +
                "[$reason] — auto-resume held until it is answered")
            mainHandler.removeCallbacks(usbPermissionTimeout)
            mainHandler.postDelayed(usbPermissionTimeout, USB_PERMISSION_TIMEOUT_MS)
            if (DacPermissionMemory.isEnabled(this)) {
                // Android offers the permanent grant in this same dialog — the app
                // cannot tick it itself (Settings → USB DAC → Remember DAC Permission).
                android.widget.Toast.makeText(
                    applicationContext,
                    "Tick \"Always open ${getString(R.string.app_name)} when " +
                        "${dac.productName ?: "the DAC"} is connected\" so Android " +
                        "doesn't ask again.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            // The callback's thread is up to the driver — hop to main.
            driver.requestPermission(dac) { granted ->
                mainHandler.post { onUsbPermissionResult(granted) }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "USB permission request failed: ${t.message}")
            mainHandler.removeCallbacks(usbPermissionTimeout)
            settleUsbPermission()
            false
        }
    }

    /** Main thread. Also handles an answer that arrives after the timeout gave up waiting. */
    private fun onUsbPermissionResult(granted: Boolean) {
        mainHandler.removeCallbacks(usbPermissionTimeout)
        Log.i(TAG, "USB permission (driver): granted=$granted")
        // Answered after this service was destroyed: its player is released and
        // a newer instance (if any) runs its own request.
        if (instance !== this) return
        // Clear the pending flag first (checkAndResetUsbAudio defers while it is
        // set), but run the waiters only after the check below.
        val waiters = if (usbPermissionPending) endUsbPermissionWait() else emptyList()
        if (granted) {
            // Something may have reached the sink while the dialog was up (a play
            // from an MPD client or a remote, or auto-resume after the timeout);
            // it was configured without the driver. Re-negotiate so it moves onto
            // the driver now instead of staying on the fallback until the next track.
            val p = mediaSession?.player
            if (p != null && p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE) {
                checkAndResetUsbAudio("USB permission granted")
            }
        }
        // Auto-resume then prepares a fresh pipeline that can claim the DAC from
        // the very first buffer.
        runUsbPermissionWaiters(waiters)
    }

    /**
     * Applied by Settings → Audio Output right after the new mode is saved. If the
     * previous (usbdevfs) mode was streaming, its wrapper still owns the DAC via usbfs;
     * that claim must be dropped and the USBDEVFS_RESET soft-replug given time to
     * complete before the rebuilt sink's system AudioTrack opens the device — otherwise
     * playback is silent (the HAL cannot open a claimed interface). The output sink is
     * chosen when ExoPlayer builds its renderers, so the player itself is rebuilt in
     * the new mode (queue/position/play-state restored) rather than merely restarted.
     */
    fun applyOutputModeAudioReset() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyOutputModeAudioReset() }
            return
        }
        val old = mediaSession?.player as? ExoPlayer
        // Snapshot queue/position/play-intent BEFORE stopping the old player:
        // after stop() the timeline can turn into a placeholder whose duration is
        // TIME_UNSET, which rebuildPlayerForOutputMode would read as a live stream
        // and restore at position 0.
        val snapshot = old?.let { snapshotPlayerState(it) }

        val sink = usbAudioSink
        // Ownership and the in-flight flag must be read before stop(): stop()
        // fires STATE_IDLE, whose listener already calls releaseUsbDriverDac(),
        // so afterwards usbDriverOwnsDac is already false even though the
        // USBDEVFS_RESET hand-back is still running (same race as
        // prepareForProcessExit). Missing it meant the new system AudioTrack was
        // opened into a DAC that was still claimed / half re-enumerated → silence.
        val wasOwned = sink != null && usbDacClaimedByDriver()
        val releaseInFlight = sink?.isIdleReleaseInFlight() == true
        // Snapshot the system sound cards before the soft-replug so we can watch
        // the USB card unbind/rebind (below) while the reset re-enumerates the DAC.
        val cardsBefore = sndCards()
        val expectedDacName = findUsbAudioDevice(this)?.productName

        // Stop the old player first (as prepareForProcessExit and resetAudioSink
        // do). Previously it kept playing through the whole hand-back wait (up to
        // USB_REBIND_MAX_WAIT_MS + settle): its UsbAudioSink could re-claim the
        // DAC on the next buffer, or its BitPerfectAudioSink delegate — no longer
        // blocked once driver ownership flipped to false — could open a direct
        // track / set BIT_PERFECT mixer attributes on the DAC. Either way the
        // rebuilt player's AudioTrack in "Bit-perfect via Android" or "Standard
        // Android Output" mode ended up silent.
        runCatching {
            Log.i(TAG, "stopPlayer from rebuildPlayerForOutputMode")
            old?.stop()
        }
        bitPerfectManager.clear()
        releaseUsbDriverDac()

        if (sink == null || (!wasOwned && !releaseInFlight)) {
            // The driver did not own the DAC — no USB reset was triggered, so there
            // is no re-enumeration to wait out. Rebuild immediately instead of
            // waiting for a card change that will never happen (BUG-21).
            if (usbAudioSink === sink) usbAudioSink = null
            rebuildPlayerForOutputMode(snapshot)
            return
        }
        val main = mainHandler
        Thread({
            if (!waitForUsbRebind(sink, cardsBefore, expectedDacName)) {
                Log.w(TAG, "USB sound card did not re-register after soft-replug — " +
                    "system-audio modes may stay silent until the DAC is physically replugged")
            } else {
                // The card is back, but the audio HAL/policy reports it before it
                // is actually stable; rebuilding immediately can open a fresh
                // system AudioTrack on a half-ready device and be silent. Give it
                // the same settle the USB attach / exit paths use.
                sleepQuietly(USB_SETTLE_MS)
            }
            logUsbRoute("output-mode switch, before rebuilding the player")
            main.post {
                if (usbAudioSink === sink) usbAudioSink = null
                rebuildPlayerForOutputMode(snapshot)
                warnIfUsbRouteStale("output-mode switch")
            }
        }, "usbModeSwitch").start()
    }

    /** Queue, position and play-intent of a player, captured for a rebuild. */
    private class PlayerStateSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val position: Long,
        val playWhenReady: Boolean
    )

    private fun snapshotPlayerState(p: ExoPlayer) = PlayerStateSnapshot(
        items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) },
        index = p.currentMediaItemIndex,
        // Live streams (duration == TIME_UNSET) report an unbounded position;
        // restoring it would seek past the end of a seekable station (BUG-19).
        position = if (p.duration == C.TIME_UNSET) 0L else p.currentPosition,
        playWhenReady = p.playWhenReady
    )

    /**
     * Rebuilds ExoPlayer so the new audio-output mode's sink is really in use.
     * The sink is decided inside DefaultRenderersFactory.buildAudioSink when the
     * player builds its renderers, so changing the mode at runtime requires a
     * fresh player (a plain sink/reset restart reuses the old renderer). The
     * MediaSession stays put — its controllers (UI / MPD bridge) keep working —
     * and the queue, position and play-intent are restored on the new player.
     * Must be called on the main thread.
     */
    private fun rebuildPlayerForOutputMode(snapshot: PlayerStateSnapshot?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { rebuildPlayerForOutputMode(snapshot) }
            return
        }
        val session = mediaSession ?: return
        val old = session.player as? ExoPlayer ?: return
        // Prefer the snapshot captured before the old player was stopped (stop()
        // can turn the timeline into a live-stream placeholder and reset the play
        // intent); fall back to the live player state when no snapshot was taken.
        val items = snapshot?.items ?: (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = snapshot?.index ?: old.currentMediaItemIndex
        // Live streams (duration == TIME_UNSET) report an unbounded position;
        // restoring it would seek past the end of a seekable station (BUG-19).
        val position = snapshot?.position ?: if (old.duration == C.TIME_UNSET) 0L else old.currentPosition
        val playWhenReady = snapshot?.playWhenReady ?: old.playWhenReady
        val mode = getAudioOutputMode()
        Log.i(TAG, "Rebuilding player for audio output mode $mode " +
            "[items=${items.size} idx=$index pos=$position playWhenReady=$playWhenReady]")

        val fresh = buildPlayer()
        session.setPlayer(fresh)
        runCatching { old.release() }
        // usbAudioSink was already cleared by applyOutputModeAudioReset before the
        // rebuild; buildPlayer re-assigns it only when building in usbdevfs mode.

        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index, position)
            fresh.playWhenReady = playWhenReady
            if (playWhenReady) {
                fresh.prepare()
                fresh.play()
            }
        } else {
            fresh.playWhenReady = playWhenReady
        }
        Log.i(TAG, "Player rebuilt in audio output mode $mode")
    }

    /**
     * `/dev/snd` control cards (controlC0, controlC1, …) currently registered,
     * or null if `/dev/snd` itself could not be listed (permission/IO error —
     * genuinely unobservable). Kept distinct from an empty-but-listable result:
     * the usbdevfs driver's force=true interface claims detach the kernel's
     * snd-usb-audio driver for as long as it owns the DAC, so a *listable*
     * /dev/snd legitimately has zero controlC entries for that device the
     * entire time it is actively streaming — that is not the same situation
     * as not being able to read the directory at all, and waitForUsbRebind()
     * needs to tell them apart.
     */
    private fun sndCardsOrNull(): Set<String>? =
        File("/dev/snd").list()?.filterTo(HashSet()) { it.startsWith("controlC") }

    private fun sndCards(): Set<String> = sndCardsOrNull() ?: emptySet()

    /**
     * Holds a fresh, one-shot AudioDeviceCallback registration used only to
     * detect a USB audio device becoming available again while waiting for the
     * post-exit USB rebind — see [registerUsbDeviceAddedWatcher].
     */
    private class UsbDeviceAddedWatcher(
        private val audioManager: AudioManager,
        private val callback: AudioDeviceCallback,
        val latch: CountDownLatch
    ) {
        val fired: Boolean get() = latch.count == 0L
        fun unregister() {
            try { audioManager.unregisterAudioDeviceCallback(callback) } catch (_: Exception) {}
        }
    }

    /**
     * Permission-free fallback for detecting the USB DAC coming back, for devices
     * where /dev/snd cannot be listed at all (confirmed via on-device log on a
     * Xiaomi Mi Box S / Android TV 14 — sndCardsOrNull() is always null there,
     * almost certainly SELinux). AudioDeviceCallback.onAudioDevicesAdded fires
     * when the audio HAL/policy actually opens the USB device again, which is
     * independent of whether this app can read /dev/snd.
     *
     * Deliberately registered as a *fresh, one-shot* callback here rather than
     * reusing [usbDeviceCallback]: that one is unregistered from onDestroy(),
     * which runs on the main thread almost immediately after
     * prepareForProcessExit() is invoked (MainFragment calls stopService()
     * first), so waiting on it here would race onDestroy() and likely never
     * see the event. This callback is registered directly against AudioManager
     * with its own Handler on the main Looper, so it keeps working for the
     * rest of the process's life regardless of the service's own teardown —
     * exactly the window needed here (up to USB_REBIND_MAX_WAIT_MS before
     * System.exit(0)).
     *
     * Returns null (nothing to wait on) if AudioManager isn't reachable or
     * registration itself throws.
     */
    /**
     * True when [device] is a USB **output** with real capabilities and, when
     * [expectedDacName] is known, is that DAC rather than another USB audio
     * device on the bus (e.g. the always-present "ZY Control Mic"). Input-only
     * devices are never accepted: `isSink` is false and their sample-rate list
     * is empty.
     */
    private fun isExpectedUsbDac(device: AudioDeviceInfo, expectedDacName: String?): Boolean {
        val isUsb = device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        if (!isUsb || !device.isSink || device.sampleRates.isEmpty()) return false
        if (expectedDacName.isNullOrBlank()) return true
        val product = device.productName?.toString()?.trim().orEmpty()
        // Some HALs leave productName empty; isSink + non-empty sampleRates
        // above is still enough to exclude the input-only microphone.
        return product.isEmpty() ||
            product.contains(expectedDacName, ignoreCase = true) ||
            expectedDacName.contains(product, ignoreCase = true)
    }

    private fun registerUsbDeviceAddedWatcher(expectedDacName: String?): UsbDeviceAddedWatcher? {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val latch = CountDownLatch(1)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                val usbDevice = addedDevices.firstOrNull {
                    isExpectedUsbDac(it, expectedDacName)
                }
                if (usbDevice != null) {
                    // Log what was actually reported, not just a boolean — if this
                    // event turns out not to be a reliable signal of real usability
                    // (observed on-device: other apps still silent afterward), the
                    // device's own reported channel/encoding lists here are the next
                    // best evidence of whether Android considered it fully populated
                    // at the moment it fired.
                    Log.i(TAG,
                        "registerUsbDeviceAddedWatcher: onAudioDevicesAdded fired for " +
                            "type=${usbDevice.type} id=${usbDevice.id} name=${usbDevice.productName} " +
                            "channelCounts=${usbDevice.channelCounts.toList()} " +
                            "encodings=${usbDevice.encodings.toList()} (expected=$expectedDacName)"
                    )
                    latch.countDown()
                } else if (addedDevices.isNotEmpty()) {
                    Log.d(TAG,
                        "registerUsbDeviceAddedWatcher: ignoring non-DAC audio device(s): " +
                            addedDevices.joinToString { "${it.productName} (sink=${it.isSink})" })
                }
            }
        }
        return try {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            UsbDeviceAddedWatcher(audioManager, callback, latch)
        } catch (e: Exception) {
            Log.i(TAG, "registerUsbDeviceAddedWatcher: failed: ${e.message}")
            null
        }
    }

    /**
     * Waits (blocking the calling background thread) for the DAC's USB sound card
     * to unbind and rebind after the USBDEVFS_RESET soft-replug, so a fresh system
     * AudioTrack never opens into a half-enumerated device (which can wedge card 0
     * until a physical replug).
     *
     * Detection is deliberately tolerant: after re-enumeration the card usually
     * re-registers under the SAME controlC index, so "a new name appeared" is not a
     * reliable signal. Instead we first wait for a card that was present before the
     * reset to disappear (unbind), then for a card to come back (rebind). Both stages
     * are bounded by [USB_REBIND_MAX_WAIT_MS]. If /dev/snd starts out empty because
     * the usbdevfs driver's force claim had the kernel driver detached (the normal
     * state while it streams), this instead waits for a controlC entry to appear;
     * if /dev/snd is not listable at all (permission/IO error — genuinely
     * unobservable, distinguished via sndCardsOrNull()), detection falls back to
     * an [UsbDeviceAddedWatcher] (AudioDeviceCallback), which is unaffected by
     * /dev/snd access. All paths are still bounded by [USB_REBIND_MAX_WAIT_MS], so
     * the caller is never stalled past that; the caller always proceeds afterwards
     * regardless of the result — a missed detection only skips the confidence of a
     * confirmed rebind, it never blocks forever.
     *
     * The watcher is registered up front and consulted only after the
     * isIdleReleaseInFlight() gate below clears. On-device evidence showed
     * exactly why the gate must NOT be
     * raced: the AudioDeviceCallback can fire ~350ms into the release — while
     * releaseUsbStream() is still running, i.e. long before the force=true usbfs
     * interface claims are dropped and USBDEVFS_RESET re-enumerates the DAC.
     * Trusting that early signal let Exit kill the process mid-release and leave
     * the DAC claimed, so every other app stayed silent until a physical replug.
     * Registering the watcher up front is still valuable: a rebind that lands
     * during the gate is not lost, it is honored once the release actually
     * completes. The gate itself is bounded by [USB_REBIND_MAX_WAIT_MS] so a
     * genuinely stuck release never stalls Exit forever.
     *
     * @return true when the kernel re-registering the sound card was actually observed.
     */
    /**
     * True when Android's audio HAL currently offers a USB output device **with real
     * capabilities**.
     *
     * The device merely being present in the list is not proof of anything: the
     * framework caches USB descriptors, so after a usbfs release the device shows up
     * again well before the kernel driver has re-probed and the HAL has re-enumerated
     * its formats. A non-empty `sampleRates` means the HAL actually queried the
     * device, which is what other apps need in order to route audio to it.
     */
    private fun usbDacAvailableToSystem(expectedDacName: String?): Boolean {
        return try {
            val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { device ->
                isExpectedUsbDac(device, expectedDacName)
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Whether the usbdevfs driver should soft-replug the DAC (USBDEVFS_RESET) when
     * handing it back to the system. On by default — it is what makes the kernel
     * rebind the system USB-audio driver. Off is available because the re-created
     * ALSA card is not tracked by every box's audio framework, which can leave
     * other apps with no sound until a reboot (see Settings → USB DAC).
     */
    fun usbResetOnRelease(context: Context): Boolean =
        context.getSharedPreferences(PREFS_APP, MODE_PRIVATE)
            .getBoolean(KEY_USB_RESET_ON_RELEASE, true)

    private fun usbResetOnRelease(): Boolean = usbResetOnRelease(this)

    /**
     * Opt-in, root-only last resort: when the normal USBDEVFS_RESET/rebind (or
     * an unrelated USB attach, handled by [UsbRecoveryService]) did not make the
     * DAC visible to Android's audio policy, unbind/bind the whole xHCI host
     * controller so UsbHostManager sees a genuine detach/attach. Off by default;
     * drops every USB device on the controller.
     */
    fun usbHostRebindOnExit(context: Context): Boolean =
        context.getSharedPreferences(PREFS_APP, MODE_PRIVATE)
            .getBoolean(KEY_USB_HOST_REBIND_ON_EXIT, false)

    private fun waitForUsbRebind(
        sink: com.decent.usbaudio.media3.UsbAudioSink,
        cardsBefore: Set<String>,
        expectedDacName: String?
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + USB_REBIND_MAX_WAIT_MS
        val watcher = registerUsbDeviceAddedWatcher(expectedDacName)
        try {
            // The unbind can only happen once the release thread has run
            // resetUsbDevice() (interface release + USBDEVFS_RESET + closeDevice).
            // On-device evidence (see the doc comment above) showed the
            // AudioDeviceCallback can fire while releaseUsbStream() is STILL
            // running — long before the force=true usbfs claims are dropped. So the
            // gate is always waited out (bounded by the deadline); the watcher is
            // only honored once the release has actually finished, guaranteeing the
            // DAC was handed back before the process can be killed after this returns.
            while (sink.isIdleReleaseInFlight() && SystemClock.uptimeMillis() < deadline) {
                if (!sleepQuietly(10)) return false
            }
            if (watcher?.fired == true) return true

            if (cardsBefore.isEmpty()) {
                if (sndCardsOrNull() == null) {
                    // /dev/snd genuinely not observable (permission/IO error, e.g.
                    // SELinux-blocked on some Android TV boxes) — /dev/snd polling
                    // can never work here. Fall back to the audio HAL/policy layer…
                    // but do NOT trust AudioDeviceCallback alone: on this box it fires
                    // a few milliseconds after the usbfs claims are dropped (i.e. when
                    // the framework can see the *descriptor* again), long before the
                    // kernel has re-probed and the HAL has re-enumerated the device.
                    // Trusting that signal made Exit leave ~1.5 s later, mid-rebind.
                    // So poll for the device to be genuinely available instead.
                    Log.i(TAG, "waitForUsbRebind: /dev/snd not listable — verifying via AudioManager instead")
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (usbDacAvailableToSystem(expectedDacName)) {
                            Log.i(TAG, "waitForUsbRebind: rebindVerified=true after " +
                                "${USB_REBIND_MAX_WAIT_MS - (deadline - SystemClock.uptimeMillis())}ms")
                            return true
                        }
                        if (!sleepQuietly(150)) return false
                    }
                    Log.w(TAG, "waitForUsbRebind: rebindVerified=false — the USB DAC is not " +
                        "available to the system within ${USB_REBIND_MAX_WAIT_MS}ms")
                    return false
                }
                Log.i(TAG, "waitForUsbRebind: /dev/snd listable but empty — polling for a card to appear")
                // /dev/snd IS listable but currently has no controlC entry — the
                // expected state while the usbdevfs driver owned the DAC (its
                // force=true claim keeps the kernel driver detached the whole
                // time it streams). This is the common case, not an edge case:
                // wait for a card to actually appear instead of sleeping a fixed
                // grace and declaring victory regardless — that previously let
                // Exit report success and kill the process before the kernel
                // driver had rebound at all, leaving system audio dead.
                while (SystemClock.uptimeMillis() < deadline) {
                    if (sndCards().isNotEmpty() &&
                        (expectedDacName.isNullOrBlank() ||
                            usbDacAvailableToSystem(expectedDacName))) return true
                    if (watcher?.fired == true) return true
                    if (!sleepQuietly(100)) return false
                }
                return false
            }

            // Stage 1: the old USB card unbinds — a card we saw before disappears, or a
            // replacement card appears (the rebind can outpace our 100 ms polling).
            var sawUnbind = false
            while (SystemClock.uptimeMillis() < deadline) {
                val now = sndCards()
                if (now.isEmpty() || now.size < cardsBefore.size || now.any { it !in cardsBefore }) {
                    sawUnbind = true
                    break
                }
                if (watcher?.fired == true) return true
                if (!sleepQuietly(100)) return false
            }
            if (!sawUnbind) return watcher?.fired == true

            // Stage 2: the USB card comes back. Accept the same or a different index,
            // but only once the expected DAC is actually available to the audio
            // policy — a new controlC card can belong to another USB audio device
            // (e.g. a microphone), and that must not be mistaken for the DAC.
            while (SystemClock.uptimeMillis() < deadline) {
                val now = sndCards()
                if (now.isNotEmpty() && now.size >= cardsBefore.size) {
                    if (expectedDacName.isNullOrBlank() ||
                        usbDacAvailableToSystem(expectedDacName)) return true
                }
                if (watcher?.fired == true) return true
                if (!sleepQuietly(100)) return false
            }
            return false
        } finally {
            watcher?.unregister()
        }
    }

    private fun sleepQuietly(ms: Long): Boolean {
        try {
            Thread.sleep(ms)
            return true
        } catch (e: InterruptedException) {
            return false
        }
    }

    /** Build the current track's native-engine path check for the driver's LoadControl. */
    private fun wrapLoadControlForUsb(d: DefaultLoadControl): LoadControl =
        com.decent.usbaudio.media3.UsbAudioSink.wrapLoadControl(d) {
            usbAudioSink?.isNativeEngineActive == true
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    mainHandler.removeCallbacks(wakeResetRunnable)
                    if (isActivelyPlaying()) {
                        // Keep listening through suspend; playing resumes and
                        // re-negotiates the USB DAC on wake. The suspend/wake
                        // reset path (usbReceiver/checkAndResetUsbAudio) slits
                        // and re-engages the driver automatically.
                        Log.i(TAG, "Screen off while playing — suspend tears the stream down; wake re-negotiates")
                    } else {
                        // Not playing: release the usbdevfs driver's claim on the
                        // DAC before suspend so it is not pinned through sleep —
                        // and the kernel proxy pcm is rebind-able once we soft-
                        // replug (USBDEVFS_RESET). Other apps can then use it.
                        Log.i(TAG, "Screen off — releasing idle audio track + DAC")
                        releaseUsbDriverDac()
                        stopPlayer("screen off")
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Android 14+: the uid-scoped preferred mixer attributes
                    // survive suspend and block other apps' audio — always clear.
                    bitPerfectManager.clear()
                    if (isActivelyPlaying()) {
                        // The player still thinks it is playing, but its AudioTrack
                        // is dead after suspend; its framework-level auto-restore
                        // loop re-attaches a stream at our rate and locks the DAC
                        // forever. Rebuild the sink (with the USB settle grace) so
                        // the DAC session is re-negotiated from scratch.
                        mainHandler.removeCallbacks(wakeResetRunnable)
                        mainHandler.postDelayed(wakeResetRunnable, USB_SETTLE_MS)
                    } else {
                        // Zombie half-state (track attached, not producing audio)
                        // also pins the DAC — release it so other apps get sound.
                        Log.i(TAG, "Screen on — releasing idle audio track")
                        releaseUsbDriverDac()
                        stopPlayer("screen on")
                    }
                }
            }
        }
    }

    /** True while the player is actively producing audio (or trying to). */
    private fun isActivelyPlaying(): Boolean {
        val p = mediaSession?.player ?: return false
        return p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
    }

    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Debounced pause/idle DAC hand-back ───────────────────────────
    // A pause or stop hands the USB DAC back so other apps can use it. Doing
    // that the instant STATE_IDLE arrives tears down and rebuilds the whole
    // bit-perfect stream when a stop is immediately followed by play (the
    // normal MPD clear→add→play / station-change pattern), costing a multi-
    // second gap. Instead the pause/idle hand-back is deferred by
    // IDLE_HANDBACK_DEBOUNCE_MS and dropped if playback resumes first.
    //
    // Deliberately NOT used by the hand-backs that must complete now:
    // transient audio-focus loss, screen off/on, output-mode change and
    // process exit call releaseUsbDriverDac() directly (which cancels any
    // pending debounce and releases immediately). A genuine pause still
    // returns the DAC to other apps — just IDLE_HANDBACK_DEBOUNCE_MS later.
    /** How long a pause/idle hand-back waits for a possible resume. */
    private val idleHandBackRunnable = Runnable { runIdleHandBackNow() }

    /** True while a debounced pause/idle hand-back is scheduled. */
    @Volatile private var idleHandBackPending = false

    /** Schedule (or coalesce into) the debounced pause/idle hand-back. */
    private fun scheduleIdleHandBack(reason: String) {
        if (idleHandBackPending) return
        idleHandBackPending = true
        Log.i(TAG, "Idle hand-back scheduled in ${IDLE_HANDBACK_DEBOUNCE_MS}ms ($reason)")
        mainHandler.postDelayed(idleHandBackRunnable, IDLE_HANDBACK_DEBOUNCE_MS)
    }

    /** Drop a pending pause/idle hand-back — playback is active again. */
    private fun cancelIdleHandBack(reason: String) {
        if (!idleHandBackPending) return
        idleHandBackPending = false
        mainHandler.removeCallbacks(idleHandBackRunnable)
        Log.i(TAG, "Idle hand-back cancelled — playback active again ($reason)")
    }

    private fun runIdleHandBackNow() {
        idleHandBackPending = false
        releaseUsbDriverDac()
    }

    private lateinit var bitPerfectManager: BitPerfectManager
    private var mpdServer: MpdServer? = null

    // ── LAN keep-foreground ───────────────────────────────────────────────────
    // Android TV aggressively kills idle "started" services once the app UI is
    // closed, which takes the MPD server down with it. While LAN control is
    // enabled and Media3 is not showing its own media notification, hold the
    // service in the foreground state with a minimal silent notification.

    private var lanFgOwned = false
    private val lanFgWatchdog = object : Runnable {
        override fun run() {
            updateLanForeground()
            mainHandler.postDelayed(this, LAN_FG_WATCHDOG_MS)
        }
    }

    private fun updateLanForeground() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLanForeground() }
            return
        }
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val mpdEnabled = prefs.getBoolean("mpd_enabled", true)
        val player = mediaSession?.player
        val media3OwnsFg = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        // Keep the process alive while the usbdevfs driver holds the DAC or is
        // mid-hand-back. On Android TV Media3's own foreground notification can
        // be dropped during buffer/ENDED transitions or on audio-focus loss, so
        // this service ends up cached and the system kills it — if that happens
        // while the DAC is claimed (or before the async USBDEVFS_RESET finishes),
        // the DAC's kernel driver is left detached and nothing has sound until a
        // physical replug. So when the DAC is held we own the foreground service
        // ourselves, even while Media3 thinks it is playing.
        val dacHeld = usbDevfsDriverEnabled() &&
            (usbDacClaimedByDriver() || usbDacHandBackInFlight())
        val lanKeep = mpdEnabled && !media3OwnsFg
        val keepFg = lanKeep || dacHeld

        try {
            if (keepFg) {
                val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (mgr.getNotificationChannel(CHANNEL_LAN_CONTROL) == null) {
                    mgr.createNotificationChannel(
                        NotificationChannel(CHANNEL_LAN_CONTROL, "LAN control",
                            NotificationManager.IMPORTANCE_MIN)
                    )
                }
                // While paused, Media3 keeps its (non-foreground) media notification
                // with id 1001; remove it so we don't end up with two notifications
                // alongside our keep-foreground one.
                try { mgr.cancel(MEDIA3_NOTIF_ID) } catch (_: Exception) {}
                val port = prefs.getInt("mpd_port", 6600).coerceIn(1024, 65535)
                val n = NotificationCompat.Builder(this, CHANNEL_LAN_CONTROL)
                    .setSmallIcon(R.drawable.ic_lan_control)
                    .setContentTitle(if (lanKeep) "LAN control active" else "Bitperfect Player")
                    .setContentText(if (lanKeep) "MPD :$port — control from phone app" else "USB DAC active")
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build()
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(LAN_FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(LAN_FG_NOTIF_ID, n)
                }
                if (!lanFgOwned) {
                    lanFgOwned = true
                    Log.i(TAG, "Keep-foreground started (${if (lanKeep) "LAN" else "DAC held"})")
                }
            } else if (lanFgOwned) {
                try { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                lanFgOwned = false
                Log.i(TAG, "Keep-foreground released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Keep-foreground failed: ${e.message}")
        }
    }

    /** mediaId of the currently playing item — protected by icyInfoLock. */
    private var activeMediaId: String? = null
    private val icyInfoLock = Any()

    // ── Position saver ────────────────────────────────────────────────────────

    private val savePositionRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            mainHandler.postDelayed(this, POSITION_SAVE_MS)
        }
    }

    /**
     * Playback position worth persisting, in ms.
     *
     * Returns 0 for live streams (duration == TIME_UNSET) because their
     * currentPosition grows unboundedly and is meaningless for a restart —
     * restoring it makes ExoPlayer seek past the stream end on seekable
     * radio streams (e.g. MP3 with a Xing/VBR header), which instantly ends
     * playback instead of resuming the station (BUG-19). Also returns 0 for
     * finished items (pos == duration, e.g. playlist ended) since resuming
     * at that position would instantly end playback again.
     */
    private fun safePositionToSave(p: Player): Long {
        val pos = p.currentPosition
        val dur = p.duration
        return if (dur == C.TIME_UNSET || pos >= dur) 0L else pos
    }

    private fun saveCurrentPosition() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE) {
                // Index and position must be written atomically in one edit:
                // saveLastPlayed() (on track transition) and this timer save
                // independently, and pairing a stale position from the previous
                // track with the new track's index would make resume seek past
                // the end and skip/stop instead of resuming.
                val safePos = safePositionToSave(p)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit()
                    .putLong("last_played_pos", safePos)
                    .putInt("last_played_index", p.currentMediaItemIndex)
                    .apply()
            }
        }
    }

    /**
     * Saves the current playback position synchronously using commit() instead
     * of apply(). Called from the UI before System.exit(0) so the position is
     * guaranteed to be on disk before the process is killed (BUG-6).
     */
    fun saveCurrentPositionSync() {
        mediaSession?.player?.let { p ->
            if (p.playbackState != Player.STATE_IDLE) {
                val safePos = safePositionToSave(p)
                getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                    .edit()
                    .putLong("last_played_pos", safePos)
                    .putInt("last_played_index", p.currentMediaItemIndex)
                    .commit()
            }
        }
    }

    /**
     * Releases every resource that exclusively claims the DAC and invokes
     * [onReleased] once it is actually safe to kill the process — not merely
     * once release has been *requested*. For a caller about to call
     * System.exit(0) (the Exit menu / back-button confirmation in MainFragment):
     * stopService()/Service.onDestroy() run asynchronously on the main looper,
     * so calling System.exit(0) right after them is a race process death
     * usually wins — exactly like BUG-6 for saveCurrentPositionSync(), but for
     * the DAC instead of the saved position.
     *
     * A short fixed delay is not enough to fix that race: releaseUsbForIdle()
     * only kicks off a USBDEVFS_RESET soft-replug on the driver's own release
     * thread, and the kernel can take up to USB_REBIND_MAX_WAIT_MS to re-probe
     * and re-register the USB sound card afterward — the same window
     * applyOutputModeAudioReset() already blocks on before it will reuse the
     * DAC. This does the same wait (bounded, so Exit can never hang forever)
     * on a background thread and calls [onReleased] there — callers should
     * treat that as "now it's safe", not the main thread.
     *
     * Ownership must be snapshotted *before* player.stop() below, not after:
     * stop() synchronously fires onPlaybackStateChanged(STATE_IDLE), whose
     * own listener already calls releaseUsbDriverDac() — so by the time this
     * function went on to call it again afterward, usbDriverOwnsDac could
     * already have flipped to false (the release was merely requested, not
     * finished) and releaseUsbDriverDac() here would report "nothing to
     * release", skipping the wait for a reset that is, in fact, still
     * running. That silently reintroduced the exact race this function
     * exists to close.
     *
     * For the same reason the *in-flight* hand-back must be snapshotted too
     * (usbDacHandBackInFlight(), read before stop() as well): ownership is
     * reported as gone at the very start of a hand-back, while the usbfs
     * interface claims are only dropped at the very end of it. Testing
     * ownership alone made Exit kill the process during a hand-back that was
     * already running — playback had just stopped/ended/paused a moment
     * earlier, or another app's audio was already broken — so System.exit(0)
     * took the release thread down with it, the force=true claims were never
     * dropped, and the DAC's kernel driver stayed detached: no sound in any
     * other app until it was physically replugged. When only a hand-back is
     * pending, Exit waits for it to finish and then leaves; the rebind itself
     * is not ours to wait for in that state (it was previously an instant
     * exit, and the hand-back's own thread is what has to survive).
     */
    fun prepareForProcessExit(onReleased: () -> Unit) {
        val sink = usbAudioSink
        val wasOwned = usbDacClaimedByDriver()
        val handBackInFlight = usbDacHandBackInFlight()
        val cardsBefore = sndCards()
        // Capture the DAC's identity before stop()/release: the watcher below must
        // not accept another USB audio device (the TV remote's microphone) as the
        // DAC coming back.
        val expectedDacName = findUsbAudioDevice(this)?.productName
        Log.i(TAG, "prepareForProcessExit: usbAudioSink=${sink != null} " +
            "wasOwned=$wasOwned handBackInFlight=$handBackInFlight " +
            "cardsBefore=$cardsBefore expectedDac=$expectedDacName")

        // System.exit() skips onDestroy() — put the user's sleep timer back now.
        sleepSuppressionShutDown = true
        mainHandler.removeCallbacks(attentiveRestoreRunnable)
        publishKeepAwake(false)
        restoreAttentiveSleep(this, "exit")

        stopPlayer("prepareForProcessExit")
        bitPerfectManager.clear()
        releaseUsbDriverDac(skipDrain = true)
        // One verdict line per exit, after the hand-back has been requested, so it
        // reflects the state the other apps are about to meet rather than this app
        // still holding the DAC (while the claims are in place AudioManager cannot
        // even list the device, which would report the uninformative "no USB output
        // device connected"). Also runs on the instant-exit path below, which skips
        // the detailed snapshot entirely. See usbOtherAppsAudibleVerdict().
        logOtherAppsAudibleVerdict("Exit")

        // Decide from the state *now*, not only from what was read at the top: a
        // claim can also be taken in this window (the deferred cross-rate
        // reconfiguration claims the DAC on its own thread), and the
        // releaseUsbDriverDac() call above has only *requested* a hand-back — it
        // runs on the sink's thread.
        val needsRebindWait = wasOwned || usbDacClaimedByDriver()
        val needsHandBackWait = handBackInFlight || usbDacHandBackInFlight()

        if (sink == null || (!needsRebindWait && !needsHandBackWait)) {
            // The driver was not actively holding the DAC when Exit was
            // pressed (not in usbdevfs mode, or already idle/paused) and no
            // hand-back is running. There may still be nothing to release — but
            // the earlier idle hand-back can have restored the kernel card without
            // putting the DAC back into AudioPolicy. Check that before leaving, or
            // every other app stays silent after Exit.
            if (!expectedDacName.isNullOrBlank() &&
                !usbDacAvailableToSystem(expectedDacName) &&
                usbHostRebindOnExit(this)) {
                Log.i(TAG, "prepareForProcessExit: nothing to release, but DAC is missing " +
                    "from Android's output list — rebinding host controller before exit")
                Thread({
                    ensureDacAvailableForExit(expectedDacName)
                    onReleased()
                }, "exitDacRebind").start()
                return
            }
            Log.i(TAG, "prepareForProcessExit: nothing to wait for — exiting immediately")
            onReleased()
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        Log.i(TAG, if (needsRebindWait)
            "prepareForProcessExit: waiting for USB rebind before exit"
        else
            "prepareForProcessExit: waiting for the in-flight DAC hand-back before exit")
        Thread({
            if (needsRebindWait) {
                var rebound = waitForUsbRebind(sink, cardsBefore, expectedDacName)
                if (rebound) {
                    // On-device evidence: this signal can fire only a few hundred ms
                    // after AudioDeviceCallback.onAudioDevicesAdded, yet other apps
                    // still had no sound afterward. That event is not trusted this fast
                    // anywhere else in this codebase — normal USB attach handling
                    // deliberately waits USB_SETTLE_MS after the same "device added"
                    // signal before touching the device, because the HAL/policy reports
                    // it before it is actually stable. Exiting (killing the whole
                    // process, including any native driver cleanup still finishing the
                    // handoff) within a few hundred ms of that same raw signal was very
                    // likely premature. Give it the same settle time used elsewhere
                    // before treating the rebind as durable.
                    Log.i(TAG, "prepareForProcessExit: rebind verified — settling ${USB_SETTLE_MS}ms before exit")
                    sleepQuietly(USB_SETTLE_MS)
                } else {
                    Log.w(TAG, "prepareForProcessExit: rebind NOT verified — the USB DAC is not " +
                        "available to the system; other apps may need a replug")
                    rebound = ensureDacAvailableForExit(expectedDacName)
                }
                Log.i(TAG, "prepareForProcessExit: wait finished after " +
                    "${SystemClock.uptimeMillis() - startedAt}ms, rebindVerified=$rebound — exiting now")
                // Captured from inside this (about to die) process, right before exit —
                // the most direct evidence available, with no adb needed, of whether
                // Android's audio policy still considers the USB DAC an available sink
                // at the exact moment we hand control back.
                Log.i(TAG, "prepareForProcessExit: final device snapshot:\n" +
                    currentAudioOutputDevicesSnapshot(this))
            } else {
                // Ownership was already given up before Exit was pressed, but the
                // hand-back is still running. Its thread has not yet dropped the
                // usbfs claims and closed the device, and killing the process now
                // would leave the DAC claimed for good — so wait it out (bounded,
                // so a stuck release cannot hang Exit) and then leave.
                val done = awaitUsbDacHandBack(sink)
                Log.i(TAG, "prepareForProcessExit: in-flight hand-back " +
                    "${if (done) "finished" else "did NOT finish"} after " +
                    "${SystemClock.uptimeMillis() - startedAt}ms")
                // A completed hand-back restores the kernel card, but on this box
                // that alone does not put the DAC back into AudioPolicy. Verify the
                // policy and use the same opt-in host rebind as the owned path;
                // otherwise Exit after an earlier pause leaves every other app silent.
                ensureDacAvailableForExit(expectedDacName)
                Log.i(TAG, "prepareForProcessExit: exiting now")
            }
            onReleased()
        }, "exitDacRelease").start()
    }

    /**
     * Blocks the calling background thread until an in-flight DAC hand-back has
     * actually finished — i.e. until releaseUsbForIdle()'s thread has dropped the
     * usbfs interface claims and closed the device (it clears the in-flight flag in
     * a finally that runs after closeDevice(), so the flag is a truthful signal).
     *
     * Bounded by [USB_REBIND_MAX_WAIT_MS] so a genuinely stuck release can never
     * hang Exit forever.
     *
     * @return true when the hand-back completed.
     */
    private fun awaitUsbDacHandBack(sink: com.decent.usbaudio.media3.UsbAudioSink): Boolean {
        val deadline = SystemClock.uptimeMillis() + USB_REBIND_MAX_WAIT_MS
        while (sink.isIdleReleaseInFlight() && SystemClock.uptimeMillis() < deadline) {
            if (!sleepQuietly(10)) return false
        }
        return !sink.isIdleReleaseInFlight()
    }

    /**
     * Last check before the process dies: is the expected DAC actually in
     * Android's output device list? The kernel card can be back while
     * UsbAlsaManager/AudioPolicy still has no USB output — the exact state in
     * which every other app stays silent after Exit. With the opt-in root
     * fallback, re-enumerate the xHCI controller so the framework sees a real
     * detach/attach. Runs on a background thread.
     *
     * @return true when the DAC is available (or no DAC identity was known).
     */
    private fun ensureDacAvailableForExit(expectedDacName: String?): Boolean {
        if (expectedDacName.isNullOrBlank()) return true
        if (usbDacAvailableToSystem(expectedDacName)) {
            Log.i(TAG, "prepareForProcessExit: DAC '$expectedDacName' already available to Android")
            return true
        }
        if (!usbHostRebindOnExit(this)) {
            Log.w(TAG, "prepareForProcessExit: DAC '$expectedDacName' missing from Android's " +
                "output list; host rebind is disabled — replug/reboot may be needed")
            return false
        }
        Log.w(TAG, "prepareForProcessExit: DAC '$expectedDacName' missing from Android's " +
            "output list — attempting root-only xHCI host rebind")
        // The rebind re-attaches the DAC; suppress UsbAttachActivity's UI during
        // the process exit.
        UsbDacRecovery.markUiSuppressed(this)
        val forced = runCatching {
            UsbAudioDevice.getInstance(this).forceHostControllerRebind(expectedDacName)
        }.getOrElse { e ->
            Log.w(TAG, "prepareForProcessExit: host rebind threw: ${e.message}")
            false
        }
        if (!forced) {
            Log.w(TAG, "prepareForProcessExit: host rebind failed (no root or no xHCI node)")
            return false
        }
        val deadline = SystemClock.uptimeMillis() + USB_REBIND_MAX_WAIT_MS
        while (!usbDacAvailableToSystem(expectedDacName) &&
            SystemClock.uptimeMillis() < deadline) {
            if (!sleepQuietly(150)) break
        }
        val ok = usbDacAvailableToSystem(expectedDacName)
        Log.i(TAG, "prepareForProcessExit: host-controller rebind verified=$ok")
        return ok
    }

    /**
     * Tears down ExoPlayer's AudioTrack (closing the HAL session) and
     * immediately rebuilds it, forcing Android to re-negotiate bit-perfect
     * output parameters with the DAC.
     *
     * Current queue, position, and play-state are fully restored.
     */
    // Runnable field for the sink-restore step so rapid resetAudioSink() calls
    // cancel the previous pending restore before scheduling a new one (BUG-17).
    private var sinkRestoreRunnable: Runnable? = null

    // Generation counter: lets a stale STATE_IDLE event or safety-net timeout
    // from a previous reset be ignored once a newer reset supersedes it.
    private var sinkResetGeneration = 0
    private var usbProbeRetries = 0

    // Set while a reset is waiting for the player to reach STATE_IDLE (the
    // AudioTrack has been released) before rebuilding the sink.
    private var awaitingSinkIdle = false
    private var awaitingSinkGeneration = 0
    private var resetRetries = 0

    fun resetAudioSink() {
        val player = mediaSession?.player as? ExoPlayer
        if (player == null) {
            // The service just started and the player is still being built —
            // retry instead of silently dropping the reset (the UI already told
            // the user the reset is happening). Bounded so a broken state does
            // not spin forever.
            if (++resetRetries > MAX_RESET_RETRIES) {
                resetRetries = 0
                Log.w(TAG, "resetAudioSink: player never became ready, giving up")
                return
            }
            mainHandler.postDelayed({ resetAudioSink() }, USB_RESET_GAP_MS)
            return
        }
        resetRetries = 0
        if (++sinkResetGeneration == Int.MAX_VALUE) sinkResetGeneration = 1
        val generation = sinkResetGeneration

        // Cancel any in-flight restore from a previous call before we stop the
        // player again, so the restore does not run against a stale item list.
        sinkRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        sinkRestoreRunnable = null
        awaitingSinkIdle = false

        // Capture the user's play INTENT, not isPlaying. A stream that is still
        // buffering reports isPlaying == false (it is only true in STATE_READY),
        // so restoring it without play() would leave it paused forever. This is
        // exactly what broke resume of radio stations on restart: the startup
        // DAC reset fired 1.5s after the service started while the auto-resumed
        // station was still buffering (BUG-20).
        val playWhenReady = player.playWhenReady
        val index      = player.currentMediaItemIndex
        // Live streams (duration == TIME_UNSET) report an unbounded position;
        // restoring it would seek past the end of a seekable station (BUG-19).
        val position   = if (player.duration == C.TIME_UNSET) 0L else player.currentPosition
        val items      = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

        Log.i(TAG, "Resetting audio sink [playing=${player.isPlaying} playWhenReady=$playWhenReady idx=$index pos=$position]")

        // Pause + stop releases the AudioTrack → HAL session is closed
        player.pause()
        Log.i(TAG, "stopPlayer from resetAudioSink")
        player.stop()

        val restoreRunnable = Runnable {
            sinkRestoreRunnable = null
            awaitingSinkIdle = false
            if (sinkResetGeneration != generation) return@Runnable
            val p = mediaSession?.player as? ExoPlayer ?: return@Runnable
            if (items.isNotEmpty()) {
                // Items first: restoring playWhenReady fires the pause-resume
                // listener, which auto-prepares — it must target these items.
                p.setMediaItems(items, index, position)
                p.playWhenReady = playWhenReady
                // Skip prepare() when paused with a USB DAC attached: it would
                // attach an AudioTrack and pin the DAC's rate (direct-only HAL),
                // blocking other apps' audio while the player sits paused.
                if (playWhenReady) {
                    p.prepare()
                    p.play()
                }
            } else {
                // Restore the play intent even when there is nothing queued yet,
                // so a later setMediaItems() call does not inherit the pause() state.
                p.playWhenReady = playWhenReady
            }
            Log.i(TAG, "Audio sink reset complete")
        }

        if (player.playbackState == Player.STATE_IDLE) {
            // Already idle: no AudioTrack is active, so stop() will not emit a
            // STATE_IDLE event to latch onto — restore on the plain gap.
            sinkRestoreRunnable = restoreRunnable
            mainHandler.postDelayed(restoreRunnable, USB_RESET_GAP_MS)
            return
        }

        // Otherwise wait for the player to actually reach STATE_IDLE — the sink
        // is released before that event is emitted — then rebuild after a short
        // grace period so the USB HAL finishes tearing down the old session.
        // A fixed delay alone is unreliable: on slow boxes teardown can outlast
        // it and the new AudioTrack reopens the stale rate (no audible change).
        // The safety net below covers the case where IDLE never lands.
        awaitingSinkIdle = true
        awaitingSinkGeneration = generation
        sinkRestoreRunnable = restoreRunnable
        mainHandler.postDelayed({
            if (sinkResetGeneration == generation &&
                sinkRestoreRunnable === restoreRunnable && awaitingSinkIdle) {
                awaitingSinkIdle = false
                restoreRunnable.run()
            }
        }, USB_RESET_IDLE_TIMEOUT_MS)
    }

    // ── Bit-perfect audio processor chain ─────────────────────────────────────

    /**
     * Replaces DefaultAudioSink's default AudioProcessorChain.
     *
     * Why this exists: DefaultAudioSink.DefaultAudioProcessorChain *always*
     * appends a SilenceSkippingAudioProcessor and a SonicAudioProcessor
     * internally — there is no supported way to opt out of them by passing
     * an empty processor array, because the two-arg private constructor that
     * takes them isn't exposed. SonicAudioProcessor is what ExoPlayer uses to
     * time-stretch/pitch-shift audio when playback speed departs from 1.0x
     * (e.g. live-edge catch-up). Even though nothing in this codebase
     * currently sets a non-default PlaybackParameters, leaving Sonic in the
     * graph means a single future call to player.setPlaybackParameters(...)
     * — or a library/live-streaming feature added later — would silently
     * start stretching PCM and break bit-perfect output with zero warning.
     *
     * This chain removes that possibility structurally:
     *   - getAudioProcessors() returns an empty array, so no processor ever
     *     touches the PCM stream — it passes through completely unmodified.
     *   - applyPlaybackParameters() ignores whatever is requested and always
     *     reports PlaybackParameters.DEFAULT (speed=1.0, pitch=1.0) back to
     *     ExoPlayer, so even a stray setPlaybackParameters() call is a no-op
     *     at the sink level instead of engaging a stretch algorithm.
     *   - applySkipSilenceEnabled() is likewise hard-pinned to false.
     *
     * The tradeoff, by design: if the network/buffer can't keep up, ExoPlayer
     * has no speed-adjustment escape hatch left, so it stalls into
     * STATE_BUFFERING instead. That's the correct behavior for a bit-perfect
     * player — a silent pause is honest, a stretched sample is not.
     */
    private object BitPerfectAudioProcessorChain : AudioProcessorChain {
        private val NO_PROCESSORS = emptyArray<AudioProcessor>()

        override fun getAudioProcessors(): Array<AudioProcessor> = NO_PROCESSORS

        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
            if (playbackParameters != PlaybackParameters.DEFAULT) {
                Log.w(
                    TAG_BITPERFECT,
                    "Ignoring non-1.0x PlaybackParameters request " +
                        "(speed=${playbackParameters.speed}, pitch=${playbackParameters.pitch}) " +
                        "— bit-perfect chain has no time-stretch processor"
                )
            }
            return PlaybackParameters.DEFAULT
        }

        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

        override fun getMediaDuration(playoutDurationUs: Long): Long = playoutDurationUs

        override fun getSkippedOutputFrameCount(): Long = 0L
    }

    // ── Player factory ────────────────────────────────────────────────────────

    private lateinit var httpFactory: OkHttpDataSource.Factory

    /**
     * Short-timeout HTTP source used only to read station playlist links
     * (`.pls`/`.m3u`). It shares the stream client's redirect/UA behaviour but
     * must not sit on a dead link for the full [HTTP_TIMEOUT_SECS].
     */
    private lateinit var playlistHttpFactory: OkHttpDataSource.Factory

    private fun buildPlayer(): ExoPlayer {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val offloadProvider = BitPerfectOffloadProvider(am)
        val selectedAudioDevice = AtomicReference<android.media.AudioDeviceInfo?>(null)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                if (getAudioOutputMode() == AUDIO_OUTPUT_STANDARD_ANDROID) {
                    // Standard Android output: the plain system audio pipeline, exactly
                    // as any other app would use it — normal mixer, normal
                    // resampling/ducking, no forced float encoding, no direct/bit-perfect
                    // AudioTrack and no bit-perfect mixer-attribute negotiation.
                    return DefaultAudioSink.Builder(context).build()
                }

                val sink = DefaultAudioSink.Builder(context)
                .setEnableAudioTrackPlaybackParams(false)
                .setEnableFloatOutput(true)
                .setAudioProcessorChain(BitPerfectAudioProcessorChain)
                .setAudioOffloadSupportProvider(offloadProvider)
                .setAudioTrackProvider(object : DefaultAudioSink.AudioTrackProvider {
                    override fun getAudioTrack(
                        audioTrackConfig: AudioSink.AudioTrackConfig,
                        audioAttributes: AudioAttributes,
                        audioSessionId: Int
                    ): android.media.AudioTrack {
                        bitPerfectManager.updateAudioTrack(
                            audioTrackConfig,
                            selectedAudioDevice.get()
                        )
                        return DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                            audioTrackConfig, audioAttributes, audioSessionId
                        )
                    }
                })
                .build()
                val bitPerfectSink = BitPerfectAudioSink(
                    sink, bitPerfectManager, selectedAudioDevice
                )
                return when (getAudioOutputMode()) {
                    AUDIO_OUTPUT_BITPERFECT_ANDROID -> {
                        // Bit-perfect via Android: direct AudioTrack targeting the
                        // DAC at its native rate through the system media stack.
                        bitPerfectSink
                    }
                    else -> {
                        // Bit-perfect (USB driver): userspace USB Audio 2.0 driver
                        // (usbdevfs) bypasses the Android audio stack when a USB DAC
                        // is present. The BitPerfectAudioSink below is the delegate
                        // (muted, routed to speaker) so ExoPlayer's clock/position
                        // tracking still works while the driver owns the DAC.
                        com.decent.usbaudio.media3.UsbAudioSink(
                            bitPerfectSink,
                            context,
                            com.decent.usbaudio.media3.UsbAudioSinkConfig(
                                bitPerfectEnabled = true,
                                // Keep the delegate's AudioTrack on the built-in speaker so a
                                // stale mix rate never pins the DAC while the driver owns it.
                                forceRouteToSpeaker = true,
                                // Whether to soft-replug (USBDEVFS_RESET) when handing the
                                // DAC back. Default on; switchable because re-creating the
                                // ALSA card leaves some boxes unable to reach the DAC from
                                // any other app until a reboot.
                                resetOnRelease = usbResetOnRelease(),
                                // The service asks for the DAC before playback starts
                                // (requestUsbPermissionIfNeeded) and holds auto-resume
                                // until the answer. The sink's own request in configure()
                                // only ever popped the dialog after a track had started,
                                // and again on every track after a denial.
                                requestPermissionOnConfigure = false
                            ),
                            // Sync the delegate's guard with the driver's actual USB ownership:
                            // while the usbdevfs driver streams, BitPerfectAudioSink must not
                            // open its own direct AudioTrack on the DAC.
                            onDriverOwnsUsbDeviceChanged = { owns ->
                                // The sink now reports ownership from the single place
                                // the claims are taken, and reclaimUsbStream() re-reports
                                // it immediately afterwards — an exact repeat is a no-op,
                                // so skip it rather than log a phantom transition and
                                // re-run the wake-lock update. Both mirrors are checked:
                                // whichever is stale gets corrected.
                                if (owns != bitPerfectSink.driverOwnsUsbDevice ||
                                        owns != this@PlaybackService.usbDriverOwnsDac) {
                                    bitPerfectSink.driverOwnsUsbDevice = owns
                                    this@PlaybackService.usbDriverOwnsDac = owns
                                    Log.i(TAG, "UsbAudioSink driver ownership changed: releasedToDriver=$owns")
                                    updatePlaybackWakeLock()
                                    if (owns) logUsbRoute("driver took the DAC (its card is gone while it streams)", staleExpected = true)
                                    else logUsbRoute("driver handing the DAC back", staleExpected = true)
                                }
                            }
                        ).also {
                            this@PlaybackService.usbAudioSink = it
                        }
                    }
                }
            }
        }

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val dataSourceFactory = DataSource.Factory {
            AppDataSource(this, httpFactory, playlistHttpFactory)
        }

        val mediaSourceFactory = SacdMediaSourceFactory(
            DefaultMediaSourceFactory(this, extractorsFactory)
                .setDataSourceFactory(dataSourceFactory),
            context = this,
            // DSF/DFF files read through the same data sources (local, content://, SMB).
            dataSourceFactory = dataSourceFactory
        )

        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val builder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .experimentalSetDynamicSchedulingEnabled(true)

        if (settings.getBoolean(KEY_NETWORK_BUFFER, true)) {
            val lc = DefaultLoadControl.Builder()
                .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_PLAYBACK_MS, BUFFER_REBUFFER_MS)
                .setTargetBufferBytes(BUFFER_MAX_BYTES)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            if (usbDevfsDriverEnabled()) {
                builder.setLoadControl(wrapLoadControlForUsb(lc))
            } else {
                builder.setLoadControl(lc)
            }
        }

        val player = builder.build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    // Offload routes audio through the DSP and bypasses the bit-perfect
                    // mixer, so it must stay disabled for bit-perfect playback.
                    .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build()
            )
            .build()

        return player.also {
            it.addListener(playerListener)
            if (usbDevfsDriverEnabled()) {
                // Attach the usbdevfs driver to the player. It registers its own
                // Player.Listener for track-path extraction + native engine lifecycle.
                usbAudioSink?.attachToPlayer(it)
                Log.i(TAG, "UsbAudioSink attached to player")
            }
        }
    }

    // ── Player listener ───────────────────────────────────────────────────────

    /** Acquire the playback wake lock (idempotent — reference-counting disabled). */
    private fun acquirePlaybackWakeLock() {
        playbackWakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    /** Release the playback wake lock (idempotent). */
    private fun releasePlaybackWakeLock() {
        playbackWakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * Keep the CPU/device awake while audio is actually being produced, across
     * all three output modes. Media3's [Player.isPlaying] is only true in
     * STATE_READY; with the usbdevfs driver the native engine can be streaming
     * while ExoPlayer sits in STATE_BUFFERING (loading is blocked) — and since
     * the bitstream bypasses Android's AudioTrack there is no implicit audio
     * wake lock either — so the Shield's standby timer is free to fire and it
     * powers the DAC down mid-listening. Hold the lock for playWhenReady in
     * READY/BUFFERING and additionally while the usbdevfs driver owns the DAC.
     *
     * Safe from any thread: ExoPlayer's accessors throw IllegalStateException
     * when called off the application thread, and this also gets invoked from
     * the playback/background threads via onDriverOwnsUsbDeviceChanged, so the
     * actual evaluation is always deferred to the main thread.
     */
    private fun updatePlaybackWakeLock() {
        mainHandler.post {
            val p = mediaSession?.player
            val exoRequested = p != null && p.playWhenReady &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            val hold = exoRequested || usbDacClaimedByDriver()
            if (hold) acquirePlaybackWakeLock() else releasePlaybackWakeLock()
            updateSleepSuppression(hold)
        }
    }

    /**
     * Main thread only. Keeps the Android TV box out of standby while [active]:
     * publishes [keepAwake] (→ FLAG_KEEP_SCREEN_ON in the visible activity,
     * blocking the normal inactivity timeout) and suspends the inattentive-sleep
     * timer when WRITE_SECURE_SETTINGS has been granted. When playback stops,
     * the user's attentive_timeout is put back after a short grace so track
     * changes and brief pauses do not flip the device setting back and forth.
     */
    private fun updateSleepSuppression(active: Boolean) {
        if (sleepSuppressionShutDown) {
            publishKeepAwake(false)
            return
        }
        val wasActive = keepAwake
        publishKeepAwake(active)
        if (active) {
            mainHandler.removeCallbacks(attentiveRestoreRunnable)
            if (!suppressAttentiveSleep(this) && !attentiveHintLogged &&
                attentiveSleepStatus(this) == AttentiveSleepStatus.NEEDS_GRANT) {
                attentiveHintLogged = true
                Log.w(TAG, "Android TV inattentive-sleep timer is active and cannot be suspended — " +
                    "the box may still sleep mid-playback. Grant once: adb shell pm grant " +
                    "$packageName android.permission.WRITE_SECURE_SETTINGS")
            }
        } else if (wasActive) {
            mainHandler.removeCallbacks(attentiveRestoreRunnable)
            mainHandler.postDelayed(attentiveRestoreRunnable, ATTENTIVE_RESTORE_GRACE_MS)
        }
    }

    /** When the last DoP recovery was attempted (see [recoverFromDopError]). */
    private var lastDopRecoveryMs = 0L

    /**
     * A DoP stream was refused (the USB driver could not open the DAC at
     * dsd_rate/16) or lost (the driver let go of the DAC mid-stream): the
     * sinks never let DoP reach a mixer or speaker, they fail instead. Retry
     * the same item at the same position: once as DoP (the driver re-claims
     * the DAC), and as PCM if it fails again soon or was refused outright.
     */
    private fun recoverFromDopError(error: androidx.media3.common.PlaybackException, player: Player): Boolean {
        val exo = error as? androidx.media3.exoplayer.ExoPlaybackException ?: return false
        if (exo.type != androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER) return false
        if (exo.rendererFormat?.label?.contains("DoP") != true) return false
        val now = SystemClock.elapsedRealtime()
        val refused = exo.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        if (refused || now - lastDopRecoveryMs < DOP_RETRY_WINDOW_MS) {
            dopSuppressed = true
            Log.w(TAG, "DoP could not be delivered bit-exactly — continuing this track as PCM")
        } else {
            Log.w(TAG, "DoP stream interrupted — re-preparing")
        }
        lastDopRecoveryMs = now
        // Re-preparing creates the media periods again, so the DoP/PCM choice
        // (SacdMediaSourceFactory.dopActive) is re-evaluated.
        mainHandler.post {
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
                player.play()
            }
        }
        return true
    }

    /** Identity of a session controller, for the diagnostics above. */
    private fun describeController(controller: MediaSession.ControllerInfo): String =
        "pkg=${controller.packageName} uid=${controller.uid} trusted=${controller.isTrusted} " +
            "version=${controller.controllerVersion} " +
            "legacy=${controller.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME} " +
            "maxCommands=${controller.maxCommandsForMediaItems} hints=${controller.connectionHints.keySet()}"

    /** Name for a `Player` command, used by the command/stop diagnostics below. */
    private fun commandName(command: Int): String = when (command) {
        Player.COMMAND_STOP -> "STOP"
        Player.COMMAND_PLAY_PAUSE -> "PLAY_PAUSE"
        Player.COMMAND_PREPARE -> "PREPARE"
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> "SEEK_TO_DEFAULT_POSITION"
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "SEEK_TO_PREVIOUS_MEDIA_ITEM"
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "SEEK_TO_NEXT_MEDIA_ITEM"
        else -> "cmd=$command"
    }

    /** Name for a playback state, for the state-transition diagnostic. */
    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "state=$state"
    }

    /**
     * Diagnostic wrapper around ExoPlayer.stop(). The usbdevfs "plays about a
     * second and then stops" report surfaced as a STATE_IDLE with no matching log
     * line from any stop() call site in this service, so record which one it was.
     */
    private fun stopPlayer(caller: String) {
        Log.i(TAG, "stopPlayer from $caller")
        try { mediaSession?.player?.stop() } catch (e: Exception) { Log.w(TAG, "stopPlayer failed", e) }
    }

    private val playerListener = object : Player.Listener {
        private var retryCount = 0
        private var lastLoggedState = Player.STATE_IDLE

        override fun onEvents(player: Player, events: Player.Events) {
            // Media3 promotes/demotes its own media notification as playback
            // starts/stops — keep our LAN keep-foreground in sync.
            updateLanForeground()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Keep the device awake while audio is being produced so the Shield
            // does not suspend (and power off USB, dropping the DAC) mid-listening.
            if (isPlaying) cancelIdleHandBack("isPlaying")
            updatePlaybackWakeLock()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName} — ${error.message}", error)
            // The current AudioTrack is no longer usable. Do not leave its preferred
            // mixer attributes active while reconnecting or moving to another item.
            bitPerfectManager.clear()
            val player = mediaSession?.player ?: return
            if (recoverFromDopError(error, player)) return
            val autoReconnect = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RECONNECT, true)
            val isStream = player.currentMediaItem?.mediaId
                ?.let { it.startsWith("http://") || it.startsWith("https://") } == true

            if (autoReconnect && isStream && retryCount < MAX_RETRIES) {
                retryCount++
                Log.i(TAG, "Reconnect $retryCount/$MAX_RETRIES")
                mainHandler.postDelayed({
                    if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.play() }
                }, RECONNECT_DELAY_MS)
                return
            }
            retryCount = 0
            if (player.hasNextMediaItem()) { player.seekToNext(); player.prepare(); player.play() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retryCount = 0
            saveLastPlayed(mediaItem)
            resetIcyInfo(mediaItem?.mediaId)
            if (mediaItem == null) bitPerfectManager.clear()
        }

        override fun onMetadata(metadata: Metadata) {
            val mediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                val title = (if (entry is IcyInfo) entry.title else null)?.trim()
                if (!title.isNullOrBlank()) {
                    try {
                        mergeIcyInfo(mediaId, title = title)
                        Log.d(TAG, "ICY title: $title")
                    } catch (e: Exception) { Log.e(TAG, "ICY title merge failed", e) }
                }
            }
            // Push the captured stream info to the MediaSession so system surfaces
            // (Shield home Now Playing bar, other controllers) show the real
            // track/artist instead of "Bitperfect Player - Unknown".
            publishIcyMetadataToSession()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                // A resume cancels a debounced pause/idle hand-back: the DAC is
                // wanted again, so tearing the USB stream down now would only
                // cause a gap before it is re-claimed.
                cancelIdleHandBack("playWhenReady=true reason=$reason")
                // Baseline for the hand-back diagnostics: the address Android
                // routes the DAC to, and the kernel's card, before the claim.
                if (usbDevfsDriverEnabled() && !usbDacClaimedByDriver()) {
                    logUsbRoute("before the driver claims the DAC")
                }
                // Resume after a pause that released the track: play() from
                // STATE_IDLE does not auto-prepare in Media3, so prepare
                // explicitly to re-negotiate the stream (fresh bit-perfect
                // session, or none at all when the DAC is gone).
                val p = mediaSession?.player ?: return
                if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) {
                    p.prepare()
                }
                updatePlaybackWakeLock()
                return
            }
            saveCurrentPosition()
            // reason=2 (audio focus loss) / 3 (becoming noisy): the system or
            // another app wants the DAC now, so hand it back immediately. Only a
            // user pause/stop is debounced, so a quick pause→play keeps the
            // stream instead of tearing it down (see scheduleIdleHandBack).
            val systemWantsDac =
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            releaseDacForInactivity("playWhenReady=false (reason=$reason)", immediate = systemWantsDac)
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) return
            // Another app took *transient* focus (it plays something now and hands
            // focus back later). Media3 keeps playWhenReady=true in that case, so
            // onPlayWhenReadyChanged never fires and the usbdevfs driver kept its
            // exclusive claims on the DAC — the app that had just taken focus was
            // silent. Drop the claims so it can play. No stop() here: an IDLE player
            // abandons audio focus, and Media3 would then never get it back to
            // resume. When focus returns, UsbAudioSink re-claims the DAC on the next
            // buffer (reclaimAfterIdleRelease).
            Log.i(TAG,
                "Transient audio-focus loss — handing the DAC back " +
                    "(driverOwns=${usbDacClaimedByDriver()})")
            saveCurrentPosition()
            releaseUsbDriverDac()
            updatePlaybackWakeLock()
        }

        /**
         * Playback was paused (by the user, or by another app taking audio focus while
         * this one is in the background): give the DAC back to the rest of the system.
         */
        private fun releaseDacForInactivity(reason: String, immediate: Boolean = false) {
            // Decide *before* the release whether a USB DAC is attached. The old
            // check asked AudioManager after releaseUsbDriverDac() — but that release
            // is asynchronous, and while the usbdevfs driver still holds its claims
            // the DAC is not in AudioManager's device list at all. So whenever the
            // driver owned the DAC (i.e. every pause in USB-driver mode, including the
            // one triggered by another app taking audio focus after Home), stop() was
            // skipped: the player stayed prepared with its muted delegate AudioTrack
            // attached. When the DAC re-registered, that track moved onto it and — on
            // the Android 11 direct-only usb_audio HAL — pinned it to our rate, so the
            // app that had just taken focus stayed silent. UsbManager lists the DAC
            // whether or not it is claimed.
            val dacAttached = usbDacClaimedByDriver() ||
                usbDacHandBackInFlight() ||
                findUsbAudioDevice(this@PlaybackService) != null ||
                bitPerfectManager.findUsbOutputDevice() != null
            Log.i(TAG,
                "releaseDacForInactivity: $reason — dacAttached=$dacAttached " +
                    "driverOwns=${usbDacClaimedByDriver()}")
            // The Android 11 usb_audio HAL is direct-only: while our
            // AudioTrack is attached, the DAC's output is pinned to the
            // track's sample rate and every other app's 48kHz stream fails
            // with EINVAL ("Bad parameter: sampleRate 48000") — no sound
            // anywhere else. Media3 pauses the track but keeps it attached,
            // and the framework's dead-object auto-restore re-attaches it,
            // so the lock persists after pause and after sleep/wake.
            // Release the track on pause so the DAC returns to the default
            // mix rate; play() above re-prepares and re-negotiates fresh.
            // With the usbdevfs driver, also drop its force-claims so other
            // apps get the DAC back while we are paused — but debounced, so a
            // pause immediately followed by play does not tear down the stream
            // that is about to be needed again (see scheduleIdleHandBack).
            // A focus loss is not a pause: the other app needs the DAC now, so
            // it is released immediately instead of waiting out the debounce.
            if (immediate) releaseUsbDriverDac() else scheduleIdleHandBack(reason)
            updatePlaybackWakeLock()
            if (dacAttached) {
                stopPlayer("releaseDacForInactivity")
                // stop() fires STATE_IDLE, whose listener schedules a debounced
                // hand-back; that must not follow the immediate release above.
                if (immediate) cancelIdleHandBack("immediate system release")
            }
        }

        private var wasBuffering = false

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "playbackState ${stateName(lastLoggedState)} -> ${stateName(playbackState)}")
            lastLoggedState = playbackState
            updatePlaybackWakeLock()
            // Entering BUFFERING/READY with a live, unsuppressed play intent
            // means we are (re)starting playback: a debounced pause/idle
            // hand-back must not fire now. This is the signal that catches an
            // internet-radio station change — clear→add→play is instant, but the
            // stream then buffers for longer than the debounce while isPlaying is
            // still false, so onIsPlayingChanged/onPlayWhenReadyChanged alone are
            // too late.
            val p = mediaSession?.player
            if ((playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) &&
                p?.playWhenReady == true &&
                p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                cancelIdleHandBack("STATE_${if (playbackState == Player.STATE_BUFFERING) "BUFFERING" else "READY"}")
            }
            // A reset waits for STATE_IDLE: that is the moment stop() has
            // released the AudioTrack, so the rebuild can safely reopen the USB
            // session with the current track's rate (without this, the rebuilt
            // sink can inherit the stale Android-default rate and the reset
            // appears to do nothing on slow HAL teardowns).
            if (playbackState == Player.STATE_IDLE && awaitingSinkIdle) {
                awaitingSinkIdle = false
                if (awaitingSinkGeneration == sinkResetGeneration) {
                    sinkRestoreRunnable?.let { restore ->
                        mainHandler.removeCallbacks(restore)
                        mainHandler.postDelayed(restore, USB_RESET_TEARDOWN_GRACE_MS)
                    }
                }
            }
            if (playbackState == Player.STATE_READY) retryCount = 0
            if (playbackState == Player.STATE_READY && p?.playWhenReady == true) {
                warnIfUsbRouteStale("playback started")
            }
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
                saveCurrentPosition()

            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                // Stop/end releases the active audio pipeline. Keep the preference scoped
                // to an active track rather than leaving it set for the service lifetime.
                bitPerfectManager.clear()
                // Same for the usbdevfs driver: drop the DAC claims once the queue
                // stops/ends so the system can use the DAC again — debounced, so a
                // stop immediately followed by play (clear→add→play, station
                // change) keeps the stream instead of tearing it down and
                // rebuilding it. The paths that must hand back now (exit,
                // output-mode change) call releaseUsbDriverDac() directly, which
                // cancels this.
                //
                // The debounce only earns its keep when something could resume. With
                // an empty queue and no play intent there is nothing to ride out, and
                // holding the DAC for another 1.2 s keeps the kernel driver detached
                // from the device for that much longer — which is the state that
                // silences every other app. Hand it back now in that case.
                val player = mediaSession?.player
                if (player != null && player.mediaItemCount == 0 && !player.playWhenReady) {
                    Log.i(TAG, "Empty queue on ${if (playbackState == Player.STATE_IDLE) "IDLE" else "ENDED"} — releasing the DAC immediately")
                    releaseUsbDriverDac()
                } else {
                    scheduleIdleHandBack("STATE_${if (playbackState == Player.STATE_IDLE) "IDLE" else "ENDED"}")
                }
            }

            // Bit-perfect design note: a buffer underrun surfaces here as a plain
            // stall (STATE_BUFFERING → playback pauses) rather than a speed-up
            // to catch up, since BitPerfectAudioProcessorChain removes the
            // time-stretch path entirely. Logged so stalls are visible/countable
            // instead of silently absorbed.
            if (playbackState == Player.STATE_BUFFERING && !wasBuffering) {
                wasBuffering = true
                Log.i(TAG_BITPERFECT, "Buffer underrun — stalling playback (no speed-up, bit-perfect chain)")
            } else if (playbackState != Player.STATE_BUFFERING && wasBuffering) {
                wasBuffering = false
                Log.i(TAG_BITPERFECT, "Stall recovered — playback resumed at 1.0x")
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Should never fire with anything but DEFAULT given BitPerfectAudioProcessorChain,
            // but log loudly if it ever does — that would indicate a regression.
            if (playbackParameters != PlaybackParameters.DEFAULT) {
                Log.e(
                    TAG_BITPERFECT,
                    "UNEXPECTED: playback parameters changed to speed=${playbackParameters.speed} " +
                        "pitch=${playbackParameters.pitch} — bit-perfect guarantee may be violated"
                )
            }
        }

    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        bitPerfectManager = BitPerfectManager(this)
        // Keep the separate USB monitor alive when the player is started headless
        // (MPD/media session) rather than through MainActivity.
        UsbRecoveryService.sync(this)

        // A previous process may have died (crash / force-stop / System.exit)
        // while it had attentive_timeout suspended — put the user's value back.
        restoreAttentiveSleep(this, "stale override from previous process")

        // PARTIAL_WAKE_LOCK: keeps the CPU awake during playback. On Android TV
        // it does not keep the box out of standby by itself — the two standby
        // timers are handled separately (see updateSleepSuppression()):
        //   - screen_off_timeout / sleep_timeout: blocked by FLAG_KEEP_SCREEN_ON
        //     on the visible activity (a screen-level wake lock would also block
        //     it, which is why the earlier SCREEN_DIM lock "half worked");
        //   - attentive_timeout: ignores ALL wake locks and window flags; only
        //     writing the setting (WRITE_SECURE_SETTINGS) suspends it.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        playbackWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitperfectPlayer:playback")
            .apply { setReferenceCounted(false) }

        // The usbdevfs driver only streams when the DAC is already granted to us.
        // Request permission up front (the system dialog shows on the TV screen) so
        // playback doesn't silently fall back to the AudioTrack path on the first
        // track. This runs before the MediaSession exists, so by the time any
        // controller connects usbPermissionPending is already set and the UI's
        // auto-resume waits for the answer (runWhenUsbPermissionSettled).
        requestUsbPermissionIfNeeded("service start")

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        httpFactory = OkHttpDataSource.Factory(httpClient).setUserAgent(USER_AGENT)
        playlistHttpFactory = OkHttpDataSource.Factory(
            httpClient.newBuilder()
                .connectTimeout(PLAYLIST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(PLAYLIST_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .build()
        ).setUserAgent(USER_AGENT)

        mediaSession = MediaSession.Builder(this, buildPlayer())
            .setCallback(object : MediaSession.Callback {
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val resume = try {
                        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
                        if (!settings.getBoolean(KEY_RESUME_PLAYBACK, false)) null
                        else settings.getString("last_played_queue", null)?.let { queueJson ->
                            val items = rebuildSavedQueueItems(JSONArray(queueJson))
                            if (items.isEmpty()) null
                            else {
                                val index = settings.getInt("last_played_index", 0).coerceIn(0, items.lastIndex)
                                val pos = settings.getLong("last_played_pos", 0)
                                val restorePos = if (items[index].mediaId.startsWith("http://") || items[index].mediaId.startsWith("https://")) 0L else pos
                                MediaSession.MediaItemsWithStartPosition(items, index, restorePos)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Playback resumption failed: ${e.message}")
                        null
                    }
                    return if (resume != null) {
                        Log.i(TAG, "Playback resumption: restored ${resume.mediaItems.size} items")
                        Futures.immediateFuture(resume)
                    } else {
                        Futures.immediateFailedFuture(UnsupportedOperationException())
                    }
                }

                override fun onPostConnect(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    Log.d(TAG, "Controller connected: ${describeController(controller)}")
                }

                override fun onMediaButtonEvent(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    // A KEYCODE_MEDIA_STOP delivered here is translated by Media3 into
                    // Player.stop() (MediaSessionImpl.applyMediaButtonKeyEvent ->
                    // MediaSessionStub.stopForControllerInfo), which tears the
                    // bit-perfect USB stream down and hands the DAC back. On the Xiaomi
                    // TV Box such an event arrives on its own a second or two into
                    // playback — the whole "plays about a second and then stops" report
                    // — so record who sent it and deliberately do not honour it.
                    @Suppress("DEPRECATION")
                    val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    if (event != null) {
                        val source = runCatching { InputDevice.getDevice(event.deviceId)?.name }.getOrNull()
                        Log.i(TAG, "Media button: action=${event.action} keyCode=${event.keyCode} " +
                            "repeat=${event.repeatCount} deviceId=${event.deviceId} device='$source' " +
                            "source=0x${Integer.toHexString(event.source)} intent=${intent.action}")
                        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                            Log.i(TAG, "Media button STOP ignored — honouring it would stop playback and drop the DAC")
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaSession, controller, intent)
                }

                override fun onDisconnected(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    Log.d(TAG, "Controller disconnected: ${describeController(controller)}")
                }

                override fun onPlayerCommandRequest(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    // Verifies that nothing sends this session a command that stops
                    // playback behind the app's back (the usbdevfs session used to be
                    // killed by an injected KEYCODE_MEDIA_STOP — see onMediaButtonEvent).
                    Log.d(TAG, "Player command ${commandName(playerCommand)} from ${describeController(controller)}")
                    return super.onPlayerCommandRequest(mediaSession, controller, playerCommand)
                }
            })
            .build()

        // Build the jcifs SMB context off the main thread so the first media-source
        // creation (which can run on the main thread during addMediaItems) is cheap.
        // This must happen AFTER the OkHttpClient is built: pre-warming registers the
        // BouncyCastle provider, and doing that concurrently with OkHttp's TLS setup
        // makes SSLContext.init fail with "BKS not found".
        Thread { SmbContext.prewarm() }.start()
        mainHandler.post(savePositionRunnable)

        // Restore the saved queue right away (before the MPD server is reachable)
        // so LAN clients never see a briefly-empty playlist after a process
        // restart — previously only MainFragment restored it, seconds later
        // (BUG: playlist items disappear in MALP for a few seconds).
        restoreSavedQueue()

        // Listen for USB plug/unplug events
        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        // Listen for sleep/wake so the audio track / bit-perfect mixer preference is
        // not left holding the USB DAC after the device suspends (it would pin
        // the DAC's sample rate and block every other app's audio after wake).
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })

        // Re-negotiate the sink the moment the USB DAC's audio side (re)appears
        // after suspend — the re-enumeration is slow and flaky, and the wake
        // check above can miss the window.
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .registerAudioDeviceCallback(usbDeviceCallback, mainHandler)

        // On startup: if a USB DAC is already connected, reset the sink so
        // Android HAL negotiates bit-perfect output from the very first track.
        mainHandler.postDelayed({ checkAndResetUsbAudio("startup") }, USB_SETTLE_MS)

        // Embedded MPD server for MALP / any MPD client (port 6600).
        // Runs on its own threads and drives playback only via MediaController
        // — never touches the bit-perfect audio chain.
        updateMpdServer()

        // Keep-alive watchdog: re-assert foreground state if the system drops it.
        mainHandler.post(lanFgWatchdog)
    }

    fun isMpdRunning(): Boolean = mpdServer != null
    fun getClientCount(): Int = mpdServer?.getClientCount() ?: 0

    /** Stops playback but keeps the service (and MPD server) alive for LAN control. */
    fun stopPlaybackKeepLanControl() {
        mainHandler.post {
            try {
                mediaSession?.player?.let { p ->
                    p.playWhenReady = false
                    Log.i(TAG, "stopPlayer from stopPlaybackKeepLanControl")
                    p.stop()
                }
            } catch (_: Exception) {}
            updateLanForeground()
        }
    }

    fun updateMpdServer() {
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val enabled = prefs.getBoolean("mpd_enabled", true)
        val port = prefs.getInt("mpd_port", 6600).coerceIn(1024, 65535)
        try {
            if (enabled) {
                // Ensure the service is in the started state so it survives
                // activity unbind / task removal while LAN control is enabled.
                try { startService(Intent(this, PlaybackService::class.java)) } catch (_: Exception) {}
                val curPort = mpdServer?.getPort()
                if (mpdServer == null) {
                    mpdServer = MpdServer(this).also { it.start() }
                    Log.i(TAG, "MPD server started on $port (LAN control enabled)")
                } else if (curPort != null && curPort != port) {
                    mpdServer?.stop()
                    mpdServer = MpdServer(this).also { it.start() }
                    Log.i(TAG, "MPD server restarted on $port (port changed)")
                }
            } else {
                mpdServer?.stop()
                mpdServer = null
                Log.i(TAG, "MPD server stopped (LAN control disabled)")
            }
            updateLanForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update MPD server", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The task went away: if we are not producing audio, hand the DAC back to the
        // system immediately rather than waiting out the idle debounce. While this app
        // holds the usbfs claim the kernel's snd-usb-audio is detached, and other apps
        // are silent for exactly that long — so the sooner we let go, the better.
        if (!isActivelyPlaying()) {
            Log.i(TAG, "onTaskRemoved — releasing the DAC immediately")
            releaseUsbDriverDac()
        }
        // Media3's default stops the service when playback is idle, which kills
        // the MPD server and breaks LAN control after the app UI is closed.
        val prefs = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
        val keepAlive = prefs.getBoolean("mpd_enabled", true) &&
            prefs.getBoolean("mpd_autostart", false)
        if (keepAlive) {
            Log.i(TAG, "Task removed — keeping service alive for LAN control")
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Only this app and trusted system/media controllers may control playback.
        return if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) {
            mediaSession
        } else {
            null
        }
    }

    override fun onDestroy() {
        instance = null
        // The waiters belong to this service's controllers, which are going away
        // with it; a new instance asks again (and gates again) in its onCreate.
        endUsbPermissionWait()
        saveCurrentPosition()
        bitPerfectManager.clear()
        // Hand the DAC back to the system HAL if the usbdevfs driver still owns it.
        // bitPerfectManager.clear() only undoes the Android-mixer bit-perfect path
        // (AUDIO_OUTPUT_BITPERFECT_ANDROID); without this, a service teardown while
        // AUDIO_OUTPUT_USBDEVFS (the default mode) is streaming leaves the driver's
        // raw usbfs claim on the DAC in place, so every other app trying to use that
        // DAC stays silent even though this app has exited.
        releaseUsbDriverDac()
        resetIcyInfo(null)
        try { mpdServer?.stop() } catch (_: Exception) {}
        mpdServer = null
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try {
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.unregisterAudioDeviceCallback(usbDeviceCallback)
        } catch (_: Exception) {}
        mediaSession?.run { player.release(); release() }
        releasePlaybackWakeLock()
        playbackWakeLock = null
        // The pending grace-period restore was dropped with the handler queue
        // above — restore now and let the UI drop FLAG_KEEP_SCREEN_ON.
        sleepSuppressionShutDown = true
        publishKeepAwake(false)
        restoreAttentiveSleep(this, "service destroyed")
        super.onDestroy()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveLastPlayed(mediaItem: MediaItem?) {
        val uri = mediaItem?.mediaId ?: return
        if (uri.startsWith("action:")) return
        val icy = icyInfo
        val title  = if (icy != null && icy.mediaId == uri && !icy.title.isNullOrBlank()) icy.title
                     else mediaItem.mediaMetadata.title?.toString() ?: "Unknown"
        val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
        val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)

        if (settings.getBoolean(KEY_RESUME_PLAYBACK, false)) {
            val player = mediaSession?.player
            val queue  = JSONArray()
            player?.let {
                for (i in 0 until it.mediaItemCount) {
                    val item = it.getMediaItemAt(i)
                    queue.put(JSONObject().apply {
                        val localConfig = item.localConfiguration
                        val clipping = item.clippingConfiguration
                        put("mediaId", item.mediaId)
                        put("uri", localConfig?.uri?.toString() ?: item.mediaId)
                        put("title",   item.mediaMetadata.title?.toString()  ?: "")
                        put("artist",  item.mediaMetadata.artist?.toString() ?: "")
                        put("start", clipping.startPositionMs)
                        put("end", clipping.endPositionMs)
                    })
                }
            }
            settings.edit()
                .putString("last_played_uri",    uri)
                .putString("last_played_title",  title)
                .putString("last_played_artist", artist)
                .putInt(   "last_played_index",  player?.currentMediaItemIndex ?: 0)
                .putLong(  "last_played_pos",    player?.let { safePositionToSave(it) } ?: 0L)
                .putString("last_played_queue",  queue.toString())
                .apply()
        }

        if (settings.getBoolean(KEY_RECENT_FILES, true)) {
            try {
                val arr  = JSONArray(settings.getString("recent_list", "[]"))
                val newArr = JSONArray()
                newArr.put(JSONObject().apply { put("uri", uri); put("title", title); put("artist", artist) })
                for (i in 0 until arr.length()) {
                    if (newArr.length() >= RECENT_LIST_MAX) break
                    if (arr.getJSONObject(i).getString("uri") != uri) newArr.put(arr.getJSONObject(i))
                }
                settings.edit().putString("recent_list", newArr.toString()).apply()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    /**
     * Restores the saved queue/index/position into the player if its queue is
     * empty. Mirrors MainFragment.resumeLastPlayed() but runs at service start,
     * so the MPD server never exposes an empty playlist after a restart.
     */
    private fun restoreSavedQueue() {
        try {
            val settings = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
            if (!settings.getBoolean(KEY_RESUME_PLAYBACK, false)) return
            val player = mediaSession?.player ?: return
            if (player.mediaItemCount > 0) return
            val queueJson = settings.getString("last_played_queue", null) ?: return
            val items = rebuildSavedQueueItems(JSONArray(queueJson))
            if (items.isEmpty()) return
            val index = settings.getInt("last_played_index", 0).coerceIn(0, items.lastIndex)
            val pos = settings.getLong("last_played_pos", 0)
            // Radio streams never have a meaningful position (BUG-19): restart at 0.
            val restorePos = if (items[index].mediaId.startsWith("http://") || items[index].mediaId.startsWith("https://")) 0L else pos
            player.setMediaItems(items, index, restorePos)
            // Prepare only when playback is actually wanted. prepare() with
            // playWhenReady=false still attaches an AudioTrack to the USB DAC
            // (direct-only HAL on Android 11), pinning its rate and blocking
            // every other app's audio while the player sits paused. play()
            // from IDLE auto-prepares, so the queued state is fully preserved.
            if (player.playWhenReady || bitPerfectManager.findUsbOutputDevice() == null) {
                player.prepare()
            }
            Log.i(TAG, "Restored ${items.size} queued items (index $index)")
        } catch (e: Exception) {
            Log.w(TAG, "Queue restore failed: ${e.message}")
        }
    }

    /** Rebuilds [MediaItem]s from the persisted "last_played_queue" JSON array. */
    private fun rebuildSavedQueueItems(queueArray: JSONArray): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        for (i in 0 until queueArray.length()) {
            val entry = queueArray.optJSONObject(i)
            if (entry == null) continue
            val mId = entry.optString("mediaId", "")
            val uri = entry.optString("uri", mId)
            val title = entry.optString("title", "")
            val artist = entry.optString("artist", "")
            val start = entry.optLong("start", 0)
            val end = entry.optLong("end", C.TIME_UNSET)
            if (uri.isEmpty()) continue
            val fallbackTitle = uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { mId }
            val meta = MediaMetadata.Builder().setTitle(title.ifBlank { fallbackTitle })
            if (artist.isNotBlank()) meta.setArtist(artist)
            val b = MediaItem.Builder()
                .setMediaId(mId)
                .setUri(uri.toUri())
                // Keep the MIME type: an item restored without it falls back to the
                // default extractors, which cannot handle DSD/WavPack.
                .setMimeType(PlaylistParser.mimeTypeFor(uri))
                .setMediaMetadata(meta.build())
            if (start > 0 || end != C.TIME_UNSET) {
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
                if (end != C.TIME_UNSET) clip.setEndPositionMs(end)
                b.setClippingConfiguration(clip.build())
            }
            items.add(b.build())
        }
        return items
    }

    /** Merges a piece of ICY info into the current holder (if it belongs to the active item). */
    private fun mergeIcyInfo(
        mediaId: String,
        title: String? = null,
        station: String? = null,
        genre: String? = null,
        description: String? = null,
        url: String? = null
    ) {
        synchronized(icyInfoLock) {
            // A loader can finish after the player has moved to another item. Do not allow
            // that late response to repopulate metadata for the old stream.
            if (activeMediaId != mediaId) return

            val cur = icyInfo
            val merged = if (cur != null && cur.mediaId == mediaId) {
                IcyStreamInfo(
                    mediaId,
                    title ?: cur.title,
                    station ?: cur.station,
                    genre ?: cur.genre,
                    description ?: cur.description,
                    url ?: cur.url
                )
            } else {
                IcyStreamInfo(mediaId, title, station, genre, description, url)
            }
            if (merged != cur) icyInfo = merged
        }
    }

    private fun resetIcyInfo(mediaId: String?) {
        synchronized(icyInfoLock) {
            activeMediaId = mediaId
            icyInfo = null
        }
    }

    /**
     * Publishes the captured stream info (ICY song title / artist / station name)
     * as the current item's metadata so system surfaces — the Shield home screen's
     * Now Playing bar, other media controllers — show the real track and artist
     * instead of the app name + "Unknown".
     */
    private fun publishIcyMetadataToSession() {
        try {
            val player = mediaSession?.player ?: return
            val item = player.currentMediaItem ?: return
            val icy = icyInfo ?: return
            if (icy.mediaId != item.mediaId) return

            val icyTitle = icy.title?.takeIf { it.isNotBlank() }
            var track = icyTitle
            var artist = ""
            val rawTrack = track
            if (rawTrack != null) {
                val delims = arrayOf(" - ", " – ", " — ", " : ", " | ")
                for (d in delims) {
                    if (rawTrack.contains(d)) { val p = rawTrack.split(d, limit = 2); artist = p[0].trim(); track = p[1].trim(); break }
                }
            }
            val station = icy.station?.takeIf { it.isNotBlank() }
            val base = item.mediaMetadata
            val builder = base.buildUpon()
            var changed = false
            // IMPORTANT: never overwrite the queue item's title with the live track —
            // the playlist/queue (and MPD) must keep the stream's own name. The live
            // track is stored in subtitle instead; the Now Playing display already
            // shows the live title via icyInfo / TrackInfoResolver.
            if (!track.isNullOrBlank() && track != base.subtitle) { builder.setSubtitle(track); changed = true }
            if (artist.isBlank() && station != null && station != track) artist = station
            if (artist.isNotBlank() && artist != base.artist) { builder.setArtist(artist); changed = true }
            // Stations repeat the same ICY title on every metadata frame — don't
            // replace the queue item unless something actually changed.
            if (!changed) return
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(builder.build()).build()
            )
        } catch (e: Exception) {
            Log.d(TAG, "publishIcyMetadata failed: ${e.message}")
        }
    }

    // ── Inner data source ─────────────────────────────────────────────────────

    @UnstableApi
    private inner class AppDataSource(
        context: Context,
        httpFactory: HttpDataSource.Factory,
        private val playlistFactory: HttpDataSource.Factory
    ) : DataSource {
        private val defaultSrc = DefaultDataSource.Factory(context, httpFactory).createDataSource()
        private val smbSrc     = SmbDataSource()
        private var active: DataSource? = null
        private var lastUri: String? = null

        override fun addTransferListener(l: TransferListener) { defaultSrc.addTransferListener(l); smbSrc.addTransferListener(l) }
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            lastUri = if (scheme == "http" || scheme == "https") dataSpec.uri.toString() else null
            // Radio directories link some stations as a small PLS/M3U document
            // (…/listen.pls?sid=1). ExoPlayer would feed that text to the audio
            // extractors and raise UnrecognizedInputFormatException ("Source
            // error"), so resolve it to the stream it names before opening. The
            // item keeps its original URL/mediaId, which also keeps ICY info and
            // saved queues matching the station the user queued.
            val playable = if (scheme == "http" || scheme == "https") resolveStationPlaylist(dataSpec) else dataSpec
            active = if (playable.uri.scheme == "smb") smbSrc else defaultSrc
            return active?.open(playable) ?: throw IOException("No data source available")
        }

        /**
         * Follows `.pls`/`.m3u` station links until a stream URL is reached.
         * Only a bounded prefix is read, redirects are followed, and any
         * failure falls back to the original [DataSpec] so the player's normal
         * error/reconnect handling still applies.
         */
        private fun resolveStationPlaylist(dataSpec: DataSpec): DataSpec {
            var current = dataSpec.uri
            if (!StreamPlaylistParser.isPlaylistUrl(current.toString())) return dataSpec
            var spec = dataSpec
            var hops = 0
            while (hops < MAX_PLAYLIST_HOPS && StreamPlaylistParser.isPlaylistUrl(current.toString())) {
                val text = fetchPlaylistText(current) ?: return dataSpec
                val next = StreamPlaylistParser.parse(text, current.toString()) ?: return dataSpec
                current = Uri.parse(next)
                // The resolved stream starts at 0: the original position referred
                // to the (unread) playlist document, not to the audio.
                spec = dataSpec.buildUpon()
                    .setUri(current)
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                hops++
            }
            return spec
        }

        /** Reads at most [MAX_PLAYLIST_BYTES] of [uri], or null on any failure. */
        private fun fetchPlaylistText(uri: Uri): String? {
            val source = playlistFactory.createDataSource()
            return try {
                source.open(DataSpec(uri))
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (total < MAX_PLAYLIST_BYTES) {
                    val n = source.read(buffer, 0, minOf(buffer.size, MAX_PLAYLIST_BYTES - total))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                String(out.toByteArray(), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.d(TAG, "Station playlist link unreadable: ${uri.host}${uri.path ?: ""}: ${e.message}")
                null
            } finally {
                try { source.close() } catch (_: Exception) {}
            }
        }

        override fun read(b: ByteArray, o: Int, l: Int): Int = active?.read(b, o, l) ?: -1
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> {
            val headers = active?.responseHeaders ?: emptyMap()
            val uri = lastUri ?: return headers
            if (headers.isEmpty()) return headers

            // Called on the loading thread: must never throw or touch the player.
            // lastUri is the ORIGINAL item URL (dataSpec.uri from open()), which
            // matches the player item's mediaId even when the stream redirects —
            // merging under it keeps the ICY info matched to the item without
            // touching the player off the main thread (BUG: station name never
            // captured, "Player is accessed on the wrong thread").
            try {
                fun header(name: String): String? =
                    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                        ?.value?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

                mergeIcyInfo(
                    uri,
                    station     = header("icy-name"),
                    genre       = header("icy-genre"),
                    description = header("icy-description"),
                    url         = header("icy-url")
                )
                // Headers arrive on the loading thread; push to the session on main.
                mainHandler.post { publishIcyMetadataToSession() }
            } catch (e: Exception) { Log.e(TAG, "ICY header capture failed", e) }
            return headers
        }
        override fun close() { active?.close(); active = null }
    }
}
