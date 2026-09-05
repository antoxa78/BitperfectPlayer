package com.example.bitperfectplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Live ICY info for the stream currently being played, if any. */
data class IcyStreamInfo(
    val mediaId: String,
    val title: String?,
    val station: String?,
    val genre: String?,
    val description: String?,
    val url: String?
)

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
        // Audio output method (Settings → Audio Output). Migrates from the old
        // boolean KEY_USBDEVFS_DRIVER on first read (true → USBDEVFS, false → BITPERFECT_ANDROID).
        private const val KEY_AUDIO_OUTPUT_MODE = "audio_output_mode"
        private const val AUDIO_OUTPUT_BITPERFECT_ANDROID = 1 // Bit-perfect via Android: direct AudioTrack at native rate
        private const val AUDIO_OUTPUT_USBDEVFS           = 2 // Bit-perfect (USB driver): userspace usbdevfs driver owns the DAC

        private const val HTTP_TIMEOUT_SECS    = 20L
        private const val USER_AGENT           = "BitperfectPlayer/1.1 (Android TV)"
        private const val POSITION_SAVE_MS     = 10_000L
        private const val RECONNECT_DELAY_MS   = 5_000L
        private const val MAX_RETRIES          = 5

        private const val BUFFER_MIN_MS        = 5_000
        private const val BUFFER_MAX_MS        = 120_000
        private const val BUFFER_PLAYBACK_MS   = 5_000
        private const val BUFFER_REBUFFER_MS   = 5_000
        private const val BUFFER_MAX_BYTES     = 128 * 1024 * 1024

        private const val RECENT_LIST_MAX      = 20

        private const val USB_SETTLE_MS        = 1_500L
        private const val USB_RESET_GAP_MS     = 400L
        // After a live Settings → Audio Output switch the USBDEVFS_RESET soft-replug
        // has re-enumerated the DAC, but the kernel needs a beat to re-probe and
        // register the USB sound card before a new system AudioTrack should open.
        // We poll /dev/snd for the unbind/rebind, but this budget is a bound, not
        // a guarantee: opening too early can land in a half-enumerated device and
        // wedge card 0 (silent until a physical replug), while a missed detection
        // must never stall the mode switch for long.
        private const val USB_REBIND_MAX_WAIT_MS = 10_000L
        // Fixed grace used when /dev/snd cannot be listed (the unbind/rebind is
        // unobservable) — long enough for the kernel to re-probe a slow TV box.
        private const val USB_REBIND_GRACE_MS = 2_000L
        // Startup find-USB retry: the DAC can be mid-re-enumeration when the
        // service starts (esp. after our USBDEVFS_RESET soft-replug), so the
        // first probe can miss it. Retry a few times; a soft reset does not
        // re-broadcast ACTION_USB_DEVICE_ATTACHED, so nothing else re-arms.
        private const val USB_PROBE_RETRY_MS    = 2_000L
        private const val MAX_USB_PROBE_RETRIES = 4
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

        // ── USB DAC detection ─────────────────────────────────────────────────
        /**
         * Detects a live USB audio device using UsbManager.
         * Unlike AudioManager.getDevices() which caches USB descriptors and keeps
         * returning the device after power-off, UsbManager.deviceList only contains
         * devices currently active on the bus.
         *
         * Callable from any Context — does not require the service to be running.
         */
        fun findUsbAudioDevice(context: Context): android.hardware.usb.UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE)
                as? android.hardware.usb.UsbManager ?: return null
            return usbManager.deviceList.values.firstOrNull { device ->
                (0 until device.interfaceCount).any { i ->
                    device.getInterface(i).interfaceClass ==
                        android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
                }
            }
        }

        /** Live reference to the running service — used by UI for resetAudioSink(). */
        @Volatile var instance: PlaybackService? = null
            private set

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
    }

    // ── USB DAC monitoring ────────────────────────────────────────────────────

    // Runnable field so USB-settle delayed calls can be cancelled before re-posting,
    // preventing pileup on rapid hotplug events (BUG-18).
    private val usbSettleRunnable = Runnable { checkAndResetUsbAudio("USB attach") }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached — will check for DAC in ${USB_SETTLE_MS}ms")
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
            }
        }
    }

    // ── decent-player userspace USB driver integration ────────────────────────

    /** Wrapped in PlaybackService.buildAudioSink when the usbdevfs driver is enabled. */
    private var usbAudioSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    /** True while the usbdevfs driver owns the USB DAC (mirrors the wrapper's
     *  onDriverOwnsUsbDeviceChanged callback; written from the release thread). */
    @Volatile
    private var usbDriverOwnsDac = false

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
    private fun releaseUsbDriverDac(): Boolean {
        // NOTE: deliberately NOT gated on usbDevfsDriverEnabled(). If the wrapper
        // exists and currently owns the DAC, its usbfs claims must be dropped
        // regardless of the current mode — e.g. a live Settings → Audio Output
        // switch from usbdevfs to a system mode must return the DAC to the HAL
        // or the new AudioTrack opens into a still-claimed device and is silent.
        val sink = usbAudioSink ?: return false
        if (!usbDriverOwnsDac) {
            // Driver does not own the DAC (released, never engaged, or a failed
            // configure). Nothing to hand back — avoid a pointless USB reset.
            Log.d(TAG, "DAC release skipped — driver does not own the DAC")
            return false
        }
        return try {
            sink.releaseUsbForIdle()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "usbdevfs DAC release failed: ${t.message}")
            false
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
        val sink = usbAudioSink
        if (sink == null) {
            usbAudioSink = null
            rebuildPlayerForOutputMode()
            return
        }
        // Snapshot the system sound cards before the soft-replug so we can watch
        // the USB card unbind/rebind (below) while the reset re-enumerates the DAC.
        val cardsBefore = sndCards()
        if (!releaseUsbDriverDac()) {
            // The driver did not own the DAC — no USB reset was triggered, so there
            // is no re-enumeration to wait out. Rebuild immediately instead of
            // waiting for a card change that will never happen (BUG-21).
            usbAudioSink = null
            rebuildPlayerForOutputMode()
            return
        }
        val main = mainHandler
        Thread({
            if (!waitForUsbRebind(sink, cardsBefore)) {
                Log.w(TAG, "USB sound card did not re-register after soft-replug — " +
                    "system-audio modes may stay silent until the DAC is physically replugged")
            }
            main.post {
                if (usbAudioSink === sink) usbAudioSink = null
                rebuildPlayerForOutputMode()
            }
        }, "usbModeSwitch").start()
    }

    /**
     * Rebuilds ExoPlayer so the new audio-output mode's sink is really in use.
     * The sink is decided inside DefaultRenderersFactory.buildAudioSink when the
     * player builds its renderers, so changing the mode at runtime requires a
     * fresh player (a plain sink/reset restart reuses the old renderer). The
     * MediaSession stays put — its controllers (UI / MPD bridge) keep working —
     * and the queue, position and play-intent are restored on the new player.
     * Must be called on the main thread.
     */
    private fun rebuildPlayerForOutputMode() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { rebuildPlayerForOutputMode() }
            return
        }
        val session = mediaSession ?: return
        val old = session.player as? ExoPlayer ?: return
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        // Live streams (duration == TIME_UNSET) report an unbounded position;
        // restoring it would seek past the end of a seekable station (BUG-19).
        val position = if (old.duration == C.TIME_UNSET) 0L else old.currentPosition
        val playWhenReady = old.playWhenReady
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

    /** `/dev/snd` control cards (controlC0, controlC1, …) currently registered. */
    private fun sndCards(): Set<String> =
        File("/dev/snd").list()?.filterTo(HashSet()) { it.startsWith("controlC") } ?: emptySet()

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
     * are bounded by [USB_REBIND_MAX_WAIT_MS]; when /dev/snd is not listable the wait
     * degenerates to a short fixed grace so the mode switch is never stalled (BUG-21).
     * The caller always rebuilds the player afterwards — a missed detection only
     * skips the extra grace period, it never blocks forever.
     *
     * @return true when an unbind+rebind was actually observed.
     */
    private fun waitForUsbRebind(
        sink: com.decent.usbaudio.media3.UsbAudioSink,
        cardsBefore: Set<String>
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + USB_REBIND_MAX_WAIT_MS

        // The unbind can only happen once the release thread has run resetUsbDevice(),
        // so first wait for any in-flight release to finish.
        while (sink.isIdleReleaseInFlight() && SystemClock.uptimeMillis() < deadline) {
            if (!sleepQuietly(10)) return false
        }

        // /dev/snd not observable: fall back to a fixed grace so the kernel has time
        // to re-probe, then let the caller proceed.
        if (cardsBefore.isEmpty()) {
            sleepQuietly(USB_REBIND_GRACE_MS)
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
            if (!sleepQuietly(100)) return false
        }
        if (!sawUnbind) return false

        // Stage 2: the USB card comes back. Accept the same or a different index.
        while (SystemClock.uptimeMillis() < deadline) {
            val now = sndCards()
            if (now.isNotEmpty() && now.size >= cardsBefore.size) return true
            if (!sleepQuietly(100)) return false
        }
        return false
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
                        mediaSession?.player?.stop()
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
                        mediaSession?.player?.stop()
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

        if (lanFgOwned) {
            if (media3OwnsFg) {
                // Media3 took over the foreground state — hand over silently.
                lanFgOwned = false
            } else if (!mpdEnabled) {
                try { stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                lanFgOwned = false
                Log.i(TAG, "LAN keep-foreground released")
            }
        }
        if (!mpdEnabled || media3OwnsFg || lanFgOwned) return

        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_LAN_CONTROL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_LAN_CONTROL, "LAN control",
                        NotificationManager.IMPORTANCE_MIN)
                )
            }
            // While paused, Media3 keeps its (non-foreground) media notification
            // with id 1001; remove it so we don't end up with two notifications
            // alongside our LAN keep-foreground one.
            try { mgr.cancel(MEDIA3_NOTIF_ID) } catch (_: Exception) {}
            val port = prefs.getInt("mpd_port", 6600).coerceIn(1024, 65535)
            val n = NotificationCompat.Builder(this, CHANNEL_LAN_CONTROL)
                .setSmallIcon(R.drawable.ic_network)
                .setContentTitle("LAN control active")
                .setContentText("MPD :$port — control from phone app")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(LAN_FG_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(LAN_FG_NOTIF_ID, n)
            }
            lanFgOwned = true
            Log.i(TAG, "LAN keep-foreground started")
        } catch (e: Exception) {
            Log.w(TAG, "LAN keep-foreground failed: ${e.message}")
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
                                forceRouteToSpeaker = true
                            ),
                            // Sync the delegate's guard with the driver's actual USB ownership:
                            // while the usbdevfs driver streams, BitPerfectAudioSink must not
                            // open its own direct AudioTrack on the DAC.
                            onDriverOwnsUsbDeviceChanged = { owns ->
                                bitPerfectSink.driverOwnsUsbDevice = owns
                                this@PlaybackService.usbDriverOwnsDac = owns
                                Log.i(TAG, "UsbAudioSink driver ownership changed: releasedToDriver=$owns")
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
            AppDataSource(this, httpFactory)
        }

        val mediaSourceFactory = SacdMediaSourceFactory(
            DefaultMediaSourceFactory(this, extractorsFactory)
                .setDataSourceFactory(dataSourceFactory)
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

    private val playerListener = object : Player.Listener {
        private var retryCount = 0

        override fun onEvents(player: Player, events: Player.Events) {
            // Media3 promotes/demotes its own media notification as playback
            // starts/stops — keep our LAN keep-foreground in sync.
            updateLanForeground()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName} — ${error.message}", error)
            // The current AudioTrack is no longer usable. Do not leave its preferred
            // mixer attributes active while reconnecting or moving to another item.
            bitPerfectManager.clear()
            val player = mediaSession?.player ?: return
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
                // Resume after a pause that released the track: play() from
                // STATE_IDLE does not auto-prepare in Media3, so prepare
                // explicitly to re-negotiate the stream (fresh bit-perfect
                // session, or none at all when the DAC is gone).
                val p = mediaSession?.player ?: return
                if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) {
                    p.prepare()
                }
                return
            }
            saveCurrentPosition()
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
            // apps get the DAC back while we are paused.
            releaseUsbDriverDac()
            if (bitPerfectManager.findUsbOutputDevice() != null) {
                mediaSession?.player?.stop()
            }
        }

        private var wasBuffering = false

        override fun onPlaybackStateChanged(playbackState: Int) {
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
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
                saveCurrentPosition()

            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                // Stop/end releases the active audio pipeline. Keep the preference scoped
                // to an active track rather than leaving it set for the service lifetime.
                bitPerfectManager.clear()
                // Same for the usbdevfs driver: drop the DAC claims once the queue
                // stops/ends so the system can use the DAC again.
                releaseUsbDriverDac()
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

        // The usbdevfs driver only streams when the DAC is already granted to us.
        // Request permission up front (the system dialog shows on the TV screen) so
        // playback doesn't silently fall back to the AudioTrack path on the first track.
        if (usbDevfsDriverEnabled()) {
            runCatching {
                val driver = com.decent.usbaudio.UsbAudioDevice.getInstance(this)
                val dac = driver.findUsbAudioDevice()
                if (dac != null && !driver.hasPermission(dac)) {
                    driver.requestPermission(dac) { granted ->
                        Log.i(TAG, "USB permission (driver): granted=$granted")
                    }
                }
            }.onFailure {
                Log.w(TAG, "USB permission request failed: ${it.message}")
            }
        }

        httpFactory = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
                .readTimeout(HTTP_TIMEOUT_SECS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
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
        saveCurrentPosition()
        bitPerfectManager.clear()
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
    private inner class AppDataSource(context: Context, httpFactory: HttpDataSource.Factory) : DataSource {
        private val defaultSrc = DefaultDataSource.Factory(context, httpFactory).createDataSource()
        private val smbSrc     = SmbDataSource()
        private var active: DataSource? = null
        private var lastUri: String? = null

        override fun addTransferListener(l: TransferListener) { defaultSrc.addTransferListener(l); smbSrc.addTransferListener(l) }
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            lastUri = if (scheme == "http" || scheme == "https") dataSpec.uri.toString() else null
            active = if (scheme == "smb") smbSrc else defaultSrc
            return active?.open(dataSpec) ?: throw IOException("No data source available")
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
