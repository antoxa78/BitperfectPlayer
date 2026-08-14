package com.example.bitperfectplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages Android 14+ (API 34) Bit-Perfect mixer attributes.
 * Allows bypassing the system mixer for USB DACs when the track format matches.
 *
 * Thread-safety: updateAudioTrack() is called on ExoPlayer's internal playback/audio
 * thread (immediately before AudioTrack construction, so it must stay synchronous —
 * it cannot be deferred to a background executor). clear() is called from the app's
 * main thread via Player.Listener callbacks in PlaybackService. These two entry points
 * can race: without coordination, a clear() for an old track can land on top of an
 * updateAudioTrack() for a track that started afterward, silently dropping the
 * bit-perfect mixer preference for the new track with no error surfaced anywhere.
 *
 * Fixed with a lock (for mutual exclusion — AudioManager calls must not interleave)
 * plus a monotonic version counter (so a call that's already been superseded by the
 * time it acquires the lock is discarded instead of overwriting the newer state).
 */
@UnstableApi
class BitPerfectManager(private val context: Context) {

    companion object {
        private const val TAG = "BitPerfectManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val lock = Any()
    private var appliedVersion = 0L
    private val nextVersion = AtomicLong(0L)
    private var appliedDevice: AudioDeviceInfo? = null

    /**
     * Updates mixer attributes from the final AudioTrack output configuration.
     * This runs immediately before AudioTrack creation, so the preference applies to that track.
     * Must never throw because it is part of the audio-track creation path.
     */
    fun updateAudioTrack(
        config: AudioSink.AudioTrackConfig,
        outputDevice: AudioDeviceInfo? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        // Grab the version stamp *before* the lock — it must reflect when the caller
        // actually decided to apply this config, not when it happened to get scheduled.
        val myVersion = nextVersion.incrementAndGet()

        val usbDeviceInfo = when {
            outputDevice != null -> outputDevice.takeIf(::isUsbOutputDevice)
            else -> findUsbOutputDevice()
        } ?: run {
            Log.d(TAG, "No USB output device found for bit-perfect routing")
            return
        }

        synchronized(lock) {
            if (myVersion <= appliedVersion) {
                Log.d(TAG, "Skipping stale updateAudioTrack (v$myVersion, already at v$appliedVersion)")
                return
            }
            try {
                if (config.offload || config.tunneling) {
                    clearPreferredMixerAttributesLocked(usbDeviceInfo)
                } else {
                    val outputFormat = AudioFormat.Builder()
                        .setEncoding(config.encoding)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(config.channelConfig)
                        .build()
                    applyBitPerfectAttributesLocked(usbDeviceInfo, outputFormat)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update bit-perfect mixer attributes", e)
            }
            appliedDevice = usbDeviceInfo
            appliedVersion = myVersion
        }
    }

    /**
     * Clears any preferred mixer attributes. Should be called when playback stops or service is destroyed.
     */
    fun clear() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        val myVersion = nextVersion.incrementAndGet()
        val device = synchronized(lock) { appliedDevice } ?: findUsbOutputDevice()

        synchronized(lock) {
            if (myVersion <= appliedVersion) {
                Log.d(TAG, "Skipping stale clear (v$myVersion, already at v$appliedVersion)")
                return
            }
            if (device != null) {
                clearPreferredMixerAttributesLocked(device)
            } else {
                Log.d(TAG, "No USB output device found while clearing mixer attributes")
            }
            appliedDevice = null
            appliedVersion = myVersion
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearPreferredMixerAttributesLocked(device: AudioDeviceInfo) {
        val mediaAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        try {
            audioManager.clearPreferredMixerAttributes(mediaAttr, device)
            Log.i(TAG, "Cleared preferred mixer attributes for ${device.productName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear preferred mixer attributes", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyBitPerfectAttributesLocked(device: AudioDeviceInfo, outputFormat: AudioFormat) {
        val mediaAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // 1. Query supported mixer attributes for this device
        val supportedMixerAttributes = try {
            audioManager.getSupportedMixerAttributes(device)
        } catch (e: Exception) {
            Log.e(TAG, "getSupportedMixerAttributes failed", e)
            return
        }

        // 2. Match the actual output format exactly. The source format can be compressed
        //    (MP3/FLAC) and is not suitable for selecting a mixer configuration.
        val bitPerfectAttr = supportedMixerAttributes.firstOrNull { attr ->
            attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    isFormatMatch(attr.format, outputFormat)
        }

        if (bitPerfectAttr != null) {
            try {
                audioManager.setPreferredMixerAttributes(mediaAttr, device, bitPerfectAttr)
                Log.i(TAG, "SUCCESS: Set BIT_PERFECT mixer for ${device.productName} " +
                        "(${outputFormat.sampleRate}Hz, ${outputFormat.channelCount}ch, encoding=${outputFormat.encoding})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set preferred mixer attributes", e)
            }
        } else {
            Log.w(TAG, "No matching BIT_PERFECT mixer found for format: " +
                    "${outputFormat.sampleRate}Hz, ${outputFormat.channelCount}ch, encoding=${outputFormat.encoding}. Supported mixers: " +
                    supportedMixerAttributes.joinToString { "${it.format.sampleRate}Hz/${it.format.encoding}" })
            // Clear if we had something set previously that doesn't match now
            clearPreferredMixerAttributesLocked(device)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isFormatMatch(mixerFormat: AudioFormat, outputFormat: AudioFormat): Boolean {
        if (mixerFormat.sampleRate != outputFormat.sampleRate) return false
        if (mixerFormat.channelCount != outputFormat.channelCount) return false
        if (mixerFormat.encoding != outputFormat.encoding) return false

        // Match the layout where both formats expose a channel mask. Some devices expose
        // only a channel count, so do not reject a valid count-only description.
        val mixerMask = mixerFormat.channelMask
        val outputMask = outputFormat.channelMask
        return mixerMask == 0 || outputMask == 0 || mixerMask == outputMask
    }

    fun findUsbOutputDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            isUsbOutputDevice(it)
        }
    }

    private fun isUsbOutputDevice(device: AudioDeviceInfo): Boolean {
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
