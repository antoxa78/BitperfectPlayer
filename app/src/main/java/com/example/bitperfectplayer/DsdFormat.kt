package com.example.bitperfectplayer

import java.io.IOException

/**
 * DSF / DSDIFF (DFF) container parsing and DSD helpers, free of Android and
 * Media3 dependencies so the byte-exact logic can be unit-tested on its own.
 * [DsdFileExtractor] wires this into Media3.
 *
 * Normalized form used downstream: channel-interleaved DSD bytes (ch0, ch1,
 * ... per byte time slot), each byte MSB-first (earliest DSD bit in bit 7) —
 * DFF's native layout, and what both DoP packing and the native DSD->PCM
 * converter expect.
 */
class DsdFormatException(message: String) : IOException(message)

enum class DsdContainer { DSF, DFF }

data class DsdStreamInfo(
    val container: DsdContainer,
    val channels: Int,
    /** DSD sample rate per channel in Hz (2822400 = DSD64). */
    val dsdRate: Int,
    /** Absolute byte offset of the first audio byte in the file. */
    val dataStart: Long,
    /** Audio bytes per channel that carry real samples (excludes DSF block padding). */
    val validBytesPerChannel: Long,
    /** DSF: bytes per channel block (usually 4096); DFF: 0 (byte-interleaved). */
    val blockSize: Int,
    /** DSF with bits-per-sample 1 stores each byte LSB-first. */
    val lsbFirst: Boolean,
) {
    val durationUs: Long get() = timeUsForBytesPerChannel(validBytesPerChannel)

    fun timeUsForBytesPerChannel(bytesPerChannel: Long): Long =
        bytesPerChannel * 8L * 1_000_000L / dsdRate

    /** Bytes of file data holding [bytesPerChannel] bytes of every channel. */
    fun dataOffsetForBytesPerChannel(bytesPerChannel: Long): Long = bytesPerChannel * channels

    /**
     * Nearest seekable point at or before [timeUs]: returns the per-channel byte
     * index. DSF seeks to block-group boundaries (channel-planar blocks);
     * DFF to an even byte index (keeps DoP 16-bit pairs aligned).
     */
    fun seekBytesPerChannel(timeUs: Long): Long {
        val target = (timeUs.coerceAtLeast(0L) * dsdRate / 8L / 1_000_000L)
            .coerceAtMost(validBytesPerChannel)
        return if (container == DsdContainer.DSF) (target / blockSize) * blockSize
               else target and 1L.inv()
    }

    /** File position for a per-channel byte index returned by [seekBytesPerChannel]. */
    fun filePositionForBytesPerChannel(bytesPerChannel: Long): Long =
        dataStart + dataOffsetForBytesPerChannel(bytesPerChannel)

    /** Inverse of [filePositionForBytesPerChannel]. */
    fun bytesPerChannelAt(filePosition: Long): Long =
        ((filePosition - dataStart) / channels).coerceIn(0L, validBytesPerChannel)
}

/** Minimal sequential reader used for header parsing. */
interface DsdByteSource {
    /** Absolute position of the next byte. */
    val position: Long
    @Throws(IOException::class) fun readFully(buf: ByteArray, off: Int, len: Int)
    @Throws(IOException::class) fun skipFully(len: Long)
}

object DsdParser {
    /** True if [head] (at least 16 bytes of the file start) looks like DSF or DFF. */
    fun sniff(head: ByteArray): Boolean {
        if (head.size < 16) return false
        if (id(head, 0) == "DSD ") return true
        return id(head, 0) == "FRM8" && id(head, 12) == "DSD "
    }

    @Throws(IOException::class)
    fun parse(src: DsdByteSource): DsdStreamInfo {
        val magic = ByteArray(4)
        src.readFully(magic, 0, 4)
        return when (id(magic, 0)) {
            "DSD " -> parseDsf(src)
            "FRM8" -> parseDff(src)
            else -> throw DsdFormatException("Not a DSF/DFF file")
        }
    }

    // ── DSF (Sony, little-endian) ─────────────────────────────────────────

    private fun parseDsf(src: DsdByteSource): DsdStreamInfo {
        // "DSD " chunk: size(8) totalFileSize(8) metadataPointer(8) — magic already read.
        val dsdChunk = read(src, 24)
        val dsdChunkSize = le64(dsdChunk, 0)
        if (dsdChunkSize < 28) throw DsdFormatException("DSF: bad DSD chunk size $dsdChunkSize")
        src.skipFully(dsdChunkSize - 28)

        val fmtHead = read(src, 12)
        if (id(fmtHead, 0) != "fmt ") throw DsdFormatException("DSF: missing fmt chunk")
        val fmtSize = le64(fmtHead, 4)
        if (fmtSize < 52) throw DsdFormatException("DSF: bad fmt chunk size $fmtSize")
        val fmt = read(src, 40)
        val formatId = le32(fmt, 4)
        val channels = le32(fmt, 12)
        val rate = le32(fmt, 16)
        val bitsPerSample = le32(fmt, 20)
        val sampleCount = le64(fmt, 24)
        val blockSize = le32(fmt, 32)
        src.skipFully(fmtSize - 52)
        if (formatId != 0) throw DsdFormatException("DSF: unsupported format id $formatId")
        if (bitsPerSample != 1 && bitsPerSample != 8) {
            throw DsdFormatException("DSF: unsupported bits per sample $bitsPerSample")
        }
        if (blockSize <= 0 || blockSize % 2 != 0) throw DsdFormatException("DSF: bad block size $blockSize")

        // Skip any chunk that isn't "data" (none are defined, but be lenient).
        while (true) {
            val head = read(src, 12)
            val size = le64(head, 4)
            if (id(head, 0) == "data") {
                val dataStart = src.position
                val dataBytes = size - 12
                val perChannelCapacity = dataBytes / channels.coerceAtLeast(1)
                val valid = minOf((sampleCount + 7) / 8, perChannelCapacity)
                return validate(
                    DsdStreamInfo(DsdContainer.DSF, channels, rate, dataStart, valid, blockSize,
                        lsbFirst = bitsPerSample == 1)
                )
            }
            if (size < 12) throw DsdFormatException("DSF: bad chunk size $size")
            src.skipFully(size - 12)
        }
    }

    // ── DSDIFF / DFF (Philips, big-endian) ───────────────────────────────

    private fun parseDff(src: DsdByteSource): DsdStreamInfo {
        val frm = read(src, 12) // size(8) + form type(4)
        if (id(frm, 8) != "DSD ") throw DsdFormatException("DFF: form type is not 'DSD '")
        var rate = 0
        var channels = 0
        var compression = "DSD "
        while (true) {
            val head = read(src, 12)
            val ckId = id(head, 0)
            val ckSize = be64(head, 4)
            val padded = ckSize + (ckSize and 1L)
            when (ckId) {
                "PROP" -> {
                    val propType = read(src, 4)
                    if (id(propType, 0) != "SND ") { src.skipFully(padded - 4); continue }
                    var remaining = ckSize - 4
                    while (remaining >= 12) {
                        val sub = read(src, 12)
                        val subId = id(sub, 0)
                        val subSize = be64(sub, 4)
                        val subPadded = subSize + (subSize and 1L)
                        when (subId) {
                            "FS  " -> {
                                rate = be32(read(src, 4), 0)
                                src.skipFully(subPadded - 4)
                            }
                            "CHNL" -> {
                                val b = read(src, 2)
                                channels = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                                src.skipFully(subPadded - 2)
                            }
                            "CMPR" -> {
                                compression = id(read(src, 4), 0)
                                src.skipFully(subPadded - 4)
                            }
                            else -> src.skipFully(subPadded)
                        }
                        remaining -= 12 + subPadded
                    }
                    if (remaining > 0) src.skipFully(remaining)
                    if (ckSize and 1L == 1L) src.skipFully(1)
                }
                "DSD " -> {
                    if (compression != "DSD ") {
                        throw DsdFormatException("DFF: compression '$compression' is not supported")
                    }
                    if (channels <= 0) throw DsdFormatException("DFF: no channel count")
                    return validate(
                        DsdStreamInfo(DsdContainer.DFF, channels, rate, src.position,
                            ckSize / channels, blockSize = 0, lsbFirst = false)
                    )
                }
                "DST " -> throw DsdFormatException("DFF: DST-compressed files are not supported")
                else -> src.skipFully(padded)
            }
        }
    }

    private fun validate(info: DsdStreamInfo): DsdStreamInfo {
        if (info.channels !in 1..2) {
            throw DsdFormatException("DSD: ${info.channels}-channel files are not supported (stereo/mono only)")
        }
        if (info.dsdRate <= 0 || info.dsdRate % 44100 != 0 && info.dsdRate % 48000 != 0) {
            throw DsdFormatException("DSD: unsupported rate ${info.dsdRate}")
        }
        if (info.validBytesPerChannel <= 0) throw DsdFormatException("DSD: no audio data")
        return info
    }

    private fun read(src: DsdByteSource, n: Int): ByteArray = ByteArray(n).also { src.readFully(it, 0, n) }
    private fun id(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, o: Int): Long =
        (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be64(b: ByteArray, o: Int): Long =
        (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)
}

object DsdPacking {
    /** Bit-reversal table (LSB-first <-> MSB-first). */
    private val REVERSE = ByteArray(256) { i ->
        var v = i
        var r = 0
        repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
        r.toByte()
    }

    /**
     * DSF block group (channel-planar: [channels] blocks of [blockSize] bytes)
     * -> [out] as channel-interleaved MSB-first bytes; only the first
     * [validPerChannel] bytes of each block are used.
     */
    fun dsfGroupToInterleaved(
        group: ByteArray, channels: Int, blockSize: Int, validPerChannel: Int,
        lsbFirst: Boolean, out: ByteArray
    ) {
        for (c in 0 until channels) {
            val base = c * blockSize
            var o = c
            for (i in 0 until validPerChannel) {
                val b = group[base + i]
                out[o] = if (lsbFirst) REVERSE[b.toInt() and 0xFF] else b
                o += channels
            }
        }
    }

    /**
     * DoP (DSD over PCM, v1.1): packs channel-interleaved MSB-first DSD bytes
     * into 24-bit little-endian PCM samples. Each sample carries 16 DSD bits —
     * the earlier byte in bits 15..8, the later in 7..0 — under a marker byte
     * that alternates 0x05 / 0xFA from one frame to the next (all channels of a
     * frame share it). [bytesPerChannel] must be even.
     *
     * @param markerPhase 0 if the first frame gets 0x05, 1 for 0xFA.
     * @return the marker phase for the frame after the last one written.
     */
    fun packDop(inter: ByteArray, channels: Int, bytesPerChannel: Int, out: ByteArray, markerPhase: Int): Int {
        val frames = bytesPerChannel / 2
        var o = 0
        var phase = markerPhase and 1
        for (f in 0 until frames) {
            val marker = if (phase == 0) 0x05.toByte() else 0xFA.toByte()
            val i0 = (2 * f) * channels
            val i1 = i0 + channels
            for (c in 0 until channels) {
                out[o] = inter[i1 + c]      // bits 7..0: later DSD byte
                out[o + 1] = inter[i0 + c]  // bits 15..8: earlier DSD byte
                out[o + 2] = marker         // bits 23..16: DoP marker
                o += 3
            }
            phase = phase xor 1
        }
        return phase
    }

    /** DoP carries 16 DSD bits per PCM sample. */
    fun dopRate(dsdRate: Int): Int = dsdRate / 16

    /** "DSD64", "DSD128", ... (either rate family). */
    fun dsdName(dsdRate: Int): String {
        val base = if (dsdRate % 44100 == 0) 44100 else 48000
        return "DSD${dsdRate / base}"
    }
}
