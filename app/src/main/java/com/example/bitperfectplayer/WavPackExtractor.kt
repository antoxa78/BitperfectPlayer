package com.example.bitperfectplayer

import android.content.Context
import android.net.Uri
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
import com.beatofthedrum.wvcodec.WavpackConfig
import com.beatofthedrum.wvcodec.WavpackContext
import com.beatofthedrum.wvcodec.WavpackUtils
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [Extractor] for WavPack (`.wv`) files.
 *
 * Media3 ships no WavPack extractor, so decoding is delegated to `javasound-wavpack`, a
 * pure-Java port of the reference unpacker. No NDK code is involved.
 *
 * ### Bit-exactness
 * Every WavPack block header stores a CRC-32 of the decoded samples and the decoder
 * compares its output against it, so a lossless file that reports
 * `WavpackGetNumErrors() == 0` is bit-exact by construction — which is what this app is
 * for. Lossy ("hybrid") WavPack files play as decoded, but are not bit-perfect themselves.
 *
 * ### Why this owns its I/O
 * [ExtractorInput] cannot seek (Media3 repositions the *DataSource* instead), and
 * `javasound-wavpack` has no seek call of its own, so a seekable source is needed to land
 * on an arbitrary WavPack block. Like the SACD path this extractor therefore reads through
 * a [SacdRandomAccess] and is handed a pass-through DataSource; `input` is unused.
 *
 * ### How seeking works
 * WavPack blocks are independently decodable, so a seek means: close the decoder, reopen it
 * on a stream that starts exactly at a block boundary, then decode-and-discard the rest of
 * that block. Block headers are parsed as bytes flow past ([BlockIndexTracker]), so the
 * byte-offset -> first-sample mapping is learned for free during playback, which makes
 * repeated seeks exact and cheap. A seek past everything read so far estimates the offset
 * from the average bytes/sample and then refines it by walking block headers, which costs
 * 32 bytes per block rather than the whole block.
 */
@OptIn(UnstableApi::class)
class WavPackExtractor(
    private val access: SacdRandomAccess,
) : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    // ── stream description, filled on first open ───────────────────────────
    private var sampleRate = 0
    private var channels = 0
    private var bytesPerSample = 0
    private var pcmEncoding = C.ENCODING_PCM_16BIT
    private var floatData = false
    private var totalSamples = 0L
    private var tracksPublished = false

    // ── decoder ────────────────────────────────────────────────────────────
    private var ctx: WavpackContext? = null
    private var ctxStream: DataInputStream? = null

    /** Config of the first open, replayed onto decoders reopened mid-file. */
    private var savedConfig: WavpackConfig? = null

    /** Byte offset to restart the decoder at, or -1 when not restarting. */
    private var pendingOffset = -1L
    /** Sample the restarted decoder should land on, or -1 to decode from there. */
    private var pendingSample = -1L

    private val index = BlockIndexTracker()
    private var fileLength: Long = -1L

    // ── scratch ────────────────────────────────────────────────────────────
    private var decodeBuf = IntArray(0)
    private var pcmBuf = ByteArray(0)
    private var pcmByteBuffer: ByteBuffer? = null

    /** Consecutive zero-length unpacks, so a transient one is not read as end of file. */
    private var emptyReads = 0

    /** Samples left in the block the decoder is on, or 0 when not known. */
    private var blockRemaining = 0L

    // Not named durationUs: inside the SeekMap below, that name would resolve to Media3's
    // own getDurationUs() rather than this, which recurses until the stack runs out.
    private val trackDurationUs: Long
        get() = if (sampleRate > 0 && totalSamples > 0) totalSamples * 1_000_000L / sampleRate else 0L

    // ── Extractor ──────────────────────────────────────────────────────────

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(4)
        if (access.read(0L, head, 4) < 4) return false
        return WavPackFormat.isWvpkMagic(head, 0)
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (fileLength < 0) fileLength = access.length()

        if (pendingOffset >= 0) {
            closeDecoder()
            openDecoder(pendingOffset)
            pendingOffset = -1L
        }
        if (ctx == null) openDecoder(0L)
        if (!tracksPublished) publishTracks()
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val c = ctx ?: return Extractor.RESULT_END_OF_INPUT

        // Land on the sample a seek asked for, if we are not already past it.
        val target = pendingSample
        if (target >= 0) {
            pendingSample = -1L
            while (WavpackUtils.WavpackGetSampleIndex(c) < target) {
                val want = unpackRequest(c).toLong()
                val got = WavpackUtils.WavpackUnpackSamples(c, decodeBuf, want)
                if (got <= 0) break
                blockRemaining -= got
            }
        }

        // WavpackGetSampleIndex is the absolute index of the next sample to be returned,
        // including after a mid-file reopen, so it doubles as the timeline position.
        val startIndex = WavpackUtils.WavpackGetSampleIndex(c)
        val n = WavpackUtils.WavpackUnpackSamples(c, decodeBuf, unpackRequest(c).toLong())
        if (n <= 0) {
            // A zero return normally means the end of the file, but only trust it once the
            // sample count agrees: retrying costs nothing and a premature end would cut
            // the track short.
            if (++emptyReads < EMPTY_READ_LIMIT && (totalSamples <= 0L || startIndex < totalSamples)) {
                return Extractor.RESULT_CONTINUE
            }
            logDecodeErrors(c)
            return Extractor.RESULT_END_OF_INPUT
        }
        emptyReads = 0
        blockRemaining -= n
        val size = writePcm(n.toInt())
        if (size <= 0) return Extractor.RESULT_CONTINUE
        val timeUs = if (sampleRate > 0) startIndex * 1_000_000L / sampleRate else 0L
        track.sampleData(ParsableByteArray(pcmBuf, size), size)
        // Raw PCM is all-sync: without KEY_FRAME the sample queue drops everything.
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // `position` is Media3's guess at a byte offset and is meaningless here, because
        // this extractor reads through its own seekable source. timeUs is authoritative.
        val sample = if (sampleRate > 0) timeUs * sampleRate / 1_000_000L else -1L
        if (sample < 0) {
            pendingOffset = position
            pendingSample = -1L
        } else {
            pendingOffset = index.offsetForSample(access, fileLength, sample)
            pendingSample = sample
        }
    }

    override fun release() {
        closeDecoder()
        output = null
        trackOutput = null
        tracksPublished = false
        index.reset()
    }

    // ── opening / closing ──────────────────────────────────────────────────

    private fun openDecoder(atByte: Long) {
        val stream = WavPackBlockStream(access, atByte, fileLength, index)
        // The stream sees each block header on its way to the decoder, which is the only
        // place the current block's length becomes known.
        stream.onBlockHeader = { samples -> blockRemaining = samples }
        val data = DataInputStream(stream)
        val c = try {
            WavpackUtils.WavpackOpenFileInput(data)
        } catch (e: Exception) {
            throw ParserException.createForMalformedContainer("not a WavPack stream: ${e.message}", e)
        }
        if (c == null) throw ParserException.createForMalformedContainer("not a WavPack stream", null)

        ctx = c
        ctxStream = data

        sampleRate = WavpackUtils.WavpackGetSampleRate(c).toInt()
        channels = WavpackUtils.WavpackGetNumChannels(c)
        bytesPerSample = WavpackUtils.WavpackGetBytesPerSample(c)
        val bits = WavpackUtils.WavpackGetBitsPerSample(c)
        totalSamples = WavpackUtils.WavpackGetNumSamples(c)
        floatData = (index.firstFlags and FLAG_FLOAT_DATA) != 0 || c.config.float_norm_exp != 0

        if (sampleRate <= 0 || channels <= 0 || channels > MAX_CHANNELS || bytesPerSample !in 1..4) {
            throw ParserException.createForUnsupportedContainerFeature(
                "unsupported WavPack stream: $sampleRate Hz, $channels ch, $bytesPerSample B/sample"
            )
        }

        // A decoder reopened mid-file only sees the block it starts on, and an explicit
        // sample rate lives in an ID_SAMPLE_RATE sub-block of block 0 only. Replay the
        // config captured on the first open so the rate survives a seek.
        if (savedConfig == null) {
            savedConfig = c.config
        } else if (sampleRate == SAMPLE_RATE_FALLBACK && savedConfig!!.sample_rate > 0) {
            WavpackUtils.WavpackSetConfiguration(c, savedConfig!!, 0)
            sampleRate = savedConfig!!.sample_rate.toInt()
        }

        if (decodeBuf.size < CHUNK_FRAMES * channels) decodeBuf = IntArray(CHUNK_FRAMES * channels)
        blockRemaining = 0L
        emptyReads = 0
        Log.i(
            TAG,
            "opened at byte $atByte: $sampleRate Hz, $channels ch, $bits bit, " +
                (if (floatData) "float" else "int") + ", $totalSamples samples, " +
                if (WavpackUtils.WavpackLossyBlocks(c) > 0) "hybrid/lossy" else "lossless"
        )
    }

    private fun closeDecoder() {
        ctx?.let { runCatching { WavpackUtils.WavpackCloseFile(it) } }
        ctx = null
        ctxStream = null
        blockRemaining = 0L
    }

    /**
     * How many frames to ask for, never reaching past the end of the current block.
     *
     * The port only ever compares a block's checksum when one unpack call finishes exactly
     * on a block boundary, and it carries the running checksum between calls without
     * reseeding it per block the way the encoder does. Letting a call straddle a boundary
     * therefore makes its own error counter report failures on a clean file — measured at
     * six spurious ones over this 5373-block test file. Staying inside the block keeps that
     * counter meaningful; it is not load-bearing here, but it is the only integrity signal
     * the library offers, so it is worth keeping honest.
     */
    private fun unpackRequest(c: WavpackContext): Int {
        if (blockRemaining <= 0L) {
            // Unknown until a block header has gone past; leave it unset and stay capped
            // by the buffer size alone.
            return CHUNK_FRAMES
        }
        return minOf(CHUNK_FRAMES.toLong(), blockRemaining).toInt().coerceAtLeast(1)
    }

    /**
     * Reports what the decoder thinks went wrong, if anything.
     *
     * Reads the message off the context rather than through WavpackGetErrorMessage, which
     * dereferences the message unconditionally and throws when there is none — which is the
     * normal case for the counter being non-zero.
     *
     * The counter is kept but not trusted: this library seeds its running block checksum
     * once per open and never resets it between blocks the way the encoder does, so a file
     * that decodes perfectly still reports a handful of errors across a full playthrough.
     * Re-decoding block by block against each header's stored checksum is what establishes
     * that the audio is intact, and that is not work to do on a playback thread.
     */
    private fun logDecodeErrors(c: WavpackContext) {
        val errors = WavpackUtils.WavpackGetNumErrors(c)
        if (errors <= 0) return
        val message = c.error_message?.message ?: "no detail"
        Log.w(
            TAG,
            "decoder reported $errors error(s) at sample ${WavpackUtils.WavpackGetSampleIndex(c)}: $message " +
                "(its block-checksum counter is unreliable across blocks, so this may be spurious)"
        )
    }

    // ── output ─────────────────────────────────────────────────────────────

    private fun publishTracks() {
        val out = output ?: return
        pcmEncoding = when {
            floatData -> C.ENCODING_PCM_FLOAT
            bytesPerSample == 1 -> C.ENCODING_PCM_8BIT
            bytesPerSample == 2 -> C.ENCODING_PCM_16BIT
            bytesPerSample == 3 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_32BIT
        }
        val track = out.track(0, C.TRACK_TYPE_AUDIO)
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(pcmEncoding)
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                .setLabel("WavPack ${if (floatData) "float" else "${bytesPerSample * 8}-bit"}")
                .build()
        )
        trackOutput = track
        out.seekMap(object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = trackDurationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                // Cheap: exact for anything already streamed, otherwise the estimate alone.
                // Refinement does real I/O and happens in seek(), on the loading thread.
                val sample = timeUs * sampleRate / 1_000_000L
                val offset = index.offsetForSample(access, fileLength, sample, precise = false)
                return SeekMap.SeekPoints(SeekPoint(timeUs, offset))
            }
        })
        out.endTracks()
        tracksPublished = true
    }

    /** Converts [frames] decoded int32 samples into little-endian PCM in [pcmBuf]. */
    private fun writePcm(frames: Int): Int {
        val total = frames * channels
        val need = total * bytesPerSample
        if (pcmBuf.size < need) {
            pcmBuf = ByteArray(need)
            pcmByteBuffer = null
        }
        var buf = pcmByteBuffer
        if (buf == null || buf.capacity() < need) {
            // A view over pcmBuf, not a buffer of its own: pcmBuf is the array the sink
            // reads, so writing anywhere else would hand it untouched zeros, i.e. silence.
            buf = ByteBuffer.wrap(pcmBuf).order(ByteOrder.LITTLE_ENDIAN)
            pcmByteBuffer = buf
        }
        buf.clear()
        if (floatData) {
            // For float files the decoder leaves IEEE bit patterns in the int32 buffer.
            for (i in 0 until total) buf.putInt(decodeBuf[i])
        } else {
            when (bytesPerSample) {
                1 -> for (i in 0 until total) buf.put((decodeBuf[i] and 0xFF).toByte())
                2 -> for (i in 0 until total) buf.putShort(decodeBuf[i].toShort())
                3 -> for (i in 0 until total) {
                    val v = decodeBuf[i]
                    buf.put(v.toByte())
                    buf.put((v shr 8).toByte())
                    buf.put((v shr 16).toByte())
                }
                else -> for (i in 0 until total) buf.putInt(decodeBuf[i])
            }
        }
        return need
    }

    companion object {
        private const val TAG = "WavPackExtractor"
        private const val CHUNK_FRAMES = 4096

        /** WavPack block flag: samples are IEEE float rather than integers. */
        private const val FLAG_FLOAT_DATA = 0x80

        /** Rate the decoder assumes when a block carries no explicit one. */
        private const val SAMPLE_RATE_FALLBACK = 44100

        private const val MAX_CHANNELS = 8

        /**
         * How many zero-length unpacks in a row to shrug off before calling it the end.
         * The decoder returns zero at end of file, but confirming that against the sample
         * count first is cheap and avoids truncating a track.
         */
        private const val EMPTY_READ_LIMIT = 4

        fun isWavPackUri(uriString: String?): Boolean {
            val s = uriString?.substringBefore('?')?.lowercase() ?: return false
            return s.endsWith(".wv") || s.endsWith(".wvp")
        }
    }
}

/** WavPack block header facts, parsed from a 32-byte header. */
internal object WavPackFormat {
    const val HEADER_SIZE = 32

    fun isWvpkMagic(b: ByteArray, off: Int): Boolean =
        b[off] == 'w'.code.toByte() && b[off + 1] == 'v'.code.toByte() &&
            b[off + 2] == 'p'.code.toByte() && b[off + 3] == 'k'.code.toByte()

    /** ckSize: total block size is this plus 8, the first 32 of which are the header. */
    fun ckSize(h: ByteArray): Long = (le32(h, 4).toLong() and 0xFFFFFFFFL)

    fun totalBlockSize(h: ByteArray): Long = 8L + ckSize(h)

    /** 40-bit block index: low 32 bits at offset 16, high byte at offset 10. */
    fun blockIndex(h: ByteArray): Long =
        (le32(h, 16).toLong() and 0xFFFFFFFFL) or ((h[10].toLong() and 0xFF) shl 32)

    fun blockSamples(h: ByteArray): Long = le32(h, 20).toLong() and 0xFFFFFFFFL

    fun flags(h: ByteArray): Int = le32(h, 24)

    fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}

/**
 * The byte-offset -> first-sample map for a WavPack file. It is filled for free as blocks
 * stream past, and queried to find where to restart the decoder for a seek.
 *
 * Every offset this hands out is a real block boundary at or before the requested sample,
 * so a decoder opened on one always finds a well-formed header and only ever has to
 * discard the remainder of one block.
 */
internal class BlockIndexTracker {
    private var pos = LongArray(512)
    private var idx = LongArray(512)
    private var count = 0

    /** Flags of the first block seen, used to spot float streams. */
    var firstFlags: Int = 0
        private set

    fun reset() {
        count = 0
        firstFlags = 0
    }

    /**
     * Records a block that streamed past. Entries stay sorted by block index, so
     * re-reading a region after a seek is a no-op and the array may end up with gaps.
     *
     * Blocks carrying no samples are left out. A WavPack file ends with one of these
     * holding its tags, and it reports a block index of 0, so recording it would put a
     * backwards step in the middle of the array and break the binary search below. It could
     * never be the answer anyway: a block with no samples contains no sample to seek to.
     */
    fun record(blockIndex: Long, bytePos: Long, blockSamples: Long, flags: Int) {
        if (count == 0) firstFlags = flags
        if (blockSamples <= 0L) return
        if (count > 0 && bytePos <= pos[count - 1]) return   // already covered
        if (count == pos.size) {
            pos = pos.copyOf(count * 2)
            idx = idx.copyOf(count * 2)
        }
        pos[count] = bytePos
        idx[count] = blockIndex
        count++
    }

    /**
     * Byte offset of a block boundary at or before [sample], so the decoder can be
     * restarted there and the rest of that block discarded.
     *
     *  1. The recorded index, which is exact for anything already streamed — this is what
     *     every seek after the first resolves to, and it costs nothing.
     *  2. Otherwise [bisect] the file on the block index, which is what a first seek into
     *     unexplored territory has to do.
     *
     * [precise] = false stops at the estimate, because [bisect] reads the file and this
     * also runs from [SeekMap.getSeekPoints], which may be on the app thread.
     */
    fun offsetForSample(
        access: SacdRandomAccess,
        fileLength: Long,
        sample: Long,
        precise: Boolean = true,
    ): Long {
        if (sample <= 0) return 0L
        knownOffsetAtOrBefore(sample)?.let { return it }
        if (!precise) return estimate(sample)
        return bisect(access, fileLength, sample)
    }

    /**
     * Last recorded block at or before [sample], but only when the recorded region reaches
     * past it — that is what makes the answer the block *containing* the sample instead of
     * merely the last thing we happened to read.
     *
     * Returns null for a target beyond the recorded prefix, so the seek falls through to
     * the bisection. Restarting at the end of the prefix would be correct too, but it
     * would decode-and-discard the whole span in between, which is minutes of audio.
     */
    private fun knownOffsetAtOrBefore(sample: Long): Long? {
        var lo = 0
        var hi = count - 1
        var found = -1
        while (lo <= hi) {                       // upper bound: last entry <= sample
            val mid = (lo + hi) ushr 1
            if (idx[mid] <= sample) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found in 0 until count - 1) pos[found] else null
    }

    /** Byte offset [sample] would land at, extrapolating the bytes/sample ratio so far. */
    private fun estimate(sample: Long): Long {
        if (count == 0) return 0L
        val lastIdx = idx[count - 1]
        if (lastIdx <= 0) return 0L
        return sample * pos[count - 1] / lastIdx
    }

    /**
     * Finds the last block starting at or before [sample] by bisecting the byte range.
     *
     * [resync] is monotonic — later offsets give later blocks — so it works as the oracle
     * for a plain bisection. Invariants: [lo] is a boundary whose index is at or before
     * [sample], and every boundary at or after [hi] starts later than that. Each step
     * strictly narrows the range, so this lands in ~log2(file size) header scans, which
     * over SMB is the difference between a handful of round trips and one per block.
     *
     * The ratio measured so far seeds the bracket to skip most of those steps.
     */
    private fun bisect(access: SacdRandomAccess, fileLength: Long, sample: Long): Long {
        var lo = 0L
        var hi = if (fileLength > 0) fileLength else Long.MAX_VALUE

        resync(access, estimate(sample) * ESTIMATE_BIAS_NUM / ESTIMATE_BIAS_DEN, fileLength)
            ?.let { near ->
                if (readBlockIndex(access, near) in 0..sample) lo = near
                else hi = near
            }

        var steps = 0
        while (hi - lo > 1 && steps < MAX_BISECT_STEPS) {
            val mid = lo + (hi - lo) / 2
            val boundary = resync(access, mid, fileLength) ?: break
            when {
                // resync ran past the bracket, which means [mid, hi) holds no boundary at
                // or before the target either, so the target's block is below [mid].
                boundary >= hi -> hi = mid
                // A boundary at or before the target: the answer is this one or later.
                readBlockIndex(access, boundary) in 0..sample -> lo = boundary
                // First boundary at or after [mid] is already past the target.
                else -> hi = boundary
            }
            steps++
        }
        return lo
    }

    /**
     * First real block boundary at or after [from], or null if none within [RESYNC_LIMIT].
     *
     * A byte offset from an estimate is almost never block-aligned, so the bytes around it
     * are scanned for the "wvpk" magic. A candidate is accepted only once the block that
     * would follow it also looks like a header with a non-decreasing index, which "wvpk"
     * occurring inside payload data will not satisfy.
     */
    private fun resync(access: SacdRandomAccess, from: Long, fileLength: Long): Long? {
        var at = maxOf(from, 0L)
        val end = if (fileLength > 0) minOf(at + RESYNC_LIMIT, fileLength) else at + RESYNC_LIMIT
        val window = ByteArray(RESYNC_CHUNK)
        val cur = ByteArray(WavPackFormat.HEADER_SIZE)
        val next = ByteArray(WavPackFormat.HEADER_SIZE)
        while (at < end) {
            val want = minOf(window.size.toLong(), end - at).toInt()
            if (access.read(at, window, want) < 4) return null
            for (i in 0..want - 4) {
                if (window[i] != 'w'.code.toByte() || window[i + 1] != 'v'.code.toByte() ||
                    window[i + 2] != 'p'.code.toByte() || window[i + 3] != 'k'.code.toByte()
                ) continue
                val candidate = at + i
                if (access.read(candidate, cur, WavPackFormat.HEADER_SIZE) < WavPackFormat.HEADER_SIZE) {
                    return null
                }
                if (!isBlockHeader(cur)) continue
                val after = candidate + WavPackFormat.totalBlockSize(cur)
                if (fileLength > 0 && after >= fileLength) return candidate   // final block
                if (access.read(after, next, WavPackFormat.HEADER_SIZE) < WavPackFormat.HEADER_SIZE) {
                    return candidate   // ran into the end: this can only be the last block
                }
                // Otherwise the bytes after a real block are the next header, so a candidate
                // they do not continue is "wvpk" inside payload data. That is also what
                // happens to a genuine last block with an APE tag after it, so the tail of
                // such a file is only ever found late: harmless, since a boundary a few
                // blocks early just costs a few blocks of decode-and-discard.
                if (!isBlockHeader(next)) continue
                if (WavPackFormat.blockIndex(next) >= WavPackFormat.blockIndex(cur)) return candidate
            }
            at += (want - 3)     // 3 bytes of overlap, in case a magic straddles chunks
        }
        return null
    }

    private fun readBlockIndex(access: SacdRandomAccess, at: Long): Long {
        val header = ByteArray(WavPackFormat.HEADER_SIZE)
        if (access.read(at, header, WavPackFormat.HEADER_SIZE) < WavPackFormat.HEADER_SIZE) return -1L
        if (!isBlockHeader(header)) return -1L
        return WavPackFormat.blockIndex(header)
    }

    private fun isBlockHeader(h: ByteArray): Boolean =
        WavPackFormat.isWvpkMagic(h, 0) && WavPackFormat.ckSize(h) >= WavPackFormat.HEADER_SIZE - 8

    private companion object {
        /**
         * How far past an estimate to look for a boundary. Blocks are tens of kilobytes
         * apart, so this only has to span a few of them.
         */
        const val RESYNC_LIMIT = 4L * 1024 * 1024
        const val RESYNC_CHUNK = 16 * 1024

        /** log2 of any plausible file size, with room to spare. */
        const val MAX_BISECT_STEPS = 64

        /** Aims the seed bracket below the target; overshooting needs a step to undo. */
        const val ESTIMATE_BIAS_NUM = 90L
        const val ESTIMATE_BIAS_DEN = 100L
    }
}

/**
 * Sequential [InputStream] over a [SacdRandomAccess] that also records the WavPack block
 * headers it passes. The reference decoder loads one whole block at a time and never skips,
 * so the block boundaries can be tracked with a small state machine over the bytes
 * delivered from the source.
 */
internal class WavPackBlockStream(
    private val access: SacdRandomAccess,
    startPos: Long,
    private val fileLength: Long,
    private val index: BlockIndexTracker,
) : InputStream() {

    private val buf = ByteArray(READ_BUFFER)
    private var bufPos = 0
    private var bufLen = 0

    /** Absolute offset of the next byte to be fetched from the source. */
    private var filePos = startPos

    /** Absolute offset at which the current block's header began. */
    private var blockStart = startPos
    private var expectingHeader = true
    private var headerFill = 0
    private val header = ByteArray(WavPackFormat.HEADER_SIZE)
    private var payloadLeft = 0L

    /** Cleared if the bytes are not block-aligned, so a bad stream stops being indexed. */
    private var tracking = true

    /** Told the size of each block header as it goes past, so unpacks can stay block-aligned. */
    var onBlockHeader: (Long) -> Unit = {}

    override fun read(): Int {
        if (!ensure()) return -1
        return buf[bufPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensure()) return -1
        val n = minOf(len, bufLen - bufPos)
        System.arraycopy(buf, bufPos, b, off, n)
        bufPos += n
        return n
    }

    override fun available(): Int = bufLen - bufPos

    private fun ensure(): Boolean {
        if (bufPos < bufLen) return true
        if (fileLength >= 0 && filePos >= fileLength) return false
        val want = if (fileLength >= 0) minOf(buf.size.toLong(), fileLength - filePos).toInt() else buf.size
        if (want <= 0) return false
        val n = access.read(filePos, buf, want)
        if (n <= 0) return false
        track(filePos, n)
        filePos += n
        bufPos = 0
        bufLen = n
        return true
    }

    /** Notes the block structure of `buf[0, n)`, which starts at absolute offset [from]. */
    private fun track(from: Long, n: Int) {
        if (!tracking) return
        var i = 0
        var at = from
        while (i < n) {
            if (expectingHeader) {
                val take = minOf(WavPackFormat.HEADER_SIZE - headerFill, n - i)
                System.arraycopy(buf, i, header, headerFill, take)
                headerFill += take
                i += take
                at += take
                if (headerFill == WavPackFormat.HEADER_SIZE) {
                    headerFill = 0
                    if (WavPackFormat.isWvpkMagic(header, 0)) {
                        val blockSamples = WavPackFormat.blockSamples(header)
                        index.record(
                            WavPackFormat.blockIndex(header),
                            blockStart,
                            blockSamples,
                            WavPackFormat.flags(header)
                        )
                        onBlockHeader(blockSamples)
                        payloadLeft = WavPackFormat.totalBlockSize(header) - WavPackFormat.HEADER_SIZE
                        if (payloadLeft <= 0L) {
                            blockStart = at
                        } else {
                            expectingHeader = false
                        }
                    } else {
                        tracking = false
                    }
                }
            } else {
                val take = minOf(payloadLeft, (n - i).toLong()).toInt()
                i += take
                at += take
                payloadLeft -= take
                if (payloadLeft == 0L) {
                    blockStart = at
                    expectingHeader = true
                }
            }
        }
    }

    private companion object {
        const val READ_BUFFER = 1 shl 16
    }
}
