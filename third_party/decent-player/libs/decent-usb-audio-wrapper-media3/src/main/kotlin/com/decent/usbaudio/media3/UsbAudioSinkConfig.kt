package com.decent.usbaudio.media3

/**
 * Configuration for [UsbAudioSink].
 *
 * @param bitPerfectEnabled When true, audio is sent directly to the USB DAC
 *        bypassing the Android audio stack entirely.
 * @param forceRouteToSpeaker When true, the delegate AudioTrack is routed to
 *        the built-in speaker to prevent AudioFlinger from opening the USB device.
 */
data class UsbAudioSinkConfig(
    val bitPerfectEnabled: Boolean = true,
    val forceRouteToSpeaker: Boolean = true,
    /**
     * Issue `USBDEVFS_RESET` on the DAC when handing it back to the system.
     *
     * The soft-replug is what makes the kernel rebind the system USB-audio driver
     * on kernels where `USBDEVFS_CONNECT` returns EBUSY — but re-enumerating also
     * re-creates the ALSA card, and some Android TV boxes (observed on a Xiaomi TV
     * Box S 3rd Gen) do not track that: the framework keeps a stale card→PCM
     * mapping, the HAL can no longer open the USB output, and every *other* app
     * loses the DAC until the box is rebooted. Disabling this releases the
     * interfaces and closes the device without the reset, so the card instance the
     * system already knows about is left alone.
     */
    val resetOnRelease: Boolean = true,
    /**
     * Ask for USB permission from [UsbAudioSink.configure] when a DAC is found
     * but not yet granted.
     *
     * `configure()` runs as a track starts, so the system dialog this raises
     * always appears *after* playback has begun, and the track that triggered it
     * is already on the system-audio fallback (the grant does not move it). After
     * a denial it is raised again on every track. Hosts that request permission
     * themselves, before starting playback, should set this to false.
     */
    val requestPermissionOnConfigure: Boolean = true
)
