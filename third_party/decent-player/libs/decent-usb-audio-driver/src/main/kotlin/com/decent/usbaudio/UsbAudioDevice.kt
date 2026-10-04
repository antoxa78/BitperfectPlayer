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
        val topo = parseClockTopology(conn)
        clockTopology = topo
        val clockSourceId = topo?.let { resolveClockSource(conn, it, interfaceId) } ?: -1
        activeClockSourceId = clockSourceId
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
        clockTopology = null
        activeClockSourceId = -1
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /**
     * The UAC2 clock entities of the DAC and how they are wired, parsed from the
     * raw configuration descriptors.
     *
     * The rate must be written to the Clock Source that actually drives the
     * streaming interface we play on. That is reached by following the chain
     * AS_GENERAL.bTerminalLink → USB-streaming Terminal.bCSourceID → (Clock
     * Selector → current input pin | Clock Multiplier → its source)* → Clock
     * Source — the same walk snd-usb-audio does. Taking simply the first
     * CLOCK_SOURCE in the descriptors (as this driver used to) is wrong for DACs
     * that expose several of them (separate 44.1 kHz- and 48 kHz-family clocks,
     * an S/PDIF-in clock, one per terminal, ...): the SET_CUR then lands on a
     * clock that is not feeding the USB stream, and the DAC stays on its old rate
     * — a 48 kHz track shown as 44.1 kHz.
     */
    private class ClockTopology(
        /** bInterfaceNumber of the AudioControl interface (the low byte of wIndex). */
        val acInterface: Int,
        /** Every CLOCK_SOURCE bClockID, in descriptor order. */
        val sources: List<Int>,
        /** CLOCK_SELECTOR bClockID → baCSourceID[] (pin 1 is index 0). */
        val selectors: Map<Int, IntArray>,
        /** CLOCK_MULTIPLIER bClockID → bCSourceID. */
        val multipliers: Map<Int, Int>,
        /** Terminal bTerminalID → bCSourceID (UAC2 Input/Output Terminals). */
        val terminalClock: Map<Int, Int>,
        /** AudioStreaming bInterfaceNumber → AS_GENERAL bTerminalLink. */
        val asTerminalLink: Map<Int, Int>
    )

    /** Parsed on open; null when the descriptors could not be read. */
    @Volatile private var clockTopology: ClockTopology? = null

    /**
     * Clock Source entity the sample rate is written to. Starts as the source
     * resolved from the topology; [setSampleRate] moves it when the DAC only
     * honours the requested rate on another of its clock sources.
     */
    @Volatile private var activeClockSourceId: Int = -1

    private fun parseClockTopology(conn: UsbDeviceConnection): ClockTopology? {
        val raw = conn.rawDescriptors ?: return null

        var acInterface = -1
        val sources = mutableListOf<Int>()
        val selectors = linkedMapOf<Int, IntArray>()
        val multipliers = linkedMapOf<Int, Int>()
        val terminalClock = linkedMapOf<Int, Int>()
        val asTerminalLink = linkedMapOf<Int, Int>()

        var i = 0
        var inAudioControl = false
        var inAudioStreaming = false
        var currentIfNum = -1

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF
            fun u8(off: Int) = raw[i + off].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                currentIfNum = u8(2)
                val cls = u8(5)
                val sub = u8(6)
                inAudioControl = cls == 1 && sub == 1
                inAudioStreaming = cls == 1 && sub == 2
                if (inAudioControl && acInterface < 0) acInterface = currentIfNum
            }

            // CS_INTERFACE (0x24)
            if (bDescriptorType == 0x24 && bLength >= 4) {
                val subtype = u8(2)
                if (inAudioControl) {
                    when (subtype) {
                        // UAC2 INPUT_TERMINAL (17 bytes): bCSourceID at offset 7
                        0x02 -> if (bLength >= 17) terminalClock[u8(3)] = u8(7)
                        // UAC2 OUTPUT_TERMINAL (12 bytes): bCSourceID at offset 8
                        0x03 -> if (bLength >= 12) terminalClock[u8(3)] = u8(8)
                        // CLOCK_SOURCE
                        0x0A -> if (bLength >= 8) sources.add(u8(3))
                        // CLOCK_SELECTOR: bNrInPins at 4, baCSourceID[] from 5
                        0x0B -> if (bLength >= 5) {
                            val n = u8(4)
                            if (bLength >= 5 + n) selectors[u8(3)] = IntArray(n) { u8(5 + it) }
                        }
                        // CLOCK_MULTIPLIER: bCSourceID at 4
                        0x0C -> if (bLength >= 5) multipliers[u8(3)] = u8(4)
                    }
                } else if (inAudioStreaming && subtype == 0x01) {
                    // AS_GENERAL: bTerminalLink at offset 3
                    asTerminalLink[currentIfNum] = u8(3)
                }
            }

            i += bLength
        }

        val topo = ClockTopology(
            acInterface = if (acInterface >= 0) acInterface else 0,
            sources = sources,
            selectors = selectors,
            multipliers = multipliers,
            terminalClock = terminalClock,
            asTerminalLink = asTerminalLink
        )
        Log.i(TAG, "parseClockTopology: acIface=${topo.acInterface} " +
                "sources=${sources.map { "0x" + it.toString(16) }} " +
                "selectors=${selectors.map { (k, v) -> "0x${k.toString(16)}<-${v.map { "0x" + it.toString(16) }}" }} " +
                "multipliers=${multipliers.map { (k, v) -> "0x${k.toString(16)}<-0x${v.toString(16)}" }} " +
                "terminals=${terminalClock.map { (k, v) -> "$k->0x${v.toString(16)}" }} " +
                "asLinks=$asTerminalLink")
        return topo
    }

    /**
     * Walk from the streaming interface's terminal to the Clock Source that
     * drives it. A Clock Selector is followed through its *current* pin (read
     * with GET_CUR), falling back to pin 1 when the DAC does not answer.
     *
     * @return Clock Source entity ID, or -1 if none could be determined.
     */
    private fun resolveClockSource(conn: UsbDeviceConnection, topo: ClockTopology, streamingIfNum: Int): Int {
        val link = topo.asTerminalLink[streamingIfNum] ?: topo.asTerminalLink.values.firstOrNull()
        var entity = link?.let { topo.terminalClock[it] }
        val path = StringBuilder("terminal=$link")
        var hops = 0
        while (entity != null && hops++ < 8) {
            path.append(" -> 0x").append(entity.toString(16))
            when {
                entity in topo.sources -> {
                    Log.i(TAG, "resolveClockSource: $path (CLOCK_SOURCE)")
                    return entity
                }
                topo.selectors.containsKey(entity) -> {
                    val pins = topo.selectors.getValue(entity)
                    val pin = readSelectorPin(conn, topo.acInterface, entity)
                    path.append("[selector pin=").append(pin).append(']')
                    entity = pins.getOrNull((if (pin in 1..pins.size) pin else 1) - 1)
                }
                topo.multipliers.containsKey(entity) -> {
                    path.append("[multiplier]")
                    entity = topo.multipliers.getValue(entity)
                }
                else -> break
            }
        }
        val fallback = topo.sources.firstOrNull() ?: -1
        Log.w(TAG, "resolveClockSource: could not follow $path to a CLOCK_SOURCE — " +
                "using first CLOCK_SOURCE 0x${fallback.toString(16)}")
        return fallback
    }

    /** GET_CUR on a Clock Selector: the current 1-based input pin, or -1. */
    private fun readSelectorPin(conn: UsbDeviceConnection, acInterface: Int, selectorId: Int): Int {
        val data = ByteArray(1)
        val ret = conn.controlTransfer(
                0xA1,    // Device-to-Host, Class, Interface
                0x01,    // CUR
                0x0100,  // CX_CLOCK_SELECTOR_CONTROL
                (selectorId shl 8) or acInterface,
                data, 1, 1000)
        return if (ret >= 1) data[0].toInt() and 0xFF else -1
    }

    /**
     * Point every Clock Selector that has [sourceId] (directly or through a
     * Clock Multiplier) as an input at that pin, so the clock we just
     * programmed is the one actually driving the stream.
     */
    private fun routeSelectorsTo(conn: UsbDeviceConnection, topo: ClockTopology, sourceId: Int) {
        for ((selectorId, pins) in topo.selectors) {
            val idx = pins.indexOfFirst { it == sourceId || topo.multipliers[it] == sourceId }
            if (idx < 0) continue
            val pin = idx + 1
            if (readSelectorPin(conn, topo.acInterface, selectorId) == pin) continue
            val ret = conn.controlTransfer(
                    0x21,    // Host-to-Device, Class, Interface
                    0x01,    // CUR
                    0x0100,  // CX_CLOCK_SELECTOR_CONTROL
                    (selectorId shl 8) or topo.acInterface,
                    byteArrayOf(pin.toByte()), 1, 1000)
            Log.i(TAG, "routeSelectorsTo: selector 0x${selectorId.toString(16)} -> pin $pin " +
                    "(source 0x${sourceId.toString(16)}): ret=$ret")
        }
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
    /** Parsed alt setting: (altNumber, container bits = bSubslotSize * 8) */
    private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    private fun parseBestAltSetting(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16
        var bestResolution = 0

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
                    // The stream is packed in subslots: a DAC with 24-bit
                    // resolution in 4-byte subslots (very common) expects 32-bit
                    // containers, and feeding it 3-byte samples is noise. The
                    // returned "bit depth" is therefore the container size;
                    // lower bits than the resolution are simply ignored by the DAC.
                    val containerBits = when {
                        bSubslotSize in 1..4 -> bSubslotSize * 8
                        else -> ((bBitResolution + 7) / 8) * 8
                    }
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize " +
                            "bitResolution=$bBitResolution → container=${containerBits}bit")

                    if (currentAlt > 0 && containerBits in 16..32) {
                        altSettings.add(Pair(currentAlt, containerBits))
                        if (bBitResolution > bestResolution ||
                            (bBitResolution == bestResolution && containerBits > bestBits)) {
                            bestResolution = bBitResolution
                            bestBits = containerBits
                            bestAlt = currentAlt
                        }
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
     * Hand the DAC back to the system. Release the claimed interfaces, then reset
     * the bus to force re-enumeration (which re-binds snd-usb-audio), then close.
     *
     * WHY THIS IS BEST-EFFORT, NOT A GUARANTEE (on-device, SHIELD Android TV
     * kernel 4.9 + Audalytic DR70, locked/unrooted):
     *  - The interfaces are claimed with force=true, which detaches snd-usb-audio
     *    and marks them so a plain release does NOT re-bind them.
     *  - The inverse ioctl, USBDEVFS_CONNECT, returns -EBUSY here whether issued
     *    before release (the interface still being claimed means device_attach()
     *    cannot re-match a driver) or after (a driver is already bound) — so it is
     *    a dead primitive on this kernel.
     *  - The only thing that ever re-binds the streaming interface is the
     *    USBDEVFS_RESET re-enumeration, and that is racy: it reliably brings back
     *    the control interface (controlC0) but only sometimes the streaming one
     *    (pcmC0D0p). When it does not, other apps route to a stale card0 and the
     *    HAL fails `proxy_open() ... /dev/snd/pcmC0D0p: No such file or directory`,
     *    which is the "no sound in other apps after Exit" bug — and it can spin
     *    badly enough to SIGABRT audioserver (observed: TimeCheckThread at 17:58).
     *  - [forceKernelRebind] (a genuine sysfs unbind/bind) is the only reliable
     *    software fix and it needs root, so on a locked box it degrades silently
     *    and the user must physically replug the DAC.
     *
     * So: release, reset (best available rebind), best-effort CONNECT, close, then
     * the root-only fallback. A USBDEVFS_RESET-only re-enumeration is the limit of
     * what a third-party app can do on this hardware without root.
     *
     * Trade-off: resuming playback after this requires a full [openDevice] rather
     * than reusing the cached fd. No-op if the connection is already closed.
     */
    fun resetUsbDevice() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        // Capture the device while it is still known (closeDevice() below nulls
        // currentDevice). The audio interface ids are needed for the best-effort
        // USBDEVFS_CONNECT; the product name for the sysfs fallback.
        val device = currentDevice
        val productNameForRebind = device?.productName
        val audioInterfaceIds = device?.let { d ->
            (0 until d.interfaceCount).asSequence()
                .map { d.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
                .map { it.id }
                .toList()
        }?.distinct() ?: emptyList()

        // Release BEFORE resetting: while an interface is still force-claimed
        // there is no kernel driver attached for the reset's re-probe to act on,
        // so the claim would survive the reset unchanged.
        Log.i(TAG, "resetUsbDevice: release sequence start — device=${productNameForRebind} " +
            "audioIfaces=$audioInterfaceIds")
        val controlInterface = device?.let { d ->
            (0 until d.interfaceCount).map { d.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
        }
        controlInterface?.let {
            Log.i(TAG, "resetUsbDevice: releaseInterface(control iface ${it.id}): ${conn.releaseInterface(it)}")
        }
        claimedInterface?.let {
            Log.i(TAG, "resetUsbDevice: releaseInterface(streaming iface ${it.id}): ${conn.releaseInterface(it)}")
        }
        claimedInterface = null

        // Re-enumerate so the kernel re-probes and (best-effort) re-binds
        // snd-usb-audio. Racy on this SoC (see doc comment), but the only
        // root-free rebind available.
        runCatching { UsbAudioStream.nativeUsbResetOnly(fd) }
            .onFailure { Log.w(TAG, "USBDEVFS_RESET failed: ${it.message}") }
            .getOrNull()?.let { if (it != 0) Log.w(TAG, "USBDEVFS_RESET returned $it") }

        // Best-effort: ask the kernel to re-bind each audio interface. Dead on
        // this kernel (always EBUSY), but harmless; nativeUsbConnect returns the
        // POSIX errno so the real reason reaches this (surviving) debug log.
        for (ifaceId in audioInterfaceIds) {
            val connectResult = runCatching { UsbAudioStream.nativeUsbConnect(fd, ifaceId) }.getOrNull()
            when {
                connectResult == null ->
                    Log.w(TAG, "resetUsbDevice: CONNECT iface=$ifaceId threw an exception")
                connectResult == 0 ->
                    Log.i(TAG, "resetUsbDevice: CONNECT iface=$ifaceId OK — kernel driver re-bound")
                else ->
                    Log.w(TAG, "resetUsbDevice: CONNECT iface=$ifaceId errno=$connectResult — " +
                        "no re-bind via CONNECT on this kernel (expected)")
            }
        }

        // Finally close the connection fully (releases any remaining claims).
        closeDevice()

        // Fallback: a genuine sysfs unbind/bind — the only reliable rebind, but it
        // needs root and degrades silently without it.
        forceKernelRebind(productNameForRebind)
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
     * True while this process holds the device open with its audio interface
     * claimed — i.e. while the kernel's snd-usb-audio is kept detached by our
     * force=true usbfs claims, which is what silences every other app on the DAC.
     * The claims live exactly as long as this, so it (not "is a stream running")
     * is the honest answer to "does the driver still hold the DAC?": a stream can
     * be released between tracks while the connection is deliberately kept open.
     */
    val isDeviceOpen: Boolean
        get() = connection != null && claimedInterface != null

    /**
     * Close the USB device and release all resources.
     */
    fun closeDevice() {
        cachedDeviceInfo = null
        clockTopology = null
        activeClockSourceId = -1
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        Log.i(TAG, "USB device closed")
    }

    /** Clock IDs tried when the descriptors name no Clock Source at all. */
    private val bruteForceClockIds = intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
            0x28, 0x29, 0x2A, 0x06, 0x07, 0x08,
            0x10, 0x11, 0x12, 0x20, 0x21, 0x22)

    private val acInterfaceNumber: Int
        get() = clockTopology?.acInterface ?: 0

    private fun clockWIndex(csId: Int) = (csId shl 8) or acInterfaceNumber

    /** SET_CUR(CS_SAM_FREQ_CONTROL) on one Clock Source. */
    private fun writeRate(conn: UsbDeviceConnection, csId: Int, sampleRateHz: Int): Boolean {
        val data = byteArrayOf(
                (sampleRateHz and 0xFF).toByte(),
                ((sampleRateHz shr 8) and 0xFF).toByte(),
                ((sampleRateHz shr 16) and 0xFF).toByte(),
                ((sampleRateHz shr 24) and 0xFF).toByte())
        val ret = conn.controlTransfer(
                0x21,    // bmRequestType: Host-to-Device, Class, Interface
                0x01,    // bRequest: CUR
                0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                clockWIndex(csId),
                data, data.size,
                1000)    // timeout ms
        return ret >= 0
    }

    /** GET_CUR(CS_SAM_FREQ_CONTROL) on one Clock Source, or -1 if unsupported. */
    private fun readRate(conn: UsbDeviceConnection, csId: Int): Int {
        val data = ByteArray(4)
        val ret = conn.controlTransfer(
                0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                0x01,    // bRequest: CUR
                0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                clockWIndex(csId),
                data, data.size,
                1000)
        if (ret < 4) return -1
        return (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
    }

    /** Read the rate back after a SET_CUR, giving the DAC a few ms to apply it.
     *  Returns the last value read (-1 if the DAC does not support GET_CUR). */
    private fun readBackRate(conn: UsbDeviceConnection, csId: Int, expected: Int): Int {
        var rate = readRate(conn, csId)
        var tries = 0
        while (rate != expected && rate > 0 && tries++ < 5) {
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
            rate = readRate(conn, csId)
        }
        return rate
    }

    /**
     * Set the stream sample rate on the DAC's UAC2 Clock Source via SET_CUR.
     *
     * UAC2 SET_CUR format:
     *   bmRequestType = 0x21 (Host-to-Device, Class, Interface)
     *   bRequest = 0x01 (CUR)
     *   wValue = (CS_SAM_FREQ_CONTROL << 8) | 0 = 0x0100
     *   wIndex = (clockSourceEntityId << 8) | audioControlInterfaceNumber
     *   data = 4-byte LE sample rate
     *
     * The write goes to the Clock Source resolved from the descriptor topology
     * (see [ClockTopology]) and is then verified with GET_CUR. A DAC can accept
     * the SET_CUR and still not change rate — the transfer succeeds, but the
     * clock it was sent to is not the one driving the stream, or only covers one
     * rate family. Previously that counted as success and the DAC stayed on its
     * old rate (a 48 kHz track shown as 44.1 kHz on a Gustard X16). When the
     * read-back does not match, every other Clock Source the DAC declares is
     * tried in turn and the Clock Selector(s) are switched to the one that takes
     * the rate, which then becomes the active clock for later calls.
     *
     * A DAC that does not implement GET_CUR keeps the old behaviour: an accepted
     * SET_CUR on the resolved clock is taken as success.
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false
        val topo = clockTopology
        val primary = activeClockSourceId

        if (primary <= 0 && (topo == null || topo.sources.isEmpty())) {
            // No Clock Source in the descriptors at all: legacy brute force.
            for (csId in bruteForceClockIds) {
                if (writeRate(conn, csId, sampleRateHz)) {
                    Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS (brute force) with " +
                            "clockSourceId=0x${csId.toString(16)} wIndex=0x${clockWIndex(csId).toString(16)}")
                    activeClockSourceId = csId
                    return true
                }
            }
            Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
            return false
        }

        // 1. The clock that drives the streaming terminal.
        var unverifiedAccepted = false
        if (primary > 0 && writeRate(conn, primary, sampleRateHz)) {
            val readback = readBackRate(conn, primary, sampleRateHz)
            if (readback == sampleRateHz) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS, verified on " +
                        "clockSourceId=0x${primary.toString(16)} (wIndex=0x${clockWIndex(primary).toString(16)})")
                return true
            }
            if (readback < 0) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): accepted by clockSourceId=0x${primary.toString(16)} " +
                        "(wIndex=0x${clockWIndex(primary).toString(16)}); DAC does not report GET_CUR")
                unverifiedAccepted = true
            } else {
                Log.w(TAG, "setSampleRate($sampleRateHz Hz): clockSourceId=0x${primary.toString(16)} accepted " +
                        "SET_CUR but reports $readback Hz — trying the DAC's other clock sources")
            }
        } else if (primary > 0) {
            Log.w(TAG, "setSampleRate($sampleRateHz Hz): SET_CUR rejected by clockSourceId=0x${primary.toString(16)}")
        }

        // 2. The DAC's other Clock Sources (e.g. a separate 48 kHz-family clock).
        val others = topo?.sources?.filter { it != primary }.orEmpty()
        for (csId in others) {
            routeSelectorsTo(conn, topo!!, csId)
            if (!writeRate(conn, csId, sampleRateHz)) continue
            val readback = readBackRate(conn, csId, sampleRateHz)
            if (readback == sampleRateHz || (readback < 0 && !unverifiedAccepted)) {
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS on clockSourceId=0x${csId.toString(16)} " +
                        "(was 0x${primary.toString(16)}, read-back=$readback) — now the active clock")
                activeClockSourceId = csId
                return true
            }
            Log.w(TAG, "setSampleRate($sampleRateHz Hz): clockSourceId=0x${csId.toString(16)} reports $readback Hz")
        }

        // Nothing verified: put the selectors back on the original clock.
        if (topo != null && primary > 0 && others.isNotEmpty()) {
            routeSelectorsTo(conn, topo, primary)
            writeRate(conn, primary, sampleRateHz)
        }
        if (unverifiedAccepted) return true
        Log.w(TAG, "setSampleRate($sampleRateHz Hz): no clock source of the DAC took the rate")
        return false
    }

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR.
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val csId = activeClockSourceId
        val ids = if (csId > 0) intArrayOf(csId) else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (id in ids) {
            val rate = readRate(conn, id)
            if (rate >= 0) {
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${id.toString(16)} returned $rate Hz")
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

        val csId = activeClockSourceId
        val clockSourceIds = if (csId > 0) intArrayOf(csId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        for (id in clockSourceIds) {
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR
                    0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                    clockWIndex(id),
                    data,
                    data.size,
                    1000
            )
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Log.i(TAG, "readClockValid: clockSourceId=0x${id.toString(16)} valid=$valid")
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
