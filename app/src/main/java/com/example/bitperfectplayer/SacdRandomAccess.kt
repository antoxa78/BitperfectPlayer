package com.example.bitperfectplayer

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Backs the native SACD ISO reader with random block access. The native side
 * calls [read] for 2048-byte LSN reads (up to 1 MB at a time) into one
 * reusable buffer per reader, and [length] for the total image size.
 */
interface SacdRandomAccess {
    /**
     * Reads up to [length] bytes starting at [offset] into [buffer] (from
     * index 0). Returns the number of bytes read — fewer at end of file, 0
     * past it. Throws on I/O errors (reported to the decoder as retryable).
     */
    fun read(offset: Long, buffer: ByteArray, length: Int): Int

    /** Total image size in bytes. */
    fun length(): Long

    /** Releases the underlying file handle/socket. Safe to call more than once. */
    fun close()
}

/** [SacdRandomAccess] backed by an SMB share (jcifs). */
class SmbSacdRandomAccess(private val smbFile: SmbFile) : SacdRandomAccess {
    /**
     * Opened lazily on first [read] rather than in the constructor: media source
     * creation runs on the main thread and a blocking SMB OPEN there throws
     * NetworkOnMainThreadException (which rolls back addMediaItems transactions).
     * The first read always happens on the extractor/loader background thread.
     */
    @Volatile
    private var raf: SmbRandomAccessFile? = null

    private fun open(): SmbRandomAccessFile {
        raf?.let { return it }
        synchronized(this) {
            raf?.let { return it }
            val opened = SmbRandomAccessFile(smbFile, "r")
            raf = opened
            return opened
        }
    }

    @Synchronized
    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        return try {
            readOnce(offset, buffer, length)
        } catch (e: Exception) {
            // A dropped TCP session can leave this file handle unusable even
            // though jcifs reconnects the transport (jcifs-ng also throws
            // unchecked RuntimeCIFSException): reopen it and retry once. A
            // second failure propagates; the decoder reports it as a
            // retryable read error and media3 retries the load later.
            closeHandle()
            try {
                readOnce(offset, buffer, length)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private fun readOnce(offset: Long, buffer: ByteArray, length: Int): Int {
        val want = minOf(length, buffer.size)
        var done = 0
        val handle = open()
        handle.seek(offset)
        while (done < want) {
            val n = handle.read(buffer, done, want - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    @Synchronized
    override fun length(): Long = smbFile.length()

    private fun closeHandle() {
        raf?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        raf = null
    }

    @Synchronized
    override fun close() = closeHandle()
}

/** [SacdRandomAccess] backed by a local file. */
class LocalSacdRandomAccess(private val file: java.io.File) : SacdRandomAccess {
    private val raf = RandomAccessFile(file, "r")

    @Synchronized
    override fun read(offset: Long, buffer: ByteArray, length: Int): Int {
        val want = minOf(length, buffer.size)
        var done = 0
        raf.seek(offset)
        while (done < want) {
            val n = raf.read(buffer, done, want - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    @Synchronized
    override fun length(): Long = file.length()

    @Synchronized
    override fun close() {
        try {
            raf.close()
        } catch (_: IOException) {
        }
    }
}
