package com.example.bitperfectplayer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/**
 * Extracts raw float PCM for one SACD track of an ISO (played over SMB or locally).
 *
 * The native decoder reads the ISO through [reader] via JNI callbacks. Samples are
 * produced as interleaved little-endian float32 (C.ENCODING_PCM_FLOAT), which the
 * app's [BitPerfectAudioSink] passes through to AudioTrack. [seek] maps ExoPlayer
 * time to an absolute PCM frame so scrubbing is exact; the native reader jumps
 * straight to the sector holding it.
 *
 * Read and seek failures are raised as [IOException]s (not ParserException,
 * which media3 never retries): a transient SMB error is retried by the loader
 * and the native reader re-reads the same sectors.
 */
@OptIn(UnstableApi::class)
class SacdMediaExtractor(
    private val reader: SacdRandomAccess,
    private val area: Int = 0,
    private val track: Int = 0,
    private val outHz: Int = 176400,
    /** Emit DoP (DSD over PCM) instead of converting to PCM; see [DsdFileExtractor]. */
    private val dopRequested: Boolean = false,
) : Extractor {

    /** True once the native reader accepted DoP mode. */
    private var dop = false

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var handle = 0L
    private var sampleRate = 0
    private var channelCount = 2
    private var totalFrames = 0L
    private var seekMapQueued = false
    private var endOfStream = false
    private var lastReadFrame = 0L
    /** Seek not yet applied to the native reader (requested before it was open,
     *  or a native seek that failed and must be retried): target time, or -1. */
    private var pendingSeekTimeUs = -1L
    /** Reused for every read (the native side copies into it). */
    private var readBuf = ByteArray(0)
    @Volatile private var released = false
    @Volatile private var lastReadBytes = 0

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun seek(position: Long, timeUs: Long) {
        // Always remembered and applied from read(): the loader calls seek()
        // right after init() on a fresh load, before the native reader is
        // open, and a seek that fails on I/O must be retried, which only
        // read() can signal (by throwing).
        pendingSeekTimeUs = timeUs.coerceAtLeast(0L)
        endOfStream = false
    }

    /** Applies [pendingSeekTimeUs] to the open native reader. */
    private fun applyPendingSeek() {
        val timeUs = pendingSeekTimeUs
        if (timeUs < 0 || handle == 0L) return
        val frame = (timeUs * sampleRate / 1_000_000L).coerceIn(0L, maxOf(0L, totalFrames))
        if (frame != lastReadFrame) {
            val ok = try {
                SacdBridge.nativeSacdSeek(handle, frame) == 0
            } catch (e: Exception) {
                android.util.Log.w(TAG, "nativeSacdSeek threw", e)
                false
            }
            // Keep the request pending and let media3 retry the load: the
            // decoder's position is unknown now, so samples must not be
            // labelled with the target time.
            if (!ok) throw IOException("SACD seek to frame $frame failed (source not readable)")
            lastReadFrame = frame
        }
        pendingSeekTimeUs = -1L
    }

    override fun read(input: ExtractorInput, positionHolder: PositionHolder): Int {
        if (released) return Extractor.RESULT_END_OF_INPUT
        val currentOutput = output ?: return Extractor.RESULT_END_OF_INPUT

        if (!seekMapQueued) {
            android.util.Log.d(TAG, "init: nativeOpenSacd track=$track")
            handle = try {
                SacdBridge.nativeOpenSacd(reader, area, track, outHz)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "nativeOpenSacd threw", e)
                0L
            }
            if (handle == 0L) {
                // Can be a transient SMB failure as well as a bad image: an
                // IOException is retried by media3 (a few times) before it
                // surfaces as a playback error.
                throw IOException("SACD decode failed while opening the ISO")
            }
            sampleRate = SacdBridge.nativeSacdOutRate(handle).takeIf { it > 0 } ?: outHz
            channelCount = SacdBridge.nativeSacdChannels(handle).takeIf { it > 0 } ?: 2
            totalFrames = SacdBridge.nativeSacdTotalFrames(handle)
            // DoP: the DSD64 bitstream itself, 16 bits per 24-bit sample at
            // 176.4 kHz (same rate/timeline as the PCM path), for DACs that
            // decode DSD natively. Needs the reader's 2:1 grid (176.4 kHz).
            dop = dopRequested && try {
                SacdBridge.nativeSacdSetDop(handle, true) == 0
            } catch (e: Exception) {
                android.util.Log.w(TAG, "nativeSacdSetDop threw", e)
                false
            }
            if (dopRequested && !dop) android.util.Log.w(TAG, "DoP not possible for this area/rate — converting to PCM")
            android.util.Log.d(TAG, "nativeOpenSacd track=$track rate=$sampleRate ch=$channelCount frames=$totalFrames")

            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                // PCM: float is the decoder's native format and the only high-res
                // PCM encoding the Shield's AudioTrack accepts (24-bit int is
                // rejected by AudioTrack.getMinBufferSize at every rate).
                // DoP: 24-bit integers carried bit-exactly (USB driver mode only).
                .setPcmEncoding(if (dop) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelCount(channelCount)
                .setLabel(if (dop) "DSD64 DoP" else "DSD64 → PCM")
                .build()
            val audioTrack = currentOutput.track(0, C.TRACK_TYPE_AUDIO)
            audioTrack.format(format)
            trackOutput = audioTrack

            val seekMap = object : SeekMap {
                override fun isSeekable(): Boolean = true

                override fun getDurationUs(): Long =
                    if (totalFrames > 0) totalFrames * 1_000_000L / sampleRate else C.TIME_UNSET

                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                    SeekMap.SeekPoints(SeekPoint(timeUs, timeUs))
            }
            currentOutput.seekMap(seekMap)
            // ProgressiveMediaPeriod only finishes preparation once endTracks() is
            // called: without it the period never becomes prepared, renderers are
            // never enabled and playback sits in BUFFERING forever.
            currentOutput.endTracks()
            seekMapQueued = true
        }

        applyPendingSeek()
        if (endOfStream) return Extractor.RESULT_END_OF_INPUT

        // interleaved float32 (PCM) or packed 24-bit (DoP), per channel sample
        val frameSize = channelCount * (if (dop) 3 else 4)
        val bufBytes = (READ_BYTES / frameSize) * frameSize
        if (readBuf.size != bufBytes) readBuf = ByteArray(bufBytes)
        val frames = try {
            if (dop) SacdBridge.nativeSacdReadDop24Into(handle, readBuf)
            else SacdBridge.nativeSacdReadFloatInto(handle, readBuf)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "SACD read threw", e)
            -1
        }
        if (frames < 0) {
            // A read failure (e.g. a transient SMB error) must NOT be treated as
            // end-of-stream — that would truncate the track and skip to the next
            // item. An IOException is retried by media3's load error policy, and
            // the native reader retries the same sectors on the next read.
            throw IOException("SACD decode failed while reading the ISO")
        }
        if (frames == 0) {
            endOfStream = true
            trackOutput?.sampleMetadata(
                totalFrames * 1_000_000L / sampleRate,
                C.BUFFER_FLAG_END_OF_STREAM,
                0,
                0,
                null
            )
            return Extractor.RESULT_END_OF_INPUT
        }

        val size = frames * frameSize
        val timeUs = lastReadFrame * 1_000_000L / sampleRate
        trackOutput?.sampleData(ParsableByteArray(readBuf, size), size)
        // PCM is all-sync-sample: SampleQueue starts with upstreamKeyframeRequired=true and
        // silently drops any sample without the KEY_FRAME flag (never clearing the requirement),
        // so every sample must be flagged as a keyframe or nothing ever reaches the renderer.
        trackOutput?.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        lastReadFrame += frames
        lastReadBytes = size
        return Extractor.RESULT_CONTINUE
    }

    override fun release() {
        released = true
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                SacdBridge.nativeSacdClose(h)
            } catch (_: Exception) {
                // Ignore release errors.
            }
        }
    }

    /** Bytes produced by the most recent [read]; consumed by the media-extractor wrapper. */
    fun takeLastReadBytes(): Int {
        val b = lastReadBytes
        lastReadBytes = 0
        return b
    }

    companion object {
        private const val TAG = "SacdExt"
        private const val READ_BYTES = 96 * 1024 // ~96 KB of interleaved float32
    }
}