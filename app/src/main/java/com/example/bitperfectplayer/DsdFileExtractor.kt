package com.example.bitperfectplayer

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException

/**
 * Media3 [Extractor] for DSD files (DSF and DSDIFF/DFF, DSD64 and up, mono or
 * stereo). Two output modes:
 *
 * - **DoP** ([dopRequested], stereo, DSD64–DSD256): the raw DSD bits are packed
 *   into 24-bit PCM with the DoP 0x05/0xFA markers at dsd_rate/16 (176.4 kHz
 *   for DSD64). Nothing is filtered or converted; a DoP-capable DAC recognizes
 *   the markers and plays native DSD. Only meaningful when the samples reach the
 *   DAC bit-exactly, i.e. in "Bit-perfect (USB driver)" mode.
 * - **PCM**: converted in native code (FFmpeg dsd2pcm + a proper anti-alias
 *   decimation filter for the file's DSD rate) to float PCM at 176.4 kHz
 *   (192 kHz for 48 kHz-family DSD), matching the SACD ISO path.
 *
 * Reads through the regular Media3 data source, so local files, content URIs
 * and SMB all work.
 */
@OptIn(UnstableApi::class)
class DsdFileExtractor(private val dopRequested: Boolean) : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var info: DsdStreamInfo? = null
    private var tracksPublished = false
    private var dop = false

    /** Per-channel bytes already emitted (position within the audio data). */
    private var bytesPerChannelDone = 0L

    // DoP state
    private var dopPhase = 0

    // PCM state
    private var converter = 0L
    private var outHz = 0
    private var baseTimeUs = 0L
    private var framesSinceBase = 0L

    // Scratch buffers
    private var readBuf = ByteArray(0)
    private var interBuf = ByteArray(0)
    private var dopBuf = ByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(16)
        if (!input.peekFully(head, 0, 16, true)) return false
        return DsdParser.sniff(head)
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        var si = info
        if (si == null) {
            si = parseHeader(input)
            info = si
            publishTracks(si)
            bytesPerChannelDone = 0L
        } else if (input.position < si.dataStart) {
            // Loader restarted from the beginning of the file (seek(0, 0)):
            // step over the header again.
            skipFully(input, si.dataStart - input.position)
            bytesPerChannelDone = 0L
        }

        val remaining = si.validBytesPerChannel - bytesPerChannelDone
        if (remaining <= 0) return Extractor.RESULT_END_OF_INPUT

        val channels = si.channels
        val validPerChannel: Int
        if (si.container == DsdContainer.DSF) {
            // One channel-planar block group per read.
            val groupBytes = si.blockSize * channels
            if (readBuf.size < groupBytes) readBuf = ByteArray(groupBytes)
            if (!readFullyOrEnd(input, readBuf, groupBytes)) return Extractor.RESULT_END_OF_INPUT
            validPerChannel = minOf(si.blockSize.toLong(), remaining).toInt()
            ensureInter(validPerChannel * channels)
            DsdPacking.dsfGroupToInterleaved(
                readBuf, channels, si.blockSize, validPerChannel, si.lsbFirst, interBuf
            )
        } else {
            validPerChannel = minOf(DFF_CHUNK_PER_CHANNEL.toLong(), remaining).toInt()
            ensureInter(validPerChannel * channels)
            if (!readFullyOrEnd(input, interBuf, validPerChannel * channels)) return Extractor.RESULT_END_OF_INPUT
        }

        val startBytesPerChannel = bytesPerChannelDone
        bytesPerChannelDone += if (si.container == DsdContainer.DSF) si.blockSize.toLong() else validPerChannel.toLong()

        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (dop) {
            val even = validPerChannel and 1.inv()  // a trailing odd byte (8 DSD bits) is dropped
            if (even == 0) return Extractor.RESULT_CONTINUE
            val size = even / 2 * channels * 3
            if (dopBuf.size < size) dopBuf = ByteArray(size)
            dopPhase = DsdPacking.packDop(interBuf, channels, even, dopBuf, dopPhase)
            val timeUs = si.timeUsForBytesPerChannel(startBytesPerChannel)
            track.sampleData(ParsableByteArray(dopBuf, size), size)
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        } else {
            // Native side reads only the first validPerChannel * channels bytes.
            val pcm = SacdBridge.nativeDsdConvProcess(converter, interBuf, validPerChannel)
                ?: throw ParserException.createForMalformedContainer("DSD to PCM conversion failed", null)
            if (pcm.isEmpty()) return Extractor.RESULT_CONTINUE
            val frames = pcm.size / (4 * channels)
            val timeUs = baseTimeUs + framesSinceBase * 1_000_000L / outHz
            framesSinceBase += frames
            track.sampleData(ParsableByteArray(pcm), pcm.size)
            // PCM is all-sync: without KEY_FRAME the sample queue drops everything.
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, pcm.size, 0, null)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        val si = info ?: return
        bytesPerChannelDone = if (position <= si.dataStart) 0L else si.bytesPerChannelAt(position)
        // A DoP stream may start on either marker as long as it then alternates.
        dopPhase = 0
        if (converter != 0L) SacdBridge.nativeDsdConvReset(converter)
        baseTimeUs = si.timeUsForBytesPerChannel(bytesPerChannelDone)
        framesSinceBase = 0L
    }

    override fun release() {
        if (converter != 0L) {
            SacdBridge.nativeDsdConvClose(converter)
            converter = 0L
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun parseHeader(input: ExtractorInput): DsdStreamInfo {
        val src = object : DsdByteSource {
            override val position: Long get() = input.position
            override fun readFully(buf: ByteArray, off: Int, len: Int) = input.readFully(buf, off, len)
            override fun skipFully(len: Long) = this@DsdFileExtractor.skipFully(input, len)
        }
        val si = try {
            DsdParser.parse(src)
        } catch (e: DsdFormatException) {
            throw ParserException.createForUnsupportedContainerFeature(e.message)
        }
        dop = dopRequested && si.channels == 2 && DsdPacking.dopRate(si.dsdRate) <= MAX_DOP_RATE
        if (!dop) {
            converter = SacdBridge.nativeDsdConvCreate(si.channels, si.dsdRate, 0)
            if (converter == 0L) {
                throw ParserException.createForUnsupportedContainerFeature("DSD rate ${si.dsdRate} not supported")
            }
            outHz = SacdBridge.nativeDsdConvOutHz(converter)
        }
        Log.i(TAG, "${si.container} ${si.channels}ch DSD${si.dsdRate / 44100} " +
            "${si.durationUs / 1000} ms -> ${if (dop) "DoP ${DsdPacking.dopRate(si.dsdRate)} Hz" else "PCM $outHz Hz"}")
        return si
    }

    private fun publishTracks(si: DsdStreamInfo) {
        if (tracksPublished) return
        val out = output ?: return
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(if (dop) C.ENCODING_PCM_24BIT else C.ENCODING_PCM_FLOAT)
            .setSampleRate(if (dop) DsdPacking.dopRate(si.dsdRate) else outHz)
            .setChannelCount(si.channels)
            .setLabel("${DsdPacking.dsdName(si.dsdRate)} ${if (dop) "DoP" else "→ PCM"}")
            .build()
        val track = out.track(0, C.TRACK_TYPE_AUDIO)
        track.format(format)
        trackOutput = track
        out.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = si.durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val perCh = si.seekBytesPerChannel(timeUs)
                return SeekMap.SeekPoints(
                    SeekPoint(si.timeUsForBytesPerChannel(perCh), si.filePositionForBytesPerChannel(perCh))
                )
            }
        })
        out.endTracks()
        tracksPublished = true
    }

    private fun ensureInter(size: Int) {
        if (interBuf.size < size) interBuf = ByteArray(size)
    }

    /** False at a clean end of input; a file truncated mid-chunk also ends playback. */
    private fun readFullyOrEnd(input: ExtractorInput, buf: ByteArray, len: Int): Boolean =
        try {
            input.readFully(buf, 0, len, true)
        } catch (_: EOFException) {
            false
        }

    private fun skipFully(input: ExtractorInput, len: Long) {
        var left = len
        while (left > 0) {
            val n = minOf(left, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(n)
            left -= n
        }
    }

    companion object {
        private const val TAG = "DsdFileExtractor"
        /** DFF read size per channel (even, so DoP pairs stay aligned). */
        private const val DFF_CHUNK_PER_CHANNEL = 4096
        /** DoP above DSD256 (705.6 kHz PCM) exceeds what the USB driver accepts. */
        private const val MAX_DOP_RATE = 705_600

        fun isDsdFileUri(uriString: String?): Boolean {
            val s = uriString?.substringBefore('?')?.lowercase() ?: return false
            return s.endsWith(".dsf") || s.endsWith(".dff")
        }
    }
}
