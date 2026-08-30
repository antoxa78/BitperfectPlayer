package com.example.bitperfectplayer

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Backs the native SACD ISO reader with random block access. The native side
 * calls [read] for 2048-byte LSN reads and [length] for the total image size.
 */
interface SacdRandomAccess {
    /** Reads up to [length] bytes starting at [offset]. Returns fewer bytes at EOF. */
    fun read(offset: Long, length: Int): ByteArray

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
    override fun read(offset: Long, length: Int): ByteArray {
        // jcifs silently re-connects its transport on a fresh call after a
        // dropped TCP session, so a single retry survives transient resets.
        return try {
            readOnce(offset, length)
        } catch (t: Throwable) {
            try {
                readOnce(offset, length)
            } catch (_: Throwable) {
                throw t
            }
        }
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        val handle = open()
        handle.seek(offset)
        while (done < length) {
            val n = handle.read(out, done, length - done)
            if (n < 0) break
            if (n == 0) break
            done += n
        }
        return if (done == length) out else out.copyOf(done)
    }

    @Synchronized
    override fun length(): Long = smbFile.length()

    @Synchronized
    override fun close() {
        raf?.let {
            try {
                it.close()
            } catch (_: IOException) {
            }
        }
        raf = null
    }
}

/** [SacdRandomAccess] backed by a local file. */
class LocalSacdRandomAccess(private val file: java.io.File) : SacdRandomAccess {
    private val raf = RandomAccessFile(file, "r")

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        raf.seek(offset)
        while (done < length) {
            val n = raf.read(out, done, length - done)
            if (n < 0) break
            if (n == 0) break
            done += n
        }
        return if (done == length) out else out.copyOf(done)
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