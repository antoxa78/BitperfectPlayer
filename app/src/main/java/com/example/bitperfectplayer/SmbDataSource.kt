package com.example.bitperfectplayer

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Reads `smb://` media for ExoPlayer.
 *
 * ExoPlayer re-opens a data source for every seek inside a file — and a FLAC file
 * without a SEEKTABLE is seeked by *binary search*, so resuming at a saved position
 * means dozens of open → read a little → close cycles in a row. Each fresh open used
 * to cost several SMB round trips (create the file handle, query its size, close it
 * again), which is what made resuming FLAC from a share slow. The open handle and the
 * file size are now kept for a few seconds after close() ([SmbHandleCache]); a re-open
 * of the same file reuses them, and only moves the read position (a local operation).
 *
 * Reads are also buffered: Media3's FLAC seeker peeks **one byte at a time** while
 * scanning for a frame header, and an unbuffered 1-byte read is a full SMB round trip
 * (hundreds of ms on Wi-Fi). A 256 KiB read-ahead turns a whole scan into one request,
 * so CUE track starts (and any seek in a FLAC without a seek table) no longer crawl.
 */
@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private val lock = Any()
    private var handle: SmbHandleCache.Handle? = null
    private var uri: Uri? = null
    private var bytesRemaining = 0L
    private var opened = false

    /** Lazily allocated: most data sources never read more than a few KiB. */
    private var readAhead: ByteArray? = null
    private var readAheadPos = 0
    private var readAheadLen = 0

    override fun open(dataSpec: DataSpec): Long = synchronized(lock) {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val key = dataSpec.uri.toString()
        val startedAt = SystemClock.elapsedRealtime()

        val h = try {
            SmbHandleCache.take(key) ?: run {
                val file = SmbFile(key, SmbContext.getContextForUri(key))
                val raf = SmbRandomAccessFile(file, "r")
                SmbHandleCache.Handle(key, raf, raf.length(), reused = false)
            }
        } catch (e: Exception) {
            throw IOException(e)
        }
        handle = h
        try {
            h.raf.seek(dataSpec.position)
        } catch (e: Exception) {
            // A cached handle the server has since dropped: start over with a fresh one.
            if (!h.reused) throw IOException(e)
            SmbHandleCache.closeQuietly(h)
            handle = null
            val fresh = try {
                val file = SmbFile(key, SmbContext.getContextForUri(key))
                val raf = SmbRandomAccessFile(file, "r")
                SmbHandleCache.Handle(key, raf, raf.length(), reused = false)
                    .also { it.raf.seek(dataSpec.position) }
            } catch (e2: Exception) {
                throw IOException(e2)
            }
            handle = fresh
        }
        readAheadPos = 0
        readAheadLen = 0

        val fileLength = handle?.length ?: 0L
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            // Clamp: a position past EOF must not produce a negative remaining count
            // (and -1 would read as "unbounded").
            fileLength > 0                             -> (fileLength - dataSpec.position).coerceAtLeast(0L)
            else                                       -> C.LENGTH_UNSET.toLong()
        }
        Log.d(TAG, "open ${if (handle?.reused == true) "reused" else "new"} handle " +
            "pos=${dataSpec.position} in ${SystemClock.elapsedRealtime() - startedAt}ms " +
            "— ${Uri.decode(dataSpec.uri.lastPathSegment ?: "")}")

        opened = true
        transferStarted(dataSpec)
        return@synchronized bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        if (length == 0) return@synchronized 0
        if (readAheadPos >= readAheadLen && bytesRemaining == 0L) {
            return@synchronized C.RESULT_END_OF_INPUT
        }

        if (readAheadPos >= readAheadLen) {
            val isUnbounded = bytesRemaining == C.LENGTH_UNSET.toLong()
            val want = if (isUnbounded) READ_AHEAD_BYTES
                       else minOf(bytesRemaining, READ_AHEAD_BYTES.toLong()).toInt()
            if (want <= 0) return@synchronized C.RESULT_END_OF_INPUT

            val buf = readAhead ?: ByteArray(READ_AHEAD_BYTES).also { readAhead = it }
            val read = try {
                handle?.raf?.read(buf, 0, want) ?: -1
            } catch (e: IOException) {
                // Do not hand a handle that just failed back to the cache.
                handle?.broken = true
                throw IOException(e)
            }
            if (read <= 0) return@synchronized C.RESULT_END_OF_INPUT

            readAheadPos = 0
            readAheadLen = read
            // Account for everything fetched now; the caller consumes it from the
            // buffer over the following reads, which must not decrement it again.
            if (!isUnbounded) bytesRemaining -= read
        }

        val bytesRead = minOf(length, readAheadLen - readAheadPos)
        System.arraycopy(readAhead!!, readAheadPos, buffer, offset, bytesRead)
        readAheadPos += bytesRead

        bytesTransferred(bytesRead)
        return@synchronized bytesRead
    }

    override fun getUri(): Uri? = synchronized(lock) { uri }

    override fun close() = synchronized(lock) {
        uri = null
        readAheadPos = 0
        readAheadLen = 0
        val h = handle
        handle = null
        try {
            // Keep it for the next open of the same file (the next seek step);
            // the cache closes it if nothing takes it within a few seconds.
            if (h != null) {
                if (h.broken) SmbHandleCache.closeQuietly(h) else SmbHandleCache.put(h)
            }
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        const val TAG = "SmbDataSource"

        /**
         * Larger than a FLAC frame (36 KiB max) so one fill covers a whole
         * header scan, and a multiple of Media3's 64 KiB sequential read.
         */
        const val READ_AHEAD_BYTES = 256 * 1024
    }
}

/**
 * Open SMB file handles parked between a data source's close() and its next open()
 * of the same file. One entry per file; an entry not taken back within
 * [IDLE_CLOSE_MS] is closed. A handle is only ever used by one data source at a time
 * (take() removes it), so no two readers share a file position.
 */
object SmbHandleCache {

    private const val TAG = "SmbHandleCache"
    /** Long enough to span a seek sequence; short enough not to hold server handles. */
    private const val IDLE_CLOSE_MS = 5_000L

    class Handle(
        val key: String,
        val raf: SmbRandomAccessFile,
        val length: Long,
        var reused: Boolean,
    ) {
        @Volatile var broken = false
        var closeTask: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val parked = HashMap<String, Handle>()
    private val closer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "smb-handle-closer").apply { isDaemon = true }
    }

    /** The parked handle for [key], now owned by the caller - or null. */
    fun take(key: String): Handle? = synchronized(lock) {
        parked.remove(key)?.also {
            it.closeTask?.cancel(false)
            it.closeTask = null
            it.reused = true
        }
    }

    /** Parks [h] for reuse; replaces (and closes) any handle already parked for its file. */
    fun put(h: Handle) {
        val displaced: Handle?
        synchronized(lock) {
            displaced = parked.put(h.key, h)
            displaced?.closeTask?.cancel(false)
            h.closeTask = closer.schedule({
                val expired = synchronized(lock) {
                    if (parked[h.key] === h) parked.remove(h.key) else null
                }
                expired?.let { closeQuietly(it) }
            }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
        }
        if (displaced != null && displaced !== h) closeQuietly(displaced)
    }

    fun closeQuietly(h: Handle) {
        try { h.raf.close() } catch (e: Exception) { Log.d(TAG, "close failed: ${e.message}") }
    }
}
