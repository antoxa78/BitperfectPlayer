package com.example.bitperfectplayer

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Offload support provider that always reports "unsupported".
 *
 * Why: offload (compressed or PCM) routes audio through the DSP pipeline and
 * bypasses the bit-perfect mixer negotiated by BitPerfectManager, so it must
 * never be used. The non-offload path is where the empty AudioProcessorChain
 * guarantees untouched PCM. This also overrides media3's default provider,
 * which would otherwise claim compressed offload (MP3/AAC) on capable devices.
 */
@UnstableApi
class BitPerfectOffloadProvider(private val audioManager: AudioManager) : DefaultAudioSink.AudioOffloadSupportProvider {

    override fun getAudioOffloadSupport(format: Format, attributes: AudioAttributes): AudioOffloadSupport {
        val isHighResPcm = MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            (format.pcmEncoding == C.ENCODING_PCM_24BIT || format.pcmEncoding == C.ENCODING_PCM_32BIT)
        if (isHighResPcm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasUsbOutput()) {
            android.util.Log.i("BitPerfectOffload", "Refusing offload for high-res PCM (${format.pcmEncoding}) to keep the bit-perfect mixer path")
        }
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED
    }

    private fun hasUsbOutput(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }
}
