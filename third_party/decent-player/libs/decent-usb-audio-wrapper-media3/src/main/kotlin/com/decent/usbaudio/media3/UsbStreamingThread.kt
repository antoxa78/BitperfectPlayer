package com.decent.usbaudio.media3

import android.util.Log
import com.decent.usbaudio.UsbAudioStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated thread for USB audio streaming, decoupled from ExoPlayer's
 * render thread.
 *
 * Supports two buffer types:
 * - [FloatBuffer]: float PCM from FFmpeg (MP3, AAC, FLAC via float path)
 * - [RawBuffer]: raw integer PCM from libFLAC (zero float, true bit-perfect)
 *
 * @param usbStream The native USB audio stream to write to.
 *                  Must only be accessed from the USB thread.
 */
class UsbStreamingThread(private val usbStream: UsbAudioStream) {

    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L
        /** Recycled PCM arrays kept per type. The producer is throttled at
         *  UsbAudioSink.QUEUE_BACKPRESSURE_THRESHOLD (16) queued buffers, so this
         *  covers the steady state with room to spare. */
        private const val POOL_MAX = 24
    }

    // ── Buffer pool ─────────────────────────────────────────────────
    // Every ExoPlayer buffer used to be copied into a freshly allocated array,
    // i.e. a steady stream of garbage on the audio path. Arrays are now handed
    // back after the (copying) native write and reused. ExoPlayer's buffer size
    // is constant for a given format, so exact-size matches are the norm; an
    // array of another size is simply dropped.
    private val floatPool = ConcurrentLinkedQueue<FloatArray>()
    private val floatPoolCount = AtomicInteger(0)
    private val bytePool = ConcurrentLinkedQueue<ByteArray>()
    private val bytePoolCount = AtomicInteger(0)

    /** A float array of exactly [size] elements, recycled when possible. */
    fun obtainFloatArray(size: Int): FloatArray {
        while (true) {
            val a = floatPool.poll() ?: return FloatArray(size)
            floatPoolCount.decrementAndGet()
            if (a.size == size) return a
        }
    }

    /** A byte array of exactly [size] bytes, recycled when possible. */
    fun obtainByteArray(size: Int): ByteArray {
        while (true) {
            val a = bytePool.poll() ?: return ByteArray(size)
            bytePoolCount.decrementAndGet()
            if (a.size == size) return a
        }
    }

    private fun recycle(a: FloatArray) {
        if (floatPoolCount.incrementAndGet() <= POOL_MAX) floatPool.offer(a)
        else floatPoolCount.decrementAndGet()
    }

    private fun recycle(a: ByteArray) {
        if (bytePoolCount.incrementAndGet() <= POOL_MAX) bytePool.offer(a)
        else bytePoolCount.decrementAndGet()
    }

    /** Sealed class for type-safe audio buffer queueing. */
    private sealed class AudioBuffer {
        class FloatBuffer(val data: FloatArray) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val encoding: Int) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    private var thread: Thread? = null
    private var dropCount = 0

    fun start() {
        running = true
        thread = Thread({
            // Java's MAX_PRIORITY only maps to nice -8; audio threads should run
            // at Android's urgent-audio level (nice -19), which apps may use.
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "Could not raise USB streaming thread priority: ${e.message}")
            }
            Log.i(TAG, "USB streaming thread started")
            while (running) {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }
                val qBefore = audioQueue.size
                when (val buf = audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    is AudioBuffer.FloatBuffer -> {
                        usbStream.write(buf.data)   // native side copies the samples
                        recycle(buf.data)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before write")
                    }
                    is AudioBuffer.RawBuffer -> {
                        usbStream.writeRaw(buf.data, buf.encoding)
                        recycle(buf.data)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before writeRaw")
                    }
                    null -> Log.w(TAG, "Queue EMPTY — poll timeout")
                }
            }
            Log.i(TAG, "USB streaming thread exited")
        }, "UsbStreamingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Enqueue float PCM (FFmpeg path). Non-blocking; on a full queue the
     * buffer is REJECTED (drop-newest) rather than evicting the oldest —
     * evicting reorders playout (fast-forward); rejecting only loses the
     * newest chunk and keeps the queue aligned. Pacing is handled upstream
     * by the backpressure threshold, so this is a rare safety valve.
     */
    fun enqueue(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            recycle(floatBuf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, rejected float buffer #$dropCount")
            }
        }
    }

    /** Enqueue raw integer PCM (libFLAC path). Non-blocking; rejects on full (see [enqueue]). */
    fun enqueueRaw(rawBytes: ByteArray, encoding: Int) {
        val buf = AudioBuffer.RawBuffer(rawBytes, encoding)
        if (!audioQueue.offer(buf)) {
            recycle(rawBytes)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, rejected raw buffer #$dropCount")
            }
        }
    }

    fun pauseStreaming() { paused = true }
    fun resumeStreaming() { paused = false }

    fun hasPendingData(): Boolean = !audioQueue.isEmpty()

    fun queueSize(): Int = audioQueue.size

    fun flush() {
        audioQueue.clear()
    }

    fun stop() {
        running = false
        audioQueue.clear()
        thread?.join(2000)
        thread = null
    }
}
