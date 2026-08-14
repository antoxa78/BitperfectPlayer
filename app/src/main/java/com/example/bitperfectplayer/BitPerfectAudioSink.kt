package com.example.bitperfectplayer

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses AudioTrack directly for high-resolution PCM so DefaultAudioSink cannot convert it to
 * float or 16-bit PCM. Other formats are delegated to media3's standard sink.
 */
@UnstableApi
class BitPerfectAudioSink(
    private val delegate: AudioSink,
    private val bitPerfectManager: BitPerfectManager,
    private val selectedDevice: AtomicReference<AudioDeviceInfo?>
) : AudioSink {

    private var listener: AudioSink.Listener? = null
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var preferredDevice: AudioDeviceInfo? = null
    private var volume = 1f
    private var tunneling = false
    private var delegateConfigured = false

    private var directMode = false
    private var directFormat: Format? = null
    private var directChannelMask = AudioFormat.CHANNEL_INVALID
    private var directBufferSize = 0
    private var directTrack: AudioTrack? = null
    private var directConfig: AudioSink.AudioTrackConfig? = null
    private var routedDevice: AudioDeviceInfo? = null
    private var directFrameSize = 0
    private var directWrittenFrames = 0L
    private var directBasePlaybackHead = 0L
    private var directStartMediaTimeUs = C.TIME_UNSET
    private var directEnded = false
    private var playing = false
    private val audioTimestamp = AudioTimestamp()

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        delegate.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        delegate.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        delegate.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        return if (isDirectCandidate(format) && canCreateDirectTrack(format)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            delegate.getFormatSupport(format)
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        return if (isDirectCandidate(format)) AudioOffloadSupport.DEFAULT_UNSUPPORTED
        else delegate.getFormatOffloadSupport(format)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!directMode) return delegate.getCurrentPositionUs(sourceEnded)
        if (directTrack == null || directStartMediaTimeUs == C.TIME_UNSET) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        return directStartMediaTimeUs + framesToDurationUs(playedFrames())
    }

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (directMode) {
            releaseDirectTrack()
            delegate.reset()
            directMode = false
            directFormat = null
            delegateConfigured = false
        }

        if (isDirectCandidate(format) && outputChannels == null && canCreateDirectTrack(format)) {
            if (delegateConfigured) {
                delegate.reset()
                delegateConfigured = false
            }
            directMode = true
            directFormat = format
            directChannelMask = channelMaskFor(format)
            directBufferSize = specifiedBufferSize
            directFrameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            directWrittenFrames = 0L
            directBasePlaybackHead = 0L
            directStartMediaTimeUs = C.TIME_UNSET
            directEnded = false
            return
        }

        val outputDevice = preferredDevice ?: bitPerfectManager.findUsbOutputDevice()
        selectedDevice.set(outputDevice)
        delegate.setPreferredDevice(outputDevice)
        delegate.configure(format, specifiedBufferSize, outputChannels)
        delegateConfigured = true
    }

    override fun play() {
        playing = true
        if (directMode) directTrack?.play() else delegate.play()
    }

    override fun handleDiscontinuity() {
        if (directMode) directStartMediaTimeUs = C.TIME_UNSET else delegate.handleDiscontinuity()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!directMode) return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        val track = directTrack ?: try {
            initializeDirectTrack()
            checkNotNull(directTrack)
        } catch (e: AudioSink.InitializationException) {
            return fallBackToMedia3Sink(buffer, presentationTimeUs, encodedAccessUnitCount, e)
        }

        if (!buffer.hasRemaining()) return true
        if (directStartMediaTimeUs == C.TIME_UNSET) directStartMediaTimeUs = presentationTimeUs

        val written = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            throw AudioSink.WriteException(
                written,
                checkNotNull(directFormat),
                written == AudioTrack.ERROR_DEAD_OBJECT
            )
        }
        directWrittenFrames += written / directFrameSize
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (directMode) directEnded = true else delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean = if (directMode) directEnded && !hasPendingData() else delegate.isEnded()

    override fun hasPendingData(): Boolean {
        if (!directMode) return delegate.hasPendingData()
        return directTrack != null && directWrittenFrames > playedFrames()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (!directMode) delegate.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return if (directMode) PlaybackParameters.DEFAULT else delegate.getPlaybackParameters()
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (!directMode) delegate.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return if (directMode) false else delegate.getSkipSilenceEnabled()
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        if (directMode && directTrack != null) {
            releaseDirectTrack()
        } else {
            delegate.setAudioAttributes(audioAttributes)
        }
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        if (directMode && directTrack != null) releaseDirectTrack()
        else delegate.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        if (!directMode) delegate.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        selectedDevice.set(audioDeviceInfo)
        if (directMode) directTrack?.preferredDevice = audioDeviceInfo
        else delegate.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        if (!directMode) delegate.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        tunneling = true
        if (directMode) releaseDirectTrack()
        delegate.enableTunnelingV21()
    }

    override fun disableTunneling() {
        tunneling = false
        delegate.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        // Offload is intentionally disabled for bit-perfect playback.
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        // Offload is intentionally disabled for bit-perfect playback.
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        if (directMode) directTrack?.setVolume(volume) else delegate.setVolume(volume)
    }

    override fun pause() {
        playing = false
        if (directMode) directTrack?.pause() else delegate.pause()
    }

    override fun flush() {
        if (directMode) {
            directTrack?.flush()
            directWrittenFrames = 0L
            directBasePlaybackHead = directTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
            directStartMediaTimeUs = C.TIME_UNSET
            directEnded = false
        } else {
            delegate.flush()
        }
    }

    override fun reset() {
        releaseDirectTrack()
        directMode = false
        directFormat = null
        bitPerfectManager.clear()
        delegate.reset()
        delegateConfigured = false
    }

    override fun release() {
        reset()
        delegate.release()
    }

    private fun initializeDirectTrack() {
        val format = checkNotNull(directFormat)
        val minBufferSize = AudioTrack.getMinBufferSize(
            format.sampleRate,
            directChannelMask,
            format.pcmEncoding
        )
        if (minBufferSize <= 0) {
            throw initializationException(IllegalArgumentException("Unsupported direct PCM format"))
        }

        val frameSize = directFrameSize
        val requestedBufferSize = maxOf(minBufferSize * 2, directBufferSize)
        val bufferSize = ((requestedBufferSize + frameSize - 1) / frameSize) * frameSize
        val config = AudioSink.AudioTrackConfig(
            format.pcmEncoding,
            format.sampleRate,
            directChannelMask,
            tunneling,
            false,
            bufferSize
        )

        // Use the same device for mixer negotiation and AudioTrack routing. If ExoPlayer
        // has not supplied one, prefer the currently detected USB output.
        val outputDevice = preferredDevice ?: bitPerfectManager.findUsbOutputDevice()
        routedDevice = outputDevice
        selectedDevice.set(outputDevice)
        bitPerfectManager.updateAudioTrack(config, outputDevice)

        try {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(format.pcmEncoding)
                .setSampleRate(format.sampleRate)
                .setChannelMask(directChannelMask)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes.getAudioAttributesV21().audioAttributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) builder.setSessionId(audioSessionId)

            val track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw IllegalStateException("AudioTrack was not initialized")
            }
            outputDevice?.let { track.preferredDevice = it }
            track.setVolume(volume)
            directTrack = track
            directConfig = config
            directBasePlaybackHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            listener?.onAudioTrackInitialized(config)
            if (playing) track.play()
        } catch (e: Exception) {
            bitPerfectManager.clear()
            throw initializationException(e)
        }
    }

    private fun fallBackToMedia3Sink(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
        failure: AudioSink.InitializationException
    ): Boolean {
        val format = directFormat ?: throw failure
        bitPerfectManager.clear()
        directMode = false
        directFormat = null
        delegate.setPreferredDevice(routedDevice ?: preferredDevice)
        delegate.configure(format, directBufferSize, null)
        delegateConfigured = true
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    private fun releaseDirectTrack() {
        val track = directTrack ?: return
        val config = directConfig
        directTrack = null
        directConfig = null
        try { track.pause() } catch (_: Exception) {}
        track.release()
        bitPerfectManager.clear()
        if (config != null) listener?.onAudioTrackReleased(config)
    }

    private fun playedFrames(): Long {
        val track = directTrack ?: return 0L
        val timestampFrames = if (track.getTimestamp(audioTimestamp)) {
            audioTimestamp.framePosition
        } else {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
        return (timestampFrames - directBasePlaybackHead).coerceAtLeast(0L)
    }

    private fun framesToDurationUs(frames: Long): Long {
        val sampleRate = directFormat?.sampleRate ?: return 0L
        return frames * 1_000_000L / sampleRate
    }

    private fun initializationException(cause: Exception): AudioSink.InitializationException {
        return AudioSink.InitializationException(
            AudioTrack.STATE_UNINITIALIZED,
            directFormat?.sampleRate ?: 0,
            directChannelMask,
            directBufferSize,
            checkNotNull(directFormat),
            false,
            cause
        )
    }

    private fun isDirectCandidate(format: Format): Boolean {
        if (tunneling || format.sampleMimeType != androidx.media3.common.MimeTypes.AUDIO_RAW) return false
        return format.pcmEncoding == C.ENCODING_PCM_24BIT ||
            format.pcmEncoding == C.ENCODING_PCM_32BIT ||
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
    }

    private fun canCreateDirectTrack(format: Format): Boolean {
        if (!isDirectCandidate(format) || format.sampleRate <= 0 || format.channelCount <= 0) return false
        return AudioTrack.getMinBufferSize(
            format.sampleRate,
            channelMaskFor(format),
            format.pcmEncoding
        ) > 0
    }

    private fun channelMaskFor(format: Format): Int {
        return Util.getAudioTrackChannelConfig(format.channelCount)
    }
}
