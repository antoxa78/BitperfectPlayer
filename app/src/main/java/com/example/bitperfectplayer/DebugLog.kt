package com.example.bitperfectplayer

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app log capture for devices where adb cannot be used at the same time as
 * the USB DAC.
 *
 * On several Android TV boxes (Xiaomi TV Box S 2nd/3rd Gen, Mi Box 3, …) the
 * single USB-A port is wired to an OTG-capable PHY: turning on **USB
 * debugging** flips that port from host mode to device/gadget mode so `adbd`
 * can talk to a PC, and the DAC disappears from the bus. There is no software
 * fix — the port can only be one of the two at a time — so the log has to
 * leave the box some other way.
 *
 * This object reads the app's own log entries straight out of logcat (an app
 * may always read its own UID's lines without READ_LOGS), keeps them in a
 * bounded in-memory ring buffer, mirrors them to a rotating file in the app's
 * external files dir, and lets [DebugLogServer] serve them over the LAN.
 *
 * Secrets are scrubbed on the way in: MPD passwords and SMB URIs carrying
 * `user:pass@` would otherwise be readable by anyone on the network.
 */
object DebugLog {

    private const val TAG = "DebugLog"

    /** Lines kept in memory — a few thousand lines covers many minutes of playback. */
    const val MAX_LINES = 4000

    const val FILE_NAME = "debug.log"
    private const val FILE_MAX_BYTES = 2L * 1024 * 1024
    private const val FILE_FLUSH_EVERY = 20

    /** `logcat -T` tail, so the log already has the moments before capture started. */
    private const val LOGCAT_TAIL_LINES = 300

    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /** Settings → Debug Log → "Debug Log: On/Off" (capture itself, not just sharing). */
    private const val PREFS = "AppSettings"
    private const val KEY_CAPTURE = "debug_log_capture"

    @Volatile private var installed = false
    @Volatile private var hooksInstalled = false
    /** True while the logcat reader runs; cleared to stop it. */
    @Volatile private var capturing = false
    /** First reader of this process takes a logcat tail; a restart must not re-add it. */
    @Volatile private var tailTaken = false
    @Volatile private var readerThread: Thread? = null
    @Volatile private var readerProcess: Process? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var state = "not started"

    // File sink state, guarded by [lock].
    private var fileWriter: BufferedWriter? = null
    private var fileSinceFlush = 0
    private var fileBytes = 0L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Credential scrubbers. Applied to every captured line, because logcat is
     * a plain-text channel that ends up on the network and on disk:
     *  - `smb://user:pass@host/share` → `smb://user:***@host/share`
     *  - the MPD command echo `MPD <- password <secret>` → `MPD <- password ***`
     *  - assignment-style leaks `password=…` / `passwd: …` / `pwd=…` → `***`
     *
     * Deliberately not a generic "the word password followed by a word" rule:
     * that mangles ordinary diagnostics such as `(password set)`, which says
     * nothing secret and loses its closing bracket in the process.
     */
    private val scrubbers = listOf(
        Regex("""(?i)([a-z][a-z0-9+.\-]*://[^/\s:@]*):[^@\s/]*@""") to "\$1:***@",
        // Mask the whole argument, not just its first word: a password may be quoted
        // and contain spaces, and the old \S+ left the rest of it in the log.
        Regex("""(?i)(MPD <- "?password"?\s+)(.*)""") to "\$1***",
        Regex("""(?i)(password|passwd|pwd)(\s*[:=]\s*)\S+""") to "\$1\$2***"
    )

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Idempotent; safe to call from every entry point. Starts the logcat reader
     * only when capture was explicitly enabled in Settings (off by default).
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        synchronized(lock) {
            if (installed) return
            installed = true
            appContext = ctx
        }
        if (isEnabled(ctx)) {
            startCapture()
        } else {
            state = "off"
        }
    }

    /**
     * The persisted on/off switch. Off by default: the logcat reader, the
     * rotating debug.log file and the extra disk writes are diagnostics, not
     * something every install should run unless the user asks for them. A user
     * who explicitly enabled capture keeps it enabled across updates.
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAPTURE, false)

    /**
     * Switches capture on or off now and for later app starts. Off stops the
     * logcat reader and closes the file; what was already captured stays
     * readable (and clearable) until it is cleared.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val ctx = context.applicationContext
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CAPTURE, enabled).apply()
        appContext = ctx
        if (enabled) startCapture() else stopCapture()
    }

    fun isCapturing(): Boolean = capturing

    private fun startCapture() {
        synchronized(lock) {
            if (capturing) return
            capturing = true
        }
        installHooksOnce()
        readerThread = Thread({ readLoop() }, "debuglog-reader").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Debug log capture started (pid ${android.os.Process.myPid()})")
    }

    private fun stopCapture() {
        val process: Process?
        synchronized(lock) {
            if (!capturing) return
            capturing = false
            process = readerProcess
            readerProcess = null
        }
        // Ends the reader's blocking readLine(); the loop then sees capturing=false.
        try { process?.destroy() } catch (_: Exception) {}
        readerThread?.interrupt()
        readerThread = null
        synchronized(lock) { closeWriterLocked() }
        state = "off"
        Log.i(TAG, "Debug log capture stopped")
    }

    private fun installHooksOnce() {
        synchronized(lock) {
            if (hooksInstalled) return
            hooksInstalled = true
        }
        installCrashHandler()
        installShutdownFlush()
    }

    /** One-line status for the Settings card / HTTP `/status`. */
    fun status(): String = state

    fun lineCount(): Int = synchronized(lock) { lines.size }

    /** Copy of the ring buffer, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    /** The last [maxLines] lines as plain text. */
    fun text(maxLines: Int = MAX_LINES): String {
        val all = snapshot()
        val from = (all.size - maxLines).coerceAtLeast(0)
        return all.subList(from, all.size).joinToString("\n")
    }

    /**
     * Tail of the persisted file — i.e. including sessions that are no longer in
     * the in-memory ring buffer. This is what makes a crash or an Exit-path
     * sequence readable after the process that wrote it is gone, without adb.
     */
    fun fileText(context: Context, maxLines: Int = MAX_LINES): String {
        val file = logFile(context) ?: return "log file unavailable"
        if (!file.exists()) return "no log file yet"
        return try {
            val lines = file.readLines()
            val from = (lines.size - maxLines).coerceAtLeast(0)
            lines.subList(from, lines.size).joinToString("\n")
        } catch (t: Throwable) {
            "log file read failed: $t"
        }
    }

    /** Empties the ring buffer and deletes the on-disk copy. */
    fun clear() {
        synchronized(lock) {
            lines.clear()
            closeWriterLocked()
            fileBytes = 0
            try { logFileLocked()?.delete() } catch (_: Exception) {}
        }
    }

    /** The on-disk mirror, or null when external storage is unavailable. */
    fun logFile(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        while (capturing) {
            var process: Process? = null
            try {
                // The tail brings in the moments before capture started — but only
                // once per process, or switching capture off and on again would
                // re-add lines that are already in the buffer.
                val tail = if (tailTaken) 1 else LOGCAT_TAIL_LINES
                tailTaken = true
                val started = ProcessBuilder(
                    "logcat", "-v", "threadtime",
                    "-T", tail.toString(),
                    "--pid=${android.os.Process.myPid()}"
                ).redirectErrorStream(true).start()
                process = started
                synchronized(lock) {
                    if (!capturing) { started.destroy(); return }
                    readerProcess = started
                }
                state = "capturing (pid ${android.os.Process.myPid()})"
                synthetic("reader started")
                val reader = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8))
                while (capturing) {
                    val line = reader.readLine() ?: break
                    addLine(line)
                }
                if (!capturing) return
                synthetic("logcat exited")
            } catch (t: Throwable) {
                if (!capturing) return
                synthetic("logcat reader failed: $t")
                state = "reader error: ${t.message}"
            } finally {
                try { process?.destroy() } catch (_: Exception) {}
            }
            if (!capturing) return
            state = "reader restarting"
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Flush the file mirror on process death — after draining what logd still holds.
     *
     * The lines that matter most are also the last ones written: the exit path's
     * DAC hand-back verdict, its wait duration and the final audio-device
     * snapshot. Two things would otherwise lose them:
     *  - [DebugLog] only flushes every [FILE_FLUSH_EVERY] lines, and
     *  - PlaybackService leaves via System.exit(0), which runs shutdown hooks but
     *    no further app code, while the logcat reader thread is a daemon that is
     *    normally still a few lines behind.
     * So run `logcat -d` for this pid here and append whatever it still has before
     * closing the file.
     */
    private fun installShutdownFlush() {
        try {
            Runtime.getRuntime().addShutdownHook(Thread({
                if (!capturing) return@Thread
                try {
                    val process = ProcessBuilder(
                        "logcat", "-d", "-v", "threadtime",
                        "--pid=${android.os.Process.myPid()}"
                    ).redirectErrorStream(true).start()
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                        .forEachLine { addLine(it) }
                    process.destroy()
                } catch (_: Throwable) {}
                synchronized(lock) { closeWriterLocked() }
            }, "debuglog-flush"))
        } catch (_: Throwable) {}
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (capturing) try {
                synthetic("FATAL in ${thread.name}: $throwable")
                throwable.stackTraceToString().lineSequence().forEach { addLine(it) }
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** A line emitted by the app itself, formatted to look like `logcat -v threadtime`. */
    private fun synthetic(message: String) {
        addLine("${stamp.format(Date())} ${android.os.Process.myPid()} $TAG: $message")
    }

    private fun addLine(raw: String) {
        val line = scrub(raw)
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            writeToFileLocked(line)
        }
    }

    /** Visible for tests: [DebugLogScrubTest]. */
    internal fun scrub(raw: String): String {
        var out = raw
        for ((regex, replacement) in scrubbers) {
            if (out.contains(regex)) out = regex.replace(out, replacement)
        }
        return out
    }

    // ── File mirror (all calls hold [lock]) ─────────────────────────────────

    private fun writeToFileLocked(line: String) {
        try {
            if (fileBytes > FILE_MAX_BYTES) rotateLocked()
            var writer = fileWriter
            if (writer == null) {
                val file = logFileLocked() ?: return
                fileBytes = file.length()
                writer = BufferedWriter(FileWriter(file, true))
                fileWriter = writer
            }
            writer.write(line)
            writer.newLine()
            fileBytes += line.length + 1
            if (++fileSinceFlush >= FILE_FLUSH_EVERY) {
                writer.flush()
                fileSinceFlush = 0
            }
        } catch (_: Exception) {
            closeWriterLocked()
        }
    }

    private fun rotateLocked() {
        closeWriterLocked()
        try {
            val file = logFileLocked() ?: return
            val backup = File(file.parentFile, "$FILE_NAME.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (_: Exception) {}
        fileBytes = 0
    }

    private fun closeWriterLocked() {
        try { fileWriter?.flush() } catch (_: Exception) {}
        try { fileWriter?.close() } catch (_: Exception) {}
        fileWriter = null
        fileSinceFlush = 0
    }

    private fun logFileLocked(): File? {
        val ctx = appContext ?: return null
        return logFile(ctx)
    }
}
