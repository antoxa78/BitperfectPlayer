package com.decent.usbaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.File


/**
 * Manages the lifecycle of a USB Audio Class device for bit-perfect output.
 *
 * Responsibilities:
 * - Discover connected USB audio devices
 * - Request user permission via [UsbManager.requestPermission]
 * - Open the device and extract endpoint/interface info
 * - Provide the file descriptor and endpoint addresses to [UsbAudioStream]
 *
 * This class does NOT perform audio I/O — that's handled by the native layer
 * via [UsbAudioStream].
 *
 * @author DecentPlayer project
 */
class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private var claimedInterface: UsbInterface? = null

    /** Default/idle clock rate of the connected DAC, parsed from its AS Format
     *  Type I descriptors (lowest advertised rate; 44100 when not parseable).
     *  Restored via SET_CUR in [restoreToIdleSampleRate] before release so the
     *  DAC is not left locked on the last played stream rate. */
    @Volatile
    private var defaultIdleRate: Int = 44100

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"

        @Volatile
        private var instance: UsbAudioDevice? = null

        /**
         * Get the singleton instance. All callers share the same connection
         * share the same connection and fd, preventing ENODEV from competing opens.
         */
        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }


    /**
     * Find the first connected USB audio output device.
     *
     * Scans all USB devices for one with an AudioStreaming interface
     * (class=1, subclass=2) that has an isochronous OUT endpoint.
     *
     * @return The USB device, or null if none found.
     */
    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // USB Audio Class: class=1 (Audio), subclass=2 (AudioStreaming)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2) {
                    Log.i(TAG, "Found USB audio device: ${device.productName} " +
                            "(vendor=0x${device.vendorId.toString(16)}, " +
                            "product=0x${device.productId.toString(16)})")
                    return device
                }
            }
        }
        Log.d(TAG, "No USB audio device found")
        return null
    }

    /**
     * Check if we already have permission to access the device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission from the user to access the USB device.
     *
     * @param device   The USB device to request access for.
     * @param callback Called with true if permission granted, false otherwise.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Permission already granted for ${device.productName}")
            callback(true)
            return
        }

        val intent = Intent(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        intent.setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                intent,
                PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == context.packageName + ACTION_USB_PERMISSION_SUFFIX) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result: granted=$granted for ${device.productName}")
                    context.unregisterReceiver(this)
                    callback(granted)
                }
            }
        }

        val filter = IntentFilter(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
        Log.i(TAG, "Permission requested for ${device.productName}")
    }

    /**
     * Open the USB device and extract all information needed for audio I/O.
     *
     * Finds the AudioStreaming interface, locates the isochronous OUT and
     * feedback IN endpoints, and returns everything the native layer needs.
     *
     * @param device The USB audio device to open.
     * @return Device info with fd and endpoint addresses, or null on failure.
     */
    /** Cached device info from the last successful openDevice() call. */
    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null

    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        // Return cached info if already open with valid connection
        val cached = cachedDeviceInfo
        if (cached != null && connection != null) {
            Log.i(TAG, "Device already open, reusing fd=${cached.fd}")
            return cached
        }
        // Close any stale connection before opening new
        closeDevice()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.productName}")
            return null
        }

        // Find the AudioStreaming interface and its endpoints
        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        // Count alternate settings for the streaming interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                altSettingCount++

                // Look for endpoints in non-zero alt settings
                if (iface.endpointCount > 0 && streamingInterface == null) {
                    streamingInterface = iface

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        when {
                            // Isochronous OUT endpoint (audio data)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> {
                                endpointOut = ep.address
                                maxPacketSize = ep.maxPacketSize
                                Log.i(TAG, "Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }
                            // Isochronous IN endpoint (feedback)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Log.i(TAG, "Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming interface/endpoint found")
            conn.close()
            return null
        }

        // Claim the AudioControl interface (0) with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        // NOTE: We claim the zero-bandwidth alt setting (alt=0). The actual streaming alt setting
        // will be activated later via setInterface() which allocates USB bandwidth.
        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        // Force alt=0 to stop any streaming left by kernel driver
        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Log.i(TAG, "Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        // Log all available alt settings for debugging
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Log.d(TAG, "  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Log.i(TAG, "Device opened: ${device.productName}, fd=$fd, " +
                "iface=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, " +
                "maxPacket=$maxPacketSize, altSettings=$altSettingCount")

        connection = conn
        currentDevice = device

        // Auto-detect Clock Source ID, best alt setting, and idle sample rate
        val clockSourceId = parseClockSourceId(conn)
        val (bestAlt, bestBits) = parseBestAltSetting(conn)
        val idleRate = parseLowestSampleRate(conn)
        if (idleRate > 0) defaultIdleRate = idleRate
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "bestAlt=$bestAlt, bestBits=$bestBits, defaultIdleRate=$defaultIdleRate")

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits
        )
        cachedDeviceInfo = info
        return info
    }

    /**
     * Perform a USB device reset via native ioctl, then close and reopen.
     * This clears any stale clock/endpoint state left by the kernel driver.
     * After reset, the DAC reinitializes and will accept our SET_CUR.
     */
    fun resetAndReopen() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        Log.i(TAG, "Performing REAL USBDEVFS_RESET on fd=$fd...")

        // Real USB port reset via native ioctl — resets DAC clock state
        val ret = UsbAudioStream.nativeUsbReset(fd)
        Log.i(TAG, "USBDEVFS_RESET result: $ret")

        // Reset releases all interface claims. The fd remains valid.
        // Clear cache so openDevice re-claims, but KEEP the connection
        // so the same fd is reused (native claims are on this fd).
        cachedDeviceInfo = null
        claimedInterface = null
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /**
     * Parse raw USB descriptors to find the UAC2 Clock Source entity ID.
     * This is the entity that controls the DAC's sample rate.
     *
     * Scans the AudioControl interface descriptors for a CLOCK_SOURCE
     * descriptor (bDescriptorSubtype = 0x0A) and returns its bClockID.
     *
     * @return Clock Source entity ID, or -1 if not found.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1

        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                // AudioControl = class 1, subclass 1
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 3) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // CLOCK_SOURCE = 0x0A
                if (bDescriptorSubtype == 0x0A && bLength >= 5) {
                    val bClockID = raw[i + 3].toInt() and 0xFF
                    Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                    return bClockID
                }
            }

            i += bLength
        }

        Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
        return -1
    }

    /**
     * Parse raw USB descriptors to find the best (highest bit depth) alt setting
     * for the AudioStreaming interface.
     *
     * Scans AS Format Type I descriptors (CS_INTERFACE 0x02) for bBitResolution
     * and returns the alt setting with the highest value.
     *
     * @return Pair(altSetting, bitDepth), or Pair(1, 16) as default.
     */
    /** Parsed alt setting: (altNumber, bitResolution) */
    private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    private fun parseBestAltSetting(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            // CS_INTERFACE (0x24) in AudioStreaming — Format Type I (subtype 0x02)
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x02) {
                    val bSubslotSize = raw[i + 4].toInt() and 0xFF
                    val bBitResolution = raw[i + 5].toInt() and 0xFF
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, bBitResolution))
                    }
                    if (bBitResolution > bestBits && currentAlt > 0) {
                        bestBits = bBitResolution
                        bestAlt = currentAlt
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
        return Pair(bestAlt, bestBits)
    }

    /**
     * Parse the DAC's advertised sample rates from its AudioStreaming AS Format
     * Type I descriptors and return the lowest one. This is the natural
     * default/idle clock rate of the device (typically the rate it boots to);
     * restoring it on release avoids leaving the DAC locked on an arbitrary
     * last-played stream rate.
     *
     * Handles both discrete rate lists (bSamFreqType = N → tSamFreq[N]) and a
     * continuous range (bSamFreqType = 0 → tSamFreq[0] is the lower bound).
     *
     * @return Lowest advertised rate in Hz, or -1 if none could be parsed.
     */
    private fun parseLowestSampleRate(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1
        var low = -1
        var i = 0
        var inAudioStreaming = false
        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
            }

            // CS_INTERFACE (0x24) in AudioStreaming — AS Format Type I (subtype 0x02)
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 9 &&
                (raw[i + 2].toInt() and 0xFF) == 0x02) {
                val bSamFreqType = raw[i + 7].toInt() and 0xFF
                var pos = i + 8
                // Continuous range: tSamFreq[0] is the lower bound
                val count = if (bSamFreqType == 0) 1 else bSamFreqType
                for (n in 0 until count) {
                    if (pos + 3 > i + bLength || pos + 3 > raw.size) break
                    val rate = (raw[pos].toInt() and 0xFF) or
                            ((raw[pos + 1].toInt() and 0xFF) shl 8) or
                            ((raw[pos + 2].toInt() and 0xFF) shl 16)
                    if (rate > 0 && (low == -1 || rate < low)) low = rate
                    pos += 3
                }
            }

            i += bLength
        }
        Log.i(TAG, "parseLowestSampleRate: lowest advertised rate=$low")
        return low
    }

    /**
     * Find the alt setting that matches the given source bit depth exactly.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> {
        if (parsedAltSettings.isEmpty()) {
            val info = cachedDeviceInfo ?: return Pair(1, 16)
            return Pair(info.bestAltSetting, info.bestBitDepth)
        }

        // Exact match
        val exact = parsedAltSettings.firstOrNull { it.second == targetBitDepth }
        if (exact != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        // Next higher
        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        // Fallback to best
        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
        return best
    }

    /**
     * Hand the DAC back to the system: release our claimed interfaces FIRST,
     * then reset the bus, then close the connection — in that order — so the
     * kernel's own snd-usb-audio driver actually gets a chance to bind.
     *
     * ORDERING BUG FIX (this was the actual remaining cause of "other apps
     * have no sound after Exit"): this function, and the caller's cleanup
     * around it, previously did USBDEVFS_RESET *first* and released the
     * claimed interfaces *afterward* (in a finally block one level up). That
     * order doesn't work: while an interface is still claimed via usbfs
     * (force = true, which is how it was originally taken), there is no
     * kernel driver attached to it for USBDEVFS_RESET's reset-and-rebind
     * cycle to act on — the kernel has nothing to do but preserve our own
     * claim straight through the reset. By the time we released the claim a
     * moment later, the reset's one opportunity to let a driver bind had
     * already passed, and simply releasing afterward does not by itself
     * trigger a fresh probe. The interface was just left unclaimed and idle
     * — which is consistent with what was observed on-device: Android's
     * audio policy still reported the USB device as present after this
     * "release" (the bus reset alone is enough to cause a redetection event
     * at that layer), yet no other app could get sound out of it, and sound
     * only ever came back after a genuine physical unplug/replug (a real
     * from-scratch enumeration with no stale claim in the way — nothing our
     * software path was doing achieved that same clean slate).
     *
     * Releasing before resetting at least removes the one confirmed reason
     * the kernel had to skip rebinding. On-device evidence (logcat on a Mi TV
     * box, Android 14): after this sequence the ALSA card files re-appear
     * (pcmC2D0p + controlC2), the framework even routes other apps to
     * OUT_USB_HEADSET — but the vendor audio HAL's USB output proxy then dies
     * with a persistent `pcm oops: cannot prepare channel: No such device`
     * because the underlying USB streaming PCM can only be re-armed by a real
     * host-level disconnect/reconnect (a physical unplug, or a root sysfs
     * unbind/bind via [forceKernelRebind]). A USBDEVFS_RESET merely
     * re-enumerates on the same port, which the HAL provably never recovers
     * from. So for other apps to regain the DAC, either the user physically
     * replugs it, or the box must provide root for the sysfs fallback —
     * nothing further at the usbfs level can force it.
     *
     * Trade-off: resuming playback after this now requires a full
     * [openDevice] (re-open, re-claim, re-parse descriptors) instead of
     * reusing the cached fd — slower to resume, but the fd staying open and
     * claimed was exactly why the device was never truly released. No-op if
     * the connection is already closed.
     */
    fun resetUsbDevice() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        // Capture the device while it is still known (closeDevice() below nulls
        // currentDevice). The audio interface ids are needed so USBDEVFS_CONNECT
        // can ask the kernel to re-bind snd-usb-audio (a force=true claim
        // previously disconnected that driver everywhere).
        val device = currentDevice
        val audioInterfaceIds = device?.let { d ->
            (0 until d.interfaceCount).asSequence()
                .map { d.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
                .map { it.id }
                .toList()
        }?.distinct() ?: emptyList()

        // Release BEFORE resetting — see doc comment above.
        val controlInterface = device?.let { d ->
            (0 until d.interfaceCount).map { d.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
        }
        controlInterface?.let {
            Log.i(TAG, "resetUsbDevice: releaseInterface(control iface ${it.id}) before reset: ${conn.releaseInterface(it)}")
        }
        claimedInterface?.let {
            Log.i(TAG, "resetUsbDevice: releaseInterface(streaming iface ${it.id}) before reset: ${conn.releaseInterface(it)}")
        }
        claimedInterface = null

        // Re-enumerate the device so the kernel re-binds snd-usb-audio. This is
        // required after our force=true claim detached the kernel driver: a plain
        // releaseInterface + USBDEVFS_CONNECT is NOT sufficient on this hardware
        // to bring the ALSA card back, which left the system/Android audio modes
        // silent (regression). The earlier assumption that this reset caused the
        // DR70 disconnects was wrong — those were HDMI-CEC / TV power-down events
        // (whole-port USB power cut), not this per-device port reset.
        runCatching { UsbAudioStream.nativeUsbResetOnly(fd) }
            .onFailure { Log.w(TAG, "USBDEVFS_RESET failed: ${it.message}") }
            .getOrNull()?.let { if (it != 0) Log.w(TAG, "USBDEVFS_RESET returned $it") }

        // Best-effort: also ask the kernel to re-bind each audio interface. After
        // the reset above this typically returns EBUSY (driver already re-bound).
        for (ifaceId in audioInterfaceIds) {
            runCatching { UsbAudioStream.nativeUsbConnect(fd, ifaceId) }
                .onFailure { Log.w(TAG, "USBDEVFS_CONNECT iface=$ifaceId failed: ${it.message}") }
                .getOrNull()?.let { if (it != 0) Log.w(TAG, "USBDEVFS_CONNECT iface=$ifaceId returned $it") }
        }

        // Finally close the connection fully (releases any remaining claims).
        closeDevice()
    }

    /**
     * Force the kernel to re-probe snd-usb-audio via a sysfs unbind/bind of the
     * USB device — a genuine disconnect/reconnect that the USB core always
     * processes, unlike [UsbAudioStream.nativeUsbResetOnly] (USBDEVFS_RESET),
     * which on Amlogic SoCs re-enumerates the device at the bus level but does
     * not cause the kernel audio driver to re-bind (on-device evidence:
     * AudioDeviceCallback fires but /dev/snd never gains the USB card, so other
     * apps remain silent until a physical unplug/replug).
     *
     * Must be called only AFTER [closeDevice] so no fd is still holding the
     * interfaces (the kernel refuses to unbind a claimed device). The unbind
     * removes the device from the bus, the bind re-probes it — snd-usb-audio
     * then binds and /dev/snd gains the card. Bounded: every sysfs touch is
     * best-effort and failures degrade to the pre-existing behavior (a silent
     * USB DAC for other apps) rather than throwing into the exit path.
     *
     * @param productName The device product name captured before close, used to
     *                    locate the device's sysfs node (e.g. "Audalytic DR70").
     */
    /**
     * Force the kernel to re-probe snd-usb-audio via a sysfs unbind/bind of the
     * USB device — a GENUINE disconnect/reconnect that the USB core always
     * processes and that Android's UsbHostManager/UsbAlsaManager observe as a
     * real device removal + re-add (the one thing usbfs can never produce: a
     * USBDEVFS_RESET merely re-enumerates on the same port, so the vendor HAL's
     * USB output proxy stays unregistered and other apps get no sound — on-device
     * evidence: after every software-exit, the HAL routes to OUT_USB_HEADSET and
     * then fails `pcm oops: cannot prepare channel: No such device` for the whole
     * session until a physical unplug/replug).
     *
     * Normal Android SELinux policy denies a third-party app write access to
     * /sys/bus/usb, so the direct write path usually fails — we then retry the
     * same operation via `su` (Magisk / userdebug adbd root / SuperSU). When a
     * su binary is present AND grants root, this produces the genuine host-level
     * detach that fixes the box. On a locked/unrooted box it degrades to the
     * pre-existing behavior (silent DAC for other apps) without ever throwing.
     *
     * Must be called only AFTER [closeDevice] so no fd is still holding the
     * interfaces (the kernel refuses to unbind a claimed device).
     *
     * @param productName The device product name captured before close, used to
     *                    locate the device's sysfs node (/sys/bus/usb/devices/N-N.N).
     */
    private fun forceKernelRebind(productName: String?) {
        if (productName.isNullOrBlank()) {
            Log.w(TAG, "forceKernelRebind: no product name to match against — skipping")
            return
        }
        val sysfsDevices = File("/sys/bus/usb/devices")
        if (!sysfsDevices.exists() || !sysfsDevices.canRead()) {
            // The SELinux policy on this box blocks read ("not permitted to read...")
            // and, with root, the shell can still list the node even though the app
            // cannot — so try to discover the node through su too.
            Log.w(TAG, "forceKernelRebind: /sys/bus/usb/devices not readable directly — will try via su")
        }
        val busId = findSysfsBusId(productName, sysfsDevices)
        if (busId == null) {
            Log.w(TAG, "forceKernelRebind: no sysfs node matching '$productName' — skipping")
            return
        }
        Log.i(TAG, "forceKernelRebind: forcing kernel re-probe of $busId ($productName)")
        if (writeSysfs("/sys/bus/usb/drivers/usb/unbind", busId, "unbind $busId")) {
            // Give the kernel a moment to finish the detach before re-adding.
            runCatching { Thread.sleep(200) }
            writeSysfs("/sys/bus/usb/drivers/usb/bind", busId, "bind $busId")
        } else {
            Log.w(TAG, "forceKernelRebind: unbind failed (direct and su) — device left as-is; " +
                    "other apps will need a physical replug")
        }
        // Don't block the exit path long; the kernel driver probes asynchronously
        // and waitForUsbRebind() (in PlaybackService) observes the outcome.
    }

    private fun findSysfsBusId(productName: String, sysfsDevices: File): String? {
        // Direct scan (app-readable sysfs or root uid).
        val direct = runCatching {
            sysfsDevices.listFiles()?.firstOrNull { dir ->
                val product = File(dir, "product").takeIf { it.exists() }?.readText()?.trim()
                product != null && product.contains(productName, ignoreCase = true)
            }?.name
        }.getOrNull()
        if (direct != null) return direct
        // Via su: have the shell scan and echo the node name back.
        val out = runShellCapture(
            "for p in /sys/bus/usb/devices/*/product; do " +
                "if grep -qi \"$productName\" \"\$p\"; then basename \$(dirname \"\$p\"); break; fi; done"
        ) ?: return null
        return out.trim().ifEmpty { null }
    }

    /** Write value -> path, trying a direct write first, then escalating via su. */
    private fun writeSysfs(path: String, value: String, tag: String): Boolean {
        try {
            File(path).writeText(value)
            Log.i(TAG, "forceKernelRebind: $tag OK (direct) — ${path} <- $value")
            return true
        } catch (direct: Exception) {
            Log.i(TAG, "forceKernelRebind: $tag direct write failed (${direct.message}) — trying su")
        }
        val ok = runShell(
            "echo '$value' > '$path'"
        )
        Log.i(TAG, "forceKernelRebind: $tag via su ok=$ok")
        return ok
    }

    private fun runShell(command: String): Boolean {
        return runCatching {
            val p = ProcessBuilder("su", "-c", command).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            val code = p.waitFor()
            Log.i(TAG, "forceKernelRebind: su -c '$command' -> exit=$code out='$out' err='$err'")
            code == 0
        }.getOrElse { e ->
            Log.w(TAG, "forceKernelRebind: no usable su (${e.message})")
            false
        }
    }

    /** Run a plain shell command, returning its stdout (or null if su is unavailable). */
    private fun runShellCapture(command: String): String? {
        return runCatching {
            val p = ProcessBuilder("su", "-c", command).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            p.waitFor()
            out
        }.getOrElse { e ->
            Log.w(TAG, "forceKernelRebind: no usable su for shell command (${e.message})")
            null
        }
    }

    /**
     * Close the USB device and release all resources.
     */
    fun closeDevice() {
        cachedDeviceInfo = null
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        Log.i(TAG, "USB device closed")
    }

    /**
     * Set the sample rate on a UAC2 Clock Source entity via SET_CUR control transfer.
     *
     * Tries multiple common clock source entity IDs since we can't easily read
     * the AudioControl descriptors from userspace on Android.
     *
     * UAC2 SET_CUR format:
     *   bmRequestType = 0x21 (Host-to-Device, Class, Interface)
     *   bRequest = 0x01 (SET_CUR)
     *   wValue = (CS_SAM_FREQ_CONTROL << 8) | 0 = 0x0100
     *   wIndex = (clockSourceEntityId << 8) | audioControlInterfaceNumber
     *   data = 4-byte LE sample rate
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false

        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()

        // Use auto-detected clock source ID from USB descriptors.
        // If not available, fall back to brute-force trying common IDs.
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) {
            intArrayOf(detectedId)  // use the one we parsed from descriptors
        } else {
            intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                    0x28, 0x29, 0x2A, 0x06, 0x07, 0x08,
                    0x10, 0x11, 0x12, 0x20, 0x21, 0x22)
        }

        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or 0  // entityId << 8 | audioControlInterface(0)
            val ret = conn.controlTransfer(
                    0x21,    // bmRequestType: Host-to-Device, Class, Interface
                    0x01,    // bRequest: SET_CUR
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000     // timeout ms
            )
            if (ret >= 0) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS with clockSourceId=0x${csId.toString(16)} (wIndex=0x${wIndex.toString(16)}, ret=$ret)")
                return true
            }
        }

        Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
        return false
    }

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR.
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val data = ByteArray(4)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or 0
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR (actually CUR is 0x01 for both)
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 4) {
                val rate = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} " +
                        "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
                return rate
            }
        }
        Log.w(TAG, "readSampleRate: all GET_CUR attempts failed")
        return -1
    }

    /**
     * Read the CLOCK_VALID control from the DAC via UAC2 GET_CUR.
     * This checks whether the Clock Source entity's clock is locked and stable
     * after a sample rate change. Standard practice per UAC2 spec: verify clock after SET_CUR before proceeding.
     *
     * UAC2 spec: Clock Source descriptor, CS = 0x02 (CUR_CLOCK_VALID_CONTROL)
     * Returns: true if clock is valid, false if not or on error.
     */
    fun readClockValid(): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(1)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or 0
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR
                    0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Log.i(TAG, "readClockValid: clockSourceId=0x${csId.toString(16)} valid=$valid")
                return valid == 1
            }
        }
        Log.w(TAG, "readClockValid: all GET_CUR attempts failed")
        return false
    }

    /**
     * Return the DAC clock to its default/idle sample rate before handing the
     * device back to the system.
     *
     * A UAC2 Clock Source's rate is a control value held in the DAC's firmware:
     * it keeps the last SET_CUR (usually the final track's rate) across a
     * USBDEVFS_RESET, because a bus reset re-enumerates the device but does not
     * power-cycle it. Without an explicit SET_CUR before release, the DAC stays
     * locked on the last played stream frequency until it is physically
     * unplugged/replugged or another app reprograms the clock.
     *
     * Mirrors the configure lifecycle (setAlt(0) → SET_CUR) by dropping the
     * streaming endpoint to zero-bandwidth first, then writing the idle rate.
     *
     * @return true when a SET_CUR was issued and accepted; false otherwise.
     */
    fun restoreToIdleSampleRate(): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false
        // Shut the streaming endpoint down first (free the ISO ring) so the
        // clock is reprogrammed with no transfers in flight.
        setAltSetting(0)
        val ok = setSampleRate(defaultIdleRate)
        Log.i(TAG, "restoreToIdleSampleRate: SET_CUR=${defaultIdleRate} Hz ok=$ok (device=${device.productName})")
        return ok
    }

    /**
     * Set the alternate setting on the streaming interface via Java API.
     * This may properly allocate USB bandwidth, which the native ioctl might not.
     */
    fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false

        // Find the UsbInterface with the matching alt setting
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting) {
                val result = conn.setInterface(iface)
                Log.i(TAG, "setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        // Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Log.d(TAG, "  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}
