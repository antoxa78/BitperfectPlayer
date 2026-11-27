package com.example.usbclaimprobe

import android.app.Activity
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class MainActivity : Activity() {

    private val tag = "USBClaimProbe"
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (intent.action == "com.example.usbclaimprobe.USB_PERMISSION") {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    log("Permission granted for ${device?.productName} (vid:pid ${device?.vendorId}:${device?.productId})")
                    device?.let { runProbe(it) }
                } else {
                    log("!! USB permission DENIED")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        status = TextView(this)
        status.textSize = 13f
        status.setTextIsSelectable(true)
        setContentView(status)

        registerReceiver(
            permissionReceiver,
            IntentFilter("com.example.usbclaimprobe.USB_PERMISSION")
        )

        // Handle launch-by-USB-attach
        handleIntent(intent)

        // Also run an explicit scan on launch
        val dac = findDac()
        if (dac != null) {
            log("DAC found on launch: ${dac.deviceName}")
            requestAndRun(dac)
        } else {
            log("No class-1 USB audio device found on launch. Plug/attach the DAC and re-open, or re-run.")
            dumpAllDevices()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
    }

    private fun handleIntent(i: Intent) {
        if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { d ->
                log("USB attached intent: ${d.deviceName} vid:pid ${d.vendorId}:${d.productId}")
                requestAndRun(d)
            }
        }
    }

    private fun findDac(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
    }

    private fun dumpAllDevices() {
        val all = usbManager.deviceList
        if (all.isEmpty()) {
            log("USB device list empty.")
            return
        }
        for ((k, d) in all) {
            log("USB device: $k  name='${d.deviceName}'  vid:pid=${d.vendorId}:${d.productId}")
        }
    }

    private fun requestAndRun(d: UsbDevice) {
        if (usbManager.hasPermission(d)) {
            log("Permission already granted — running probe on ${d.deviceName}")
            runProbe(d)
        } else {
            log("Requesting USB permission for ${d.deviceName} ...")
            usbManager.requestPermission(
                d,
                PendingIntent.getBroadcast(
                    this, 0,
                    Intent("com.example.usbclaimprobe.USB_PERMISSION"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
    }

    private fun runProbe(d: UsbDevice) {
        log("")
        log("================ CLAIM PROBE ================")
        log("Device: ${d.deviceName}  vid:pid=${d.vendorId}:${d.productId}  " +
                "name='${d.productName}'")

        var connection: UsbDeviceConnection? = null
        try {
            connection = usbManager.openDevice(d)
            if (connection == null) {
                log("!! openDevice() returned NULL (system/other process holds it)")
                return
            }
            log("openDevice() OK (fd=${connection.fileDescriptor})")
        } catch (e: Exception) {
            log("!! openDevice() exception: ${e.javaClass.simpleName}: ${e.message}")
            return
        }

        for (i in 0 until d.interfaceCount) {
            val intf: UsbInterface = d.getInterface(i)
            val info = "if#$i  id=${intf.id}  class=${intf.interfaceClass} " +
                    "sub=${intf.interfaceSubclass}  proto=${intf.interfaceProtocol}  " +
                    "eps=${intf.endpointCount}"
            log(info)

            val isAudio = intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO
            if (!isAudio) {
                log("  -> non-audio, skipping claim test")
                continue
            }

            // Attempt claim WITHOUT force first (the behavior the decent driver uses)
            log("  -> claimInterface(force=false) ...")
            val claimedSoft = try {
                connection.claimInterface(intf, false)
            } catch (e: Exception) {
                log("     exception: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            log("     claimInterface(force=false) => $claimedSoft")

            // Then release and try with force=true
            if (claimedSoft) {
                connection.releaseInterface(intf)
                log("     released (soft claim)")
            }
            log("  -> claimInterface(force=true) ...")
            val claimedHard = try {
                connection.claimInterface(intf, true)
            } catch (e: Exception) {
                log("     exception: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            log("     claimInterface(force=true) => $claimedHard")

            if (claimedHard && intf.endpointCount > 0) {
                probeIsoEndpoint(connection, intf)
            }

            if (claimedHard) {
                connection.releaseInterface(intf)
                log("     released (hard claim)")
            }
        }

        // Prove low-level bus access: raw control transfer (reads 8 bytes of the
        // device descriptor, the same fd/usbdevfs path the userspace driver uses).
        log("")
        log("--- USBDEVFS-level probe: controlTransfer (GET_DESCRIPTOR device) ---")
        val buf = ByteArray(8)
        val n = try {
            connection.controlTransfer(
                /* requestType= */ 0x80, // IN, device
                /* request= */ 0x06,    // GET_DESCRIPTOR
                /* value= */ 0x0100,     // device descriptor at index 0
                /* index= */ 0,
                /* buffer= */ buf,
                /* length= */ buf.size,
                /* timeout= */ 1000
            )
        } catch (e: Exception) {
            log("!! controlTransfer exception: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
        if (n >= 0) {
            val hex = buf.take(n).joinToString(" ") { "%02x".format(it) }
            log("controlTransfer OK: read $n bytes: $hex  (len=${buf[0].toInt()} type=${buf[1].toInt()})")
        } else {
            log("!! controlTransfer FAILED: $n")
        }

        connection.close()
        log("connection closed")
        log("================ END PROBE ================")
    }

    private fun probeIsoEndpoint(connection: UsbDeviceConnection, intf: UsbInterface) {
        val outIso = (0 until intf.endpointCount)
            .map { intf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC }
        if (outIso == null) {
            log("     (no isochronous endpoint on this interface)")
            return
        }
        log("     iso endpoint present: ${endpointStr(intf, outIso)}")
    }

    private fun endpointStr(intf: UsbInterface, ep: UsbEndpoint): String {
        return "0x${String.format("%02x", ep.address)} dir=" +
                if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN" +
                " type=iso maxPkt=${ep.maxPacketSize} ifId=${intf.id}"
    }

    private fun log(msg: String) {
        Log.i(tag, msg)
        runOnUiThread {
            status.append(if (status.text.isEmpty()) msg else "\n$msg")
        }
    }
}
