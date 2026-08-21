package com.example.bitperfectplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded MPD-protocol server. Drives playback **only** through a
 * MediaController bound to PlaybackService — never touches the
 * bit-perfect audio chain (AudioSink / BitPerfectManager).
 */
@OptIn(UnstableApi::class)
class MpdServer(private val context: Context) {

    companion object {
        private const val TAG = "MpdServer"
        const val PORT = 6600
        fun getConfiguredPort(ctx: Context): Int =
            ctx.getSharedPreferences("AppSettings", Context.MODE_PRIVATE).getInt("mpd_port", 6600).coerceIn(1024, 65535)
        private const val REPORTED_VERSION = "0.24.0"
        private const val HOP_TIMEOUT_MS = 3000L
        private const val IDLE_MAX_MS = 60_000L

        // ACK codes
        const val ACK_NOT_LIST = 1
        const val ACK_ARG = 2
        const val ACK_PASSWORD = 3
        const val ACK_PERMISSION = 4
        const val ACK_UNKNOWN = 5
        const val ACK_NO_EXIST = 50
        const val ACK_SYSTEM = 52
        const val ACK_UPDATE_ALREADY = 54

        val SUPPORTED_COMMANDS = listOf(
            "close", "kill", "ping", "password", "commands", "notcommands",
            "tagtypes", "urlhandlers", "decoders",
            "status", "currentsong", "playlistinfo", "playlistid", "plchanges", "plchangesposid",
            "play", "playid", "pause", "stop", "next", "previous",
            "seek", "seekid", "seekcur", "random", "repeat", "single", "setvol", "volume", "crossfade",
            "clear", "add", "addid", "delete", "deleteid", "move", "moveid", "swap", "swapid", "shuffle", "prio", "prioid",
            "lsinfo", "listall", "listallinfo", "update", "rescan",
            "list", "find", "search", "searchadd", "findadd", "count",
            "outputs", "enableoutput", "disableoutput", "toggleoutput", "outputset",
            "mixrampdb", "mixrampdelay", "replay_gain_status", "replay_gain_mode",
            "stats", "idle", "noidle",
            "partition", "partitions", "listpartitions",
            "listplaylists", "listplaylist", "listplaylistinfo", "load", "save", "rm", "rename",
            "command_list_begin", "command_list_ok_begin", "command_list_end"
        )
        val SUPPORTED_TAGS = listOf("Artist", "AlbumArtist", "Album", "Title", "Track", "Genre")
    }

    class MpdAck(val code: Int, msg: String) : Exception(msg)
    private object CloseSignal : Exception()

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    val library = MpdLibrary(appContext)

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = Executors.newCachedThreadPool { r -> Thread(r, "mpd-client").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mpd-poll").apply { isDaemon = true } }

    @Volatile private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var startedAtMs = System.currentTimeMillis()
    private val clientCount = AtomicInteger(0)
    fun getClientCount(): Int = clientCount.get()
    /** Port actually bound, or the configured port if not yet bound. */
    @Volatile private var boundPort = 0
    fun getPort(): Int = if (boundPort > 0) boundPort else getConfiguredPort(appContext)

    // Queue bookkeeping
    private val qLock = Any()
    private var songIds: List<Int> = emptyList()
    private var nextSongId = 1
    private var queueVersion = 1
    private var lastFingerprint = ""

    // Idle
    private inner class IdleWaiter(val wanted: Set<String>) {
        val latch = CountDownLatch(1)
        @Volatile var fired: Set<String> = emptySet()
    }
    private val idleWaiters = CopyOnWriteArrayList<IdleWaiter>()
    private var pollerStarted = false
    private var updateJobId = 0

    // Stored playlists dir
    private val playlistDir: File get() = File(Environment.getExternalStorageDirectory(), "Playlists")

    fun start() {
        if (running) return
        running = true
        startedAtMs = System.currentTimeMillis()
        connectController(0)
        library.onScanFinished = { fireIdle(setOf("database", "update")) }
        Thread({ acceptLoop() }, "mpd-accept").apply { isDaemon = true; start() }
        ensurePoller()
        Log.i(TAG, "MPD server starting on port ${getConfiguredPort(appContext)}")
    }

    fun stop() {
        running = false
        boundPort = 0
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.shutdownNow()
        scheduler.shutdownNow()
        library.cancel()
        mainHandler.post {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null; controller = null
        }
    }

    private fun connectController(attempt: Int) {
        if (!running) return
        try {
            val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
            val future = MediaController.Builder(appContext, token).buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    controller = future.get()
                    Log.i(TAG, "MPD bridge connected to PlaybackService")
                } catch (e: Exception) {
                    Log.w(TAG, "MediaController connect failed attempt $attempt: ${e.message}")
                    if (attempt < 5 && running) mainHandler.postDelayed({ connectController(attempt + 1) }, 3000)
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e(TAG, "connectController failed", e)
            if (attempt < 5 && running) mainHandler.postDelayed({ connectController(attempt + 1) }, 3000)
        }
    }

    private fun acceptLoop() {
        val port = getPort()
        var ss: ServerSocket? = null
        for (i in 1..3) {
            if (!running) return
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                ss = s; break
            } catch (e: Exception) {
                Log.e(TAG, "MPD bind attempt $i failed on $port", e)
                try { Thread.sleep(8000) } catch (_: InterruptedException) { return }
            }
        }
        if (ss == null) { Log.e(TAG, "MPD disabled: cannot bind $port"); return }
        serverSocket = ss
        boundPort = port
        val pwdInfo = if (getPassword().isNotEmpty()) " (password set)" else ""
        Log.i(TAG, "MPD server listening on $port — configure MALP host=<shield-ip> port=$port$pwdInfo")
        while (running) {
            val sock = try { ss.accept() } catch (_: Exception) { break }
            clients.execute(Client(sock))
        }
    }

    // ── Idle poller ──────────────────────────────────────────────────────────

    private fun ensurePoller() {
        if (pollerStarted) return
        pollerStarted = true
        scheduler.scheduleWithFixedDelay({
            if (idleWaiters.isEmpty() || !running) return@scheduleWithFixedDelay
            val changed = pollChanges() ?: return@scheduleWithFixedDelay
            if (changed.isEmpty()) return@scheduleWithFixedDelay
            for (w in idleWaiters) {
                val fired = if (w.wanted.isEmpty()) changed else changed.intersect(w.wanted)
                if (fired.isNotEmpty()) { w.fired = fired; w.latch.countDown() }
            }
            idleWaiters.removeIf { it.latch.count == 0L }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    private var lastSnap: Snapshot? = null

    private data class Snapshot(
        val state: Int, val playing: Boolean, val index: Int, val count: Int,
        val mediaId: String?, val repeat: Int, val random: Boolean, val queueFp: String
    )

    private fun pollChanges(): Set<String>? {
        val snap = lightSnapshot() ?: return null
        val prev = lastSnap
        lastSnap = snap
        if (prev == null) return emptySet()
        val out = mutableSetOf<String>()
        if (snap.state != prev.state || snap.playing != prev.playing || snap.index != prev.index || snap.mediaId != prev.mediaId) out.add("player")
        if (snap.count != prev.count || snap.queueFp != prev.queueFp) out.add("playlist")
        if (snap.repeat != prev.repeat || snap.random != prev.random) out.add("options")
        return out
    }

    fun fireIdle(subsystems: Set<String>) {
        for (w in idleWaiters) {
            val fired = if (w.wanted.isEmpty()) subsystems else subsystems.intersect(w.wanted)
            if (fired.isNotEmpty()) { w.fired = fired; w.latch.countDown() }
        }
        idleWaiters.removeIf { it.latch.count == 0L }
    }

    // ── Main-thread hop ─────────────────────────────────────────────────────

    private fun <T> hop(block: (MediaController) -> T): T {
        val c = controller ?: throw MpdAck(ACK_SYSTEM, "player not connected")
        if (Looper.myLooper() == Looper.getMainLooper()) return block(c)
        var res: T? = null; var err: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post { try { res = block(c) } catch (t: Throwable) { err = t } finally { latch.countDown() } }
        if (!latch.await(HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw MpdAck(ACK_SYSTEM, "player busy")
        err?.let { e -> if (e is MpdAck) throw e else throw MpdAck(ACK_SYSTEM, e.message ?: "error") }
        @Suppress("UNCHECKED_CAST") return res as T
    }

    private fun hopVoid(block: (MediaController) -> Unit) = hop(block)

    // ── Snapshot helpers ────────────────────────────────────────────────────

    private data class ItemInfo(val mediaId: String, val title: String, val artist: String, val album: String, val durationMs: Long)

    private fun lightSnapshot(): Snapshot? = try {
        hop { c ->
            val fp = buildFingerprint(c)
            Snapshot(c.playbackState, c.isPlaying, c.currentMediaItemIndex, c.mediaItemCount,
                c.currentMediaItem?.mediaId, c.repeatMode, c.shuffleModeEnabled, fp)
        }
    } catch (_: Exception) { null }

    private fun buildFingerprint(c: MediaController): String {
        // count + ordered mediaIds hash
        val sb = StringBuilder(); sb.append(c.mediaItemCount).append('|')
        for (i in 0 until minOf(c.mediaItemCount, 512)) sb.append(c.getMediaItemAt(i).mediaId.hashCode()).append(',')
        return sb.toString()
    }

    private fun syncIdsIfNeeded(c: MediaController) {
        synchronized(qLock) {
            val count = c.mediaItemCount
            if (songIds.size != count) {
                // rebuild preserving where mediaId stable at same index
                val newIds = MutableList(count) { 0 }
                for (i in 0 until count) {
                    val mid = c.getMediaItemAt(i).mediaId
                    val oldMid = if (i < songIds.size) runCatching { c.getMediaItemAt(i).mediaId }.getOrNull() else null
                    // naive reuse check: if old slot had same mediaId keep its id
                    val reused = if (i < songIds.size && oldMid == mid) songIds[i] else 0
                    newIds[i] = if (reused != 0) reused else nextSongId++
                }
                if (newIds != songIds) { songIds = newIds; queueVersion++ }
                lastFingerprint = buildFingerprint(c)
                return
            }
            val fp = buildFingerprint(c)
            if (fp != lastFingerprint) {
                // order changed — assign fresh ids for moved entries is OK; bump version
                queueVersion++
                lastFingerprint = fp
            }
        }
    }

    private fun posOfId(id: Int): Int = synchronized(qLock) { songIds.indexOf(id) }

    private fun idOfPos(pos: Int): Int = synchronized(qLock) { songIds.getOrNull(pos) ?: -1 }

    // ── Client ──────────────────────────────────────────────────────────────

    private inner class Client(private val sock: Socket) : Runnable {
        override fun run() {
            // Reset per-connection auth (pooled threads reuse ThreadLocal)
            clientAuth.set(getPassword().isEmpty())
            clientCount.incrementAndGet()
            try {
                sock.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                send(writer, "OK MPD $REPORTED_VERSION")
                while (running && !sock.isClosed) {
                    val raw = reader.readLine() ?: break
                    if (raw.isBlank()) continue
                    val t = raw.trim()
                    if (t == "command_list_begin" || t == "command_list_ok_begin") {
                        val okEach = t == "command_list_ok_begin"
                        val lines = mutableListOf<String>()
                        while (true) { val l = reader.readLine() ?: break; if (l.trim() == "command_list_end") break; lines.add(l) }
                        var hadError = false
                        for ((idx, line) in lines.withIndex()) {
                            if (hadError) break
                            try { execLine(line, writer, reader); if (okEach) send(writer, "OK") }
                            catch (e: MpdAck) { sendAck(writer, idx, lineToken(line), e); hadError = true }
                            catch (_: CloseSignal) { sock.close(); return }
                        }
                        if (!hadError && !okEach) send(writer, "OK")
                        continue
                    }
                    try { execLine(raw, writer, reader); send(writer, "OK") }
                    catch (e: MpdAck) { sendAck(writer, 0, lineToken(raw), e) }
                    catch (_: CloseSignal) { break }
                }
            } catch (e: Exception) { if (running) Log.d(TAG, "client: ${e.message}") }
            finally { clientCount.decrementAndGet(); try { sock.close() } catch (_: Exception) {} }
        }
        private fun lineToken(line: String): String = try { tokenize(line).firstOrNull() ?: "" } catch (_: Exception) { "" }
    }

    // Use Client2 as actual client — keep Client stub for binary compat, but acceptLoop uses Client2
    // Patch: override acceptLoop to use Client2

    private fun send(w: BufferedWriter, s: String) { w.write(s); w.write("\n"); w.flush() }
    private fun sendAck(w: BufferedWriter, pos: Int, cmd: String, e: MpdAck) {
        w.write("ACK [${e.code}@$pos] {$cmd} ${e.message}\n"); w.flush()
    }

    // ── Tokenize ────────────────────────────────────────────────────────────

    fun tokenize(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var i = 0; var inQ = false; var had = false
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ -> when {
                    ch == '\\' && i + 1 < line.length && (line[i + 1] == '"' || line[i + 1] == '\\') -> { cur.append(line[i + 1]); i++ }
                    ch == '"' -> inQ = false
                    else -> cur.append(ch)
                }
                ch == '"' -> { inQ = true; had = true }
                ch.isWhitespace() -> { if (cur.isNotEmpty() || had) { out.add(cur.toString()); cur.setLength(0); had = false } }
                else -> cur.append(ch)
            }
            i++
        }
        if (inQ) throw MpdAck(ACK_ARG, "unterminated quote")
        if (cur.isNotEmpty() || had) out.add(cur.toString())
        return out
    }

    // ── Dispatch ───────────────────────────────────────────────────────────

    private fun getPassword(): String =
        appContext.getSharedPreferences("AppSettings", Context.MODE_PRIVATE).getString("mpd_password", "") ?: ""

    // Per-connection auth flag is stored in Client; execLine checks it via helper below.
    // We pass authenticated state via ThreadLocal for simplicity (each Client thread has its own).
    private val clientAuth = ThreadLocal.withInitial { false }

    @Throws(MpdAck::class, CloseSignal::class)
    private fun execLine(line: String, writer: BufferedWriter, reader: BufferedReader? = null) {
        Log.d(TAG, "MPD <- $line")
        val toks = tokenize(line)
        if (toks.isEmpty()) return
        val cmd = toks[0].lowercase()
        val args = toks.drop(1)
        // Password handling: always allow password/close/ping even when auth required
        val pwd = getPassword()
        val needAuth = pwd.isNotEmpty() && !(clientAuth.get() ?: false)
        if (needAuth && cmd !in setOf("password", "close", "ping")) {
            throw MpdAck(ACK_PASSWORD, "password required")
        }
        // idle needs special handling — it blocks; pass reader so noidle can interrupt
        if (cmd == "idle") { handleIdle(args, writer, reader); return }
        if (cmd == "noidle") {
            // If we are inside idle, this will be consumed by handleIdle's reader poll; otherwise just ack
            return
        }
        if (cmd == "password") {
            val supplied = args.firstOrNull() ?: ""
            if (pwd.isEmpty() || supplied == pwd) {
                clientAuth.set(true)
                return
            } else throw MpdAck(ACK_PASSWORD, "incorrect password")
        }
        val sb = StringBuilder()
        try {
            dispatch(cmd, args, sb, writer)
            if (sb.isNotEmpty()) { writer.write(sb.toString()); writer.flush() }
            Log.d(TAG, "MPD -> $cmd OK (${sb.length} bytes)")
        } catch (e: MpdAck) {
            Log.w(TAG, "MPD -> $cmd ACK ${e.code} ${e.message}")
            throw e
        }
    }

    private fun handleIdle(args: List<String>, writer: BufferedWriter, reader: BufferedReader?) {
        val wanted: Set<String> = if (args.isEmpty()) emptySet() else args.flatMap { it.split(" ") }.filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
        val waiter = IdleWaiter(wanted)
        idleWaiters.add(waiter)
        ensurePoller()
        // Wait up to IDLE_MAX_MS but wake early if client sends noidle
        var remaining = IDLE_MAX_MS
        val step = 100L
        while (remaining > 0) {
            if (waiter.latch.await(step, TimeUnit.MILLISECONDS)) break
            // Check if client sent noidle
            if (reader != null) {
                try {
                    if (reader.ready()) {
                        // Peek without consuming OK handling: client will send "noidle"
                        // We need to consume that line here so outer loop doesn't see it as next command
                        // Mark and read if available
                        reader.mark(16)
                        // Use ready + readLine non-blocking check
                        if (reader.ready()) {
                            val peek = reader.readLine()
                            if (peek != null && peek.trim().lowercase() == "noidle") {
                                idleWaiters.remove(waiter)
                                break
                            } else if (peek != null) {
                                // Unexpected line during idle — treat as noidle wake and push back not possible
                                // Just break and let outer loop handle it on next iteration (re-inject via flag)
                                // We consumed it, so we need to handle it as next command — store for outer loop
                                // For now just break idle and the consumed line is lost — so we handle it by dispatching it here
                                // Dispatch the consumed non-noidle line as next command
                                try {
                                    execLine(peek, writer, reader)
                                    // Send OK for that command if needed is handled inside execLine's caller — we need to send OK here
                                    // execLine for non-idle doesn't send OK itself; caller sends OK. So we mimic:
                                    writer.write("OK\n"); writer.flush()
                                } catch (_: Exception) {}
                                idleWaiters.remove(waiter)
                                break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            remaining -= step
        }
        if (idleWaiters.contains(waiter)) idleWaiters.remove(waiter)
        val fired = waiter.fired
        for (sub in fired) { writer.write("changed: $sub\n") }
        writer.flush()
    }

    @Throws(MpdAck::class, CloseSignal::class)
    private fun dispatch(cmd: String, args: List<String>, out: StringBuilder, writer: BufferedWriter) {
        when (cmd) {
            "ping" -> {}
            "password" -> {} // no auth
            "kill" -> {} // ignore — don't kill service
            "close" -> throw CloseSignal
            "commands" -> SUPPORTED_COMMANDS.forEach { out.append("command: ").append(it).append('\n') }
            "notcommands" -> {}
            "tagtypes" -> SUPPORTED_TAGS.forEach { out.append("tagtype: ").append(it).append('\n') }
            "urlhandlers" -> { out.append("handler: http://\n"); out.append("handler: https://\n") }
            "decoders" -> {}
            "binarylimit" -> {} // MALP sends this
            "getvol" -> out.append("volume: -1\n")
            "partition" -> out.append("partition: default\n")
            "partitions", "listpartitions" -> out.append("partition: default\n")
            "outputs" -> out.append("outputid: 0\noutputname: bitperfect\noutputenabled: 1\n")
            "enableoutput", "disableoutput", "toggleoutput", "outputset" -> {}
            "mixrampdb", "mixrampdelay" -> {}
            "replay_gain_status" -> out.append("replay_gain_mode: off\n")
            "replay_gain_mode" -> {} // ignore, stay off for bit-perfect
            "config" -> throw MpdAck(ACK_PERMISSION, "command config requires admin")
            "stats" -> writeStats(out)
            "status" -> writeStatus(out)
            "currentsong" -> writeCurrentsong(out)
            "playlistinfo" -> writePlaylistInfo(args, out)
            "playlistid" -> writePlaylistId(args, out)
            "plchanges", "plchangesposid" -> writePlChanges(args, out, posOnly = cmd == "plchangesposid")
            "play" -> { val p = args.firstOrNull()?.toIntOrNull(); hop { c -> if (p != null) { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.seekTo(p, 0); c.play() } else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } } }
            "playid" -> { val id = args.firstOrNull()?.toIntOrNull() ?: throw MpdAck(ACK_ARG, "need id"); val pos = posOfId(id); if (pos < 0) throw MpdAck(ACK_NO_EXIST, "No such song"); hop { c -> if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.seekTo(pos, 0); c.play() } }
            "pause" -> {
                val v = args.firstOrNull()
                hop { c -> when (v) { null -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }; "0" -> { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }; "1" -> c.pause(); else -> throw MpdAck(ACK_ARG, "bad pause arg") } }
            }
            "stop" -> hopVoid { it.stop() }
            "next" -> hopVoid { it.seekToNext() }
            "previous" -> hopVoid { it.seekToPrevious() }
            "seek" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need pos and time")
                val pos = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad pos")
                val secs = args[1].toDoubleOrNull() ?: throw MpdAck(ACK_ARG, "bad time")
                hop { it.seekTo(pos, (secs * 1000).toLong()) }
            }
            "seekid" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need id and time")
                val id = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad id")
                val pos = posOfId(id); if (pos < 0) throw MpdAck(ACK_NO_EXIST, "No such song")
                val secs = args[1].toDoubleOrNull() ?: throw MpdAck(ACK_ARG, "bad time")
                hop { it.seekTo(pos, (secs * 1000).toLong()) }
            }
            "seekcur" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need time")
                val raw = args[0]
                val rel = raw.startsWith("+") || raw.startsWith("-")
                val secs = raw.toDoubleOrNull() ?: throw MpdAck(ACK_ARG, "bad time")
                hop { c -> if (rel) c.seekTo(c.currentPosition + (secs * 1000).toLong()) else c.seekTo((secs * 1000).toLong()) }
            }
            "random" -> { val v = args.firstOrNull()?.toIntOrNull() ?: throw MpdAck(ACK_ARG, "need 0/1"); hop { it.shuffleModeEnabled = v != 0 } }
            "repeat" -> { val v = args.firstOrNull()?.toIntOrNull() ?: throw MpdAck(ACK_ARG, "need 0/1"); hop { it.repeatMode = if (v != 0) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF } }
            "single" -> { val v = args.firstOrNull()?.toIntOrNull() ?: throw MpdAck(ACK_ARG, "need 0/1"); hop { it.repeatMode = if (v != 0) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF } }
            "setvol", "volume" -> throw MpdAck(ACK_SYSTEM, "no mixer (bit-perfect output)")
            "crossfade" -> {} // ignore
            "clear" -> hopVoid { it.clearMediaItems() }
            "add" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need uri")
                val items = expandUri(args[0])
                if (items.isEmpty()) throw MpdAck(ACK_NO_EXIST, "No such directory or no playable files")
                hop { it.addMediaItems(items) }
            }
            "addid" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need uri")
                val uri = args[0]
                val posArg = args.getOrNull(1)?.toIntOrNull()
                val items = expandUri(uri)
                if (items.isEmpty()) throw MpdAck(ACK_NO_EXIST, "No such directory")
                val insertedPos = hop { c ->
                    val at = posArg?.coerceIn(0, c.mediaItemCount) ?: c.mediaItemCount
                    c.addMediaItems(at, items)
                    at
                }
                // need id of first inserted
                synchronized(qLock) { /* ids will be synced on next poll/status; approximate */ }
                // Return Id of inserted first track (best effort)
                val newId = synchronized(qLock) { songIds.getOrNull(insertedPos) ?: nextSongId }
                out.append("Id: ").append(newId).append('\n')
            }
            "delete" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need pos")
                val (from, until) = parseRange(args[0])
                hop { c ->
                    val f = from.coerceIn(0, c.mediaItemCount)
                    val u = until.coerceIn(f, c.mediaItemCount)
                    if (f < u) c.removeMediaItems(f, u - f)
                }
            }
            "deleteid" -> {
                val id = args.firstOrNull()?.toIntOrNull() ?: throw MpdAck(ACK_ARG, "need id")
                val pos = posOfId(id); if (pos < 0) throw MpdAck(ACK_NO_EXIST, "No such song")
                hop { it.removeMediaItems(pos, 1) }
            }
            "move" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need from and to")
                val (from, until) = parseRange(args[0])
                val to = args[1].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad to")
                hop { c -> c.moveMediaItems(from, until, to) }
            }
            "moveid" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need id and to")
                val id = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad id")
                val pos = posOfId(id); if (pos < 0) throw MpdAck(ACK_NO_EXIST, "No such song")
                val to = args[1].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad to")
                hop { it.moveMediaItems(pos, pos + 1, to) }
            }
            "swap" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need two positions")
                val a = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad pos")
                val b = args[1].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad pos")
                hop { c ->
                    if (a == b) return@hop
                    // swap via two moves
                    if (a < b) { c.moveMediaItems(a, a + 1, b); c.moveMediaItems(b - 1, b, a) }
                    else { c.moveMediaItems(a, a + 1, b); c.moveMediaItems(b + 1, b + 2, a + 1) }
                }
            }
            "swapid" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need two ids")
                val a = posOfId(args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad id"))
                val b = posOfId(args[1].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad id"))
                if (a < 0 || b < 0) throw MpdAck(ACK_NO_EXIST, "No such song")
                hop { c ->
                    if (a == b) return@hop
                    if (a < b) { c.moveMediaItems(a, a + 1, b); c.moveMediaItems(b - 1, b, a) }
                    else { c.moveMediaItems(a, a + 1, b); c.moveMediaItems(b + 1, b + 2, a + 1) }
                }
            }
            "shuffle" -> {
                // MPD shuffle [range] — we just toggle shuffle mode for now
                if (args.isEmpty()) hop { it.shuffleModeEnabled = true } else {
                    val (f, u) = parseRange(args[0]); hop { c -> c.moveMediaItems(f, u, f) } // no-op placeholder
                    hop { it.shuffleModeEnabled = true }
                }
            }
            "prio", "prioid" -> {} // ignore priority
            "lsinfo" -> writeLsInfo(args.firstOrNull(), out)
            "listall", "listallinfo" -> writeListAll(args.firstOrNull(), out, withInfo = cmd == "listallinfo")
            "update", "rescan" -> {
                val job = try { library.startUpdate() } catch (e: MpdAck) { throw e }
                updateJobId = job
                out.append("updating_db: ").append(job).append('\n')
                fireIdle(setOf("update"))
            }
            "list" -> writeList(args, out)
            "find", "search", "findadd", "searchadd" -> writeFindSearch(cmd, args, out)
            "count" -> writeCount(args, out)
            "listplaylists" -> writeListPlaylists(out)
            "listplaylist" -> writeListPlaylist(args, out, withInfo = false)
            "listplaylistinfo" -> writeListPlaylist(args, out, withInfo = true)
            "load" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need playlist name")
                val items = loadStoredPlaylist(args[0])
                if (items.isEmpty()) throw MpdAck(ACK_NO_EXIST, "No such playlist")
                hop { it.addMediaItems(items) }
            }
            "save" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need name")
                saveStoredPlaylist(args[0])
            }
            "rm" -> {
                if (args.isEmpty()) throw MpdAck(ACK_ARG, "need name")
                val f = playlistFile(args[0]); if (!f.exists() || !f.delete()) throw MpdAck(ACK_NO_EXIST, "No such playlist")
                invalidatePlaylistCache()
            }
            "rename" -> {
                if (args.size < 2) throw MpdAck(ACK_ARG, "need old and new")
                val a = playlistFile(args[0]); val b = playlistFile(args[1])
                if (!a.exists()) throw MpdAck(ACK_NO_EXIST, "No such playlist")
                if (b.exists()) throw MpdAck(ACK_SYSTEM, "playlist already exists")
                if (!a.renameTo(b)) throw MpdAck(ACK_SYSTEM, "rename failed")
                invalidatePlaylistCache()
            }
                        "albumart", "readpicture" -> throw MpdAck(ACK_NO_EXIST, "no album art")
            else -> throw MpdAck(ACK_UNKNOWN, "unknown command \"$cmd\"")
        }
    }

    // ── Status helpers ──────────────────────────────────────────────────────

    private fun writeStats(out: StringBuilder) {
        val (artists, albums, songs) = library.counts()
        val playtime = library.totalPlaytimeSec()
        val uptime = (System.currentTimeMillis() - startedAtMs) / 1000
        out.append("artists: ").append(artists).append('\n')
        out.append("albums: ").append(albums).append('\n')
        out.append("songs: ").append(songs).append('\n')
        out.append("uptime: ").append(uptime).append('\n')
        out.append("playtime: ").append(playtime).append('\n')
        out.append("db_playtime: ").append(playtime).append('\n')
        out.append("db_update: ").append(library.lastUpdated()).append('\n')
    }

    private data class StatusSnap(
        val state: String, val vol: Int, val repeat: Int, val random: Int, val single: Int,
        val qv: Int, val count: Int, val index: Int, val songId: Int, val nextSong: Int, val nextId: Int,
        val posSec: Long, val durMs: Long, val playbackState: Int, val posMs: Long,
        val bitrate: Int, val sr: Int, val ch: Int, val bits: Int, val hasAudio: Boolean, val updating: Int?
    )

    private fun writeStatus(out: StringBuilder) {
        val s = hop { c ->
            syncIdsIfNeeded(c)
            // Use playWhenReady for buffering: isPlaying is false while buffering even though user wants play
            val state = when (c.playbackState) {
                Player.STATE_READY -> if (c.isPlaying) "play" else "pause"
                Player.STATE_BUFFERING -> if (c.playWhenReady) "play" else "pause"
                else -> "stop"
            }
            val fmt = findAudioFormat(c)
            StatusSnap(
                state = state, vol = -1,
                repeat = if (c.repeatMode == Player.REPEAT_MODE_ALL || c.repeatMode == Player.REPEAT_MODE_ONE) 1 else 0,
                random = if (c.shuffleModeEnabled) 1 else 0,
                single = if (c.repeatMode == Player.REPEAT_MODE_ONE) 1 else 0,
                qv = synchronized(qLock) { queueVersion },
                count = c.mediaItemCount, index = c.currentMediaItemIndex,
                songId = if (c.currentMediaItemIndex >= 0) idOfPos(c.currentMediaItemIndex) else -1,
                nextSong = if (c.mediaItemCount > c.currentMediaItemIndex + 1) c.currentMediaItemIndex + 1 else -1,
                nextId = if (c.mediaItemCount > c.currentMediaItemIndex + 1) idOfPos(c.currentMediaItemIndex + 1) else -1,
                posSec = c.currentPosition / 1000, durMs = c.duration, playbackState = c.playbackState, posMs = c.currentPosition,
                bitrate = fmt?.bitrate ?: 0, sr = fmt?.sampleRate ?: 0, ch = fmt?.channelCount ?: 0, bits = fmt?.let { pcmBits(it) } ?: 0,
                hasAudio = fmt != null, updating = if (library.scanning) updateJobId else null
            )
        }
        out.append("volume: ").append(s.vol).append('\n')
        out.append("repeat: ").append(s.repeat).append('\n')
        out.append("random: ").append(s.random).append('\n')
        out.append("single: ").append(s.single).append('\n')
        out.append("consume: 0\n")
        out.append("partition: default\n")
        out.append("playlist: ").append(s.qv).append('\n')
        out.append("playlistlength: ").append(s.count).append('\n')
        if (s.count > 0 && s.index >= 0) {
            out.append("song: ").append(s.index).append('\n')
            out.append("songid: ").append(s.songId).append('\n')
            if (s.nextSong >= 0) {
                out.append("nextsong: ").append(s.nextSong).append('\n')
                out.append("nextsongid: ").append(s.nextId).append('\n')
            }
        }
        if (s.durMs != C.TIME_UNSET && s.durMs > 0) {
            val durSec = s.durMs / 1000
            out.append("time: ").append(s.posSec).append(":").append(durSec).append('\n')
            out.append("elapsed: ").append(String.format(Locale.US, "%.3f", s.posMs / 1000.0)).append('\n')
            out.append("duration: ").append(String.format(Locale.US, "%.3f", s.durMs / 1000.0)).append('\n')
        } else if (s.playbackState != Player.STATE_IDLE) {
            out.append("time: ").append(s.posSec).append('\n')
            out.append("elapsed: ").append(String.format(Locale.US, "%.3f", s.posMs / 1000.0)).append('\n')
        }
        if (s.hasAudio) {
            if (s.bitrate > 0) out.append("bitrate: ").append(s.bitrate / 1000).append('\n')
            val sr = if (s.sr > 0) s.sr else 44100
            val ch = if (s.ch > 0) s.ch else 2
            val bits = if (s.bits != 0) s.bits else 16
            out.append("audio: ").append(sr).append(":").append(bits).append(":").append(ch).append('\n')
        }
        s.updating?.let { out.append("updating_db: ").append(it).append('\n') }
        out.append("xfade: 0\n")
        out.append("mixrampdb: 0.000000\n")
        out.append("mixrampdelay: nan\n")
        out.append("state: ").append(s.state).append('\n')
    }

    private fun writeCurrentsong(out: StringBuilder) {
        hop { c ->
            syncIdsIfNeeded(c)
            val idx = c.currentMediaItemIndex
            if (idx < 0 || idx >= c.mediaItemCount) return@hop
            appendSong(c, idx, out)
        }
    }

    private fun writePlaylistInfo(args: List<String>, out: StringBuilder) {
        hop { c ->
            syncIdsIfNeeded(c)
            if (args.isEmpty()) {
                for (i in 0 until c.mediaItemCount) appendSong(c, i, out)
            } else {
                val (from, until) = parseRange(args[0])
                for (i in from until minOf(until, c.mediaItemCount)) appendSong(c, i, out)
            }
        }
    }

    private fun writePlaylistId(args: List<String>, out: StringBuilder) {
        hop { c ->
            syncIdsIfNeeded(c)
            if (args.isEmpty()) {
                for (i in 0 until c.mediaItemCount) appendSong(c, i, out)
            } else {
                val id = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad id")
                val pos = posOfId(id); if (pos < 0) throw MpdAck(ACK_NO_EXIST, "No such song")
                appendSong(c, pos, out)
            }
        }
    }

    private fun writePlChanges(args: List<String>, out: StringBuilder, posOnly: Boolean) {
        if (args.isEmpty()) throw MpdAck(ACK_ARG, "need version")
        val ver = args[0].toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad version")
        val qv = synchronized(qLock) { queueVersion }
        if (ver >= qv) return
        // For simplicity return full playlist (MALP handles it)
        hop { c ->
            syncIdsIfNeeded(c)
            val range = if (args.size >= 2) parseRange(args[1]) else 0 to c.mediaItemCount
            for (i in range.first until minOf(range.second, c.mediaItemCount)) {
                if (posOnly) { out.append("cpos: ").append(i).append('\n'); out.append("Id: ").append(idOfPos(i)).append('\n') }
                else appendSong(c, i, out)
            }
        }
    }

    private fun appendSong(c: MediaController, pos: Int, out: StringBuilder) {
        val item = c.getMediaItemAt(pos)
        val uri = item.mediaId
        val filePath = mpdPath(uri)
        val id = idOfPos(pos)
        out.append("file: ").append(filePath).append('\n')
        val lastMod = try {
            val f = File(Uri.decode(uri.removePrefix("file://")))
            if (f.exists()) iso8601(f.lastModified()) else iso8601(System.currentTimeMillis())
        } catch (_: Exception) { iso8601(System.currentTimeMillis()) }
        out.append("Last-Modified: ").append(lastMod).append('\n')
        // duration
        val durMs = c.duration.takeIf { pos == c.currentMediaItemIndex && it != C.TIME_UNSET } ?: C.TIME_UNSET
        val lib = try { library.lookup(File(Uri.decode(uri.removePrefix("file://"))).absolutePath) } catch (_: Exception) { null }
        val secs = when {
            durMs != C.TIME_UNSET && pos == c.currentMediaItemIndex -> (durMs / 1000).toInt()
            lib != null && lib.durationSec > 0 -> lib.durationSec
            else -> -1
        }
        if (secs >= 0) { out.append("Time: ").append(secs).append('\n'); out.append("duration: ").append(String.format(Locale.US, "%.3f", secs.toDouble())).append('\n') }
        val title = item.mediaMetadata.title?.toString() ?: lib?.title ?: File(filePath).nameWithoutExtension
        val artist = item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: lib?.artist ?: ""
        val album = item.mediaMetadata.albumTitle?.toString() ?: lib?.album ?: ""
        val genre = lib?.genre ?: ""
        val trackNo = lib?.trackNo ?: 0
        if (artist.isNotBlank()) out.append("Artist: ").append(artist).append('\n')
        if (album.isNotBlank()) out.append("Album: ").append(album).append('\n')
        out.append("Title: ").append(title).append('\n')
        if (trackNo > 0) out.append("Track: ").append(trackNo).append('\n')
        if (genre.isNotBlank()) out.append("Genre: ").append(genre).append('\n')
        // Format line per song (optional)
        val fmt = findAudioFormat(c).takeIf { pos == c.currentMediaItemIndex }
        if (fmt != null) {
            val sr = if (fmt.sampleRate > 0) fmt.sampleRate else null
            val ch = if (fmt.channelCount > 0) fmt.channelCount else null
            val bits = pcmBits(fmt)
            if (sr != null && ch != null && bits != null) out.append("Format: ").append(sr).append(":").append(bits).append(":").append(ch).append('\n')
        }
        out.append("Pos: ").append(pos).append('\n')
        out.append("Id: ").append(id).append('\n')
    }

    // ── Filesystem browsing ────────────────────────────────────────────────

    private fun storageRoots(): List<Pair<File, String>> {
        val out = ArrayList<Pair<File, String>>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.isDirectory) out.add(primary to "Internal Storage")
        try {
            File("/storage").listFiles()?.forEach { f ->
                val n = f.name
                if (n == "emulated" || n == "self" || n.startsWith("private") || n.startsWith("enc_")) return@forEach
                if (f.isDirectory && f.canRead() && out.none { it.first.absolutePath == f.absolutePath }) {
                    out.add(f to f.name)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun resolveLocalPath(mpdUri: String): File {
        val trimmed = mpdUri.trim()
        if (trimmed.isEmpty() || trimmed == "/") throw MpdAck(ACK_NO_EXIST, "use lsinfo with empty uri for roots")
        // Special case: /storage itself → treat as roots listing
        if (trimmed == "/storage" || trimmed == "storage") {
            return File("/storage")
        }
        val decoded = Uri.decode(trimmed)
        val f = File(decoded)
        if (!f.isAbsolute) throw MpdAck(ACK_ARG, "relative paths not supported")
        // /storage is the parent of all roots — allow it
        if (f.absolutePath == "/storage") return f
        // Containment check against roots (+ /storage parent)
        val roots = storageRoots().map { it.first.absolutePath.trimEnd('/') } + "/storage"
        val canon = try { f.canonicalPath } catch (_: Exception) { f.absolutePath }
        val allowed = roots.any { canon == it || canon.startsWith("$it/") }
        if (!allowed) throw MpdAck(ACK_NO_EXIST, "No such directory")
        return f
    }

    private fun writeLsInfo(arg: String?, out: StringBuilder) {
        // /storage and empty both list roots — MALP sometimes does lsinfo "/storage"
        if (arg.isNullOrBlank() || arg == "/" || arg == "/storage" || arg == "storage") {
            for ((root, label) in storageRoots()) {
                out.append("directory: ").append(root.absolutePath).append('\n')
                out.append("Last-Modified: ").append(iso8601(root.lastModified())).append('\n')
            }
            // stored playlists at root
            if (playlistDir.isDirectory) {
                playlistDir.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".m3u") || it.name.lowercase().endsWith(".m3u8") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.forEach { out.append("playlist: ").append(it.nameWithoutExtension).append('\n'); out.append("Last-Modified: ").append(iso8601(it.lastModified())).append('\n') }
            }
            return
        }
        val dir = resolveLocalPath(arg)
        if (!dir.exists() || !dir.isDirectory) throw MpdAck(ACK_NO_EXIST, "No such directory")
        val files = try { dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) } catch (_: Exception) { null }
            ?: throw MpdAck(ACK_NO_EXIST, "No such directory")
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                out.append("directory: ").append(f.absolutePath).append('\n')
                out.append("Last-Modified: ").append(iso8601(f.lastModified())).append('\n')
            } else if (f.name.lowercase().endsWith(".m3u") || f.name.lowercase().endsWith(".m3u8")) {
                out.append("playlist: ").append(f.absolutePath).append('\n')
                out.append("Last-Modified: ").append(iso8601(f.lastModified())).append('\n')
            } else if (MpdLibrary.isAudioFile(f.name)) {
                out.append("file: ").append(f.absolutePath).append('\n')
                out.append("Last-Modified: ").append(iso8601(f.lastModified())).append('\n')
                val lib = library.lookup(f.absolutePath)
                if (lib != null) {
                    if (lib.artist.isNotBlank()) out.append("Artist: ").append(lib.artist).append('\n')
                    if (lib.album.isNotBlank()) out.append("Album: ").append(lib.album).append('\n')
                    out.append("Title: ").append(lib.title).append('\n')
                    if (lib.durationSec > 0) { out.append("Time: ").append(lib.durationSec).append('\n'); out.append("duration: ").append(String.format(Locale.US, "%.3f", lib.durationSec.toDouble())).append('\n') }
                } else {
                    out.append("Title: ").append(f.nameWithoutExtension).append('\n')
                }
            }
        }
    }

    private fun writeListAll(arg: String?, out: StringBuilder, withInfo: Boolean) {
        val base = if (arg.isNullOrBlank() || arg == "/") null else resolveLocalPath(arg)
        val roots = if (base != null) listOf(base) else storageRoots().map { it.first }
        for (root in roots) walkListAll(root, out, withInfo)
    }

    private fun walkListAll(dir: File, out: StringBuilder, withInfo: Boolean) {
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { out.append("directory: ").append(f.absolutePath).append('\n'); walkListAll(f, out, withInfo) }
            else if (MpdLibrary.isAudioFile(f.name)) {
                out.append("file: ").append(f.absolutePath).append('\n')
                if (withInfo) {
                    val lib = library.lookup(f.absolutePath)
                    if (lib != null) { out.append("Title: ").append(lib.title).append('\n'); if (lib.artist.isNotBlank()) out.append("Artist: ").append(lib.artist).append('\n') }
                }
            }
        }
    }

    // ── list / find / search ──────────────────────────────────────────────

    private fun writeList(args: List<String>, out: StringBuilder) {
        if (args.isEmpty()) throw MpdAck(ACK_ARG, "need tag")
        val tag = args[0]
        val filterArgs = args.drop(1)
        val tracks = if (filterArgs.isEmpty()) library.all() else {
            val nodes = parseOldFilter(filterArgs, substring = false)
            library.all().filter { t -> nodes.all { library.matches(t, it) } }
        }
        val values: List<String> = when (tag.lowercase()) {
            "artist" -> tracks.map { it.artist.ifBlank { "Unknown Artist" } }
            "albumartist" -> tracks.map { (it.albumArtist.ifBlank { it.artist }).ifBlank { "Unknown Artist" } }
            "album" -> tracks.map { it.album.ifBlank { "Unknown Album" } }
            "title" -> tracks.map { it.title }
            "genre" -> tracks.mapNotNull { it.genre.takeIf { g -> g.isNotBlank() } }
            else -> throw MpdAck(ACK_ARG, "unknown tag $tag")
        }.distinctBy { it.lowercase() }.sortedWith(compareBy { it.lowercase() })
        for (v in values) out.append(tag).append(": ").append(v).append('\n')
    }

    private fun writeFindSearch(cmd: String, args: List<String>, out: StringBuilder) {
        val isSearch = cmd.startsWith("search")
        val isAdd = cmd.endsWith("add")
        // strip window [start:end] suffix if present
        val window = args.lastOrNull()?.let { parseWindow(it) }
        val filterArgs = if (window != null && args.isNotEmpty()) args.dropLast(1) else args
        // strip leading -- if present
        val stripped = if (filterArgs.firstOrNull() == "--") filterArgs.drop(1) else filterArgs
        val nodes: List<MpdLibrary.FNode> = when {
            stripped.isEmpty() -> emptyList()
            stripped.size == 1 && stripped[0].startsWith("(") -> listOf(parseFilterExpr(stripped[0]))
            stripped.size >= 1 && stripped[0].startsWith("(") -> {
                // MALP sometimes sends single parenthesized expr as one arg
                listOf(parseFilterExpr(stripped.joinToString(" ")))
            }
            else -> parseOldFilter(stripped, substring = isSearch)
        }
        var results = if (nodes.isEmpty()) library.all() else library.all().filter { t -> nodes.all { library.matches(t, it) } }
        if (window != null) {
            val (a, b) = window
            results = results.subList(a.coerceIn(0, results.size), b.coerceIn(a, results.size))
        }
        if (isAdd) {
            val items = results.mapNotNull { t -> File(t.path).takeIf { it.exists() }?.let { fileToMediaItem(it, t) } }
            if (items.isNotEmpty()) hop { it.addMediaItems(items) }
            return
        }
        for (t in results) {
            out.append("file: ").append(t.path).append('\n')
            out.append("Last-Modified: ").append(iso8601(t.lastModified)).append('\n')
            if (t.durationSec > 0) { out.append("Time: ").append(t.durationSec).append('\n'); out.append("duration: ").append(String.format(Locale.US, "%.3f", t.durationSec.toDouble())).append('\n') }
            if (t.artist.isNotBlank()) out.append("Artist: ").append(t.artist).append('\n')
            if (t.album.isNotBlank()) out.append("Album: ").append(t.album).append('\n')
            out.append("Title: ").append(t.title).append('\n')
            if (t.trackNo > 0) out.append("Track: ").append(t.trackNo).append('\n')
            if (t.genre.isNotBlank()) out.append("Genre: ").append(t.genre).append('\n')
        }
    }

    private fun writeCount(args: List<String>, out: StringBuilder) {
        val stripped = if (args.firstOrNull() == "--") args.drop(1) else args
        val nodes = if (stripped.isEmpty()) emptyList() else if (stripped[0].startsWith("(")) listOf(parseFilterExpr(stripped.joinToString(" "))) else parseOldFilter(stripped, substring = false)
        val results = if (nodes.isEmpty()) library.all() else library.all().filter { t -> nodes.all { library.matches(t, it) } }
        out.append("songs: ").append(results.size).append('\n')
        out.append("playtime: ").append(results.sumOf { it.durationSec.toLong() }).append('\n')
    }

    // ── Stored playlists ──────────────────────────────────────────────────

    private fun playlistFile(name: String): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        if (safe.contains("..") || safe.isBlank()) throw MpdAck(ACK_ARG, "invalid playlist name")
        val withExt = if (safe.lowercase().endsWith(".m3u") || safe.lowercase().endsWith(".m3u8")) safe else "$safe.m3u"
        return File(playlistDir, withExt)
    }

    private fun writeListPlaylists(out: StringBuilder) {
        val byName = LinkedHashMap<String, Long>()
        cachedPlaylists().forEach { (name, f) -> byName[name] = f.lastModified() }
        byName.toSortedMap().forEach { (name, ts) ->
            out.append("playlist: ").append(name).append('\n')
            out.append("Last-Modified: ").append(iso8601(ts)).append('\n')
        }
    }

    // Discovered playlists: the dedicated Playlists dir plus any playlist files
    // found across the registered library folders. Cached briefly so listplaylists
    // stays responsive on large libraries.
    private val playlistCacheLock = Any()
    private var playlistCache: Map<String, File>? = null
    private var playlistCacheAt = 0L

    private fun cachedPlaylists(): Map<String, File> = synchronized(playlistCacheLock) {
        val now = System.currentTimeMillis()
        if (playlistCache == null || now - playlistCacheAt > 10_000L) {
            val map = LinkedHashMap<String, File>()
            if (playlistDir.isDirectory) {
                playlistDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        val lc = f.name.lowercase()
                        if (lc.endsWith(".m3u") || lc.endsWith(".m3u8") || lc.endsWith(".pls") || lc.endsWith(".cue")) {
                            if (!map.containsKey(f.nameWithoutExtension)) map[f.nameWithoutExtension] = f
                        }
                    }
                }
            }
            library.findPlaylistFiles().forEach { (k, v) -> if (!map.containsKey(k)) map[k] = v }
            playlistCache = map
            playlistCacheAt = now
        }
        playlistCache ?: emptyMap()
    }

    private fun invalidatePlaylistCache() = synchronized(playlistCacheLock) { playlistCache = null }

    private fun findPlaylist(name: String): File? {
        if (name.isBlank()) return null
        File(name).takeIf { it.exists() }?.let { return it }
        cachedPlaylists()[name]?.let { return it }
        return cachedPlaylists().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun writeListPlaylist(args: List<String>, out: StringBuilder, withInfo: Boolean) {
        if (args.isEmpty()) throw MpdAck(ACK_ARG, "need playlist name")
        val f = findPlaylist(args[0]) ?: throw MpdAck(ACK_NO_EXIST, "No such playlist")
        val lines = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (line in lines) {
            out.append("file: ").append(line).append('\n')
            if (withInfo) {
                val lib = library.lookup(line)
                if (lib != null) { out.append("Title: ").append(lib.title).append('\n'); if (lib.artist.isNotBlank()) out.append("Artist: ").append(lib.artist).append('\n') }
            }
        }
    }

    private fun loadStoredPlaylist(name: String): List<MediaItem> {
        val f = findPlaylist(name) ?: throw MpdAck(ACK_NO_EXIST, "No such playlist")
        return parseM3uFile(f)
    }

    private fun saveStoredPlaylist(name: String) {
        val f = playlistFile(name)
        f.parentFile?.mkdirs()
        val items = hop { c -> (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId } }
        f.writeText(buildString {
            append("#EXTM3U\n")
            for (uri in items) {
                // store absolute path for file:// uris
                val line = if (uri.startsWith("file://")) Uri.decode(uri.removePrefix("file://")) else uri
                append(line).append('\n')
            }
        })
        invalidatePlaylistCache()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun expandUri(mpdUri: String): List<MediaItem> {
        val u = mpdUri.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return listOf(streamItem(u))
        if (u.startsWith("content://") || u.startsWith("smb://")) return listOf(genericItem(u))
        // try stored playlist name first
        findPlaylist(u)?.let { return parseM3uFile(it) }
        val f = try { resolveLocalPath(u) } catch (e: MpdAck) {
            // fallback: maybe absolute path not under roots but still exists (e.g. /storage/emulated/0/...)
            val alt = File(Uri.decode(u))
            if (alt.exists()) alt else throw e
        }
        if (!f.exists()) throw MpdAck(ACK_NO_EXIST, "No such file or directory")
        return when {
            f.isDirectory -> collectDir(f)
            f.name.lowercase().endsWith(".m3u") || f.name.lowercase().endsWith(".m3u8") -> parseM3uFile(f)
            f.name.lowercase().endsWith(".pls") -> parsePlsFile(f)
            f.name.lowercase().endsWith(".cue") -> parseCueFile(f)
            MpdLibrary.isAudioFile(f.name) -> listOf(fileToMediaItem(f, library.lookup(f.absolutePath)))
            else -> throw MpdAck(ACK_NO_EXIST, "Not a playable file")
        }
    }

    private fun collectDir(dir: File): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        fun walk(d: File) {
            val kids = d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (c in kids) {
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) walk(c)
                else if (MpdLibrary.isAudioFile(c.name)) out.add(fileToMediaItem(c, library.lookup(c.absolutePath)))
                else if (c.name.lowercase().endsWith(".m3u") || c.name.lowercase().endsWith(".m3u8")) out.addAll(parseM3uFile(c))
            }
        }
        walk(dir)
        return out
    }

    private fun fileToMediaItem(f: File, lib: MpdLibrary.Track?): MediaItem {
        val uri = Uri.fromFile(f).toString()
        val title = lib?.title ?: f.nameWithoutExtension
        val artist = lib?.artist ?: ""
        return MediaItem.Builder().setMediaId(uri).setUri(uri).setMimeType(mimeFor(uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).apply { if (artist.isNotBlank()) setArtist(artist); if (!lib?.album.isNullOrBlank()) setAlbumTitle(lib.album) }.build()).build()
    }

    private fun streamItem(url: String): MediaItem =
        MediaItem.Builder().setMediaId(url).setUri(url).setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(url.substringAfterLast('/')).build()).build()

    private fun genericItem(uri: String): MediaItem =
        MediaItem.Builder().setMediaId(uri).setUri(uri).setMimeType(mimeFor(uri))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(uri.substringAfterLast('/')).build()).build()

    private fun mimeFor(uri: String): String? {
        val l = uri.lowercase()
        return when {
            l.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
            l.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            l.endsWith(".wav") -> MimeTypes.AUDIO_WAV
            l.endsWith(".m4a") || l.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            l.endsWith(".ogg") -> MimeTypes.AUDIO_OGG
            l.endsWith(".ape") -> "audio/x-ape"
            else -> null
        }
    }

    private fun mpdPath(mediaId: String): String = when {
        mediaId.startsWith("file://") -> Uri.decode(mediaId.removePrefix("file://"))
        else -> mediaId
    }

    private fun iso8601(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }

    private fun pcmBits(fmt: Format): Int? = when (fmt.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32; else -> null
    }

    private fun findAudioFormat(c: MediaController): Format? {
        for (g in c.currentTracks.groups) if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected) {
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        }
        return null
    }

    private fun parseRange(s: String): Pair<Int, Int> {
        val t = s.trim().removePrefix("[").removeSuffix("]")
        return if (":" in t) {
            val (a, b) = t.split(":", limit = 2)
            val from = a.toIntOrNull() ?: 0
            val until = b.toIntOrNull() ?: Int.MAX_VALUE
            from to until
        } else { val p = t.toIntOrNull() ?: throw MpdAck(ACK_ARG, "bad range"); p to p + 1 }
    }

    private fun parseWindow(s: String): Pair<Int, Int>? {
        if (!s.startsWith("window")) return null
        val inner = s.substringAfter("\"").substringBefore("\"")
        return parseRange(inner)
    }

    // ── Filter parsing ────────────────────────────────────────────────────

    private fun parseOldFilter(args: List<String>, substring: Boolean): List<MpdLibrary.FNode> {
        if (args.size % 2 != 0) throw MpdAck(ACK_ARG, "wrong number of filter arguments")
        return args.chunked(2).map { (type, value) ->
            val tag = type.lowercase()
            when (tag) {
                "base" -> MpdLibrary.FTag("base", "prefix", value)
                "any" -> MpdLibrary.FTag("any", if (substring) "contains" else "eq", value)
                else -> MpdLibrary.FTag(tag, if (substring) "contains" else "eq", value)
            }
        }
    }

    private fun parseFilterExpr(expr: String): MpdLibrary.FNode {
        // Very small parser for "(tag == \"val\")" with AND/OR/NOT and parens
        val s = expr.trim()
        return FilterExprParser(s).parse()
    }

    private class FilterExprParser(private val s: String) {
        private var pos = 0
        private val toks = mutableListOf<Pair<String, String>>()
        init { tokenize() }
        private fun tokenize() {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch.isWhitespace() -> i++
                    ch == '(' -> { toks.add("LP" to "("); i++ }
                    ch == ')' -> { toks.add("RP" to ")"); i++ }
                    s.startsWith("==", i) -> { toks.add("OP" to "=="); i += 2 }
                    s.startsWith("!=", i) -> { toks.add("OP" to "!="); i += 2 }
                    s.startsWith("=~", i) -> { toks.add("OP" to "=~"); i += 2 }
                    s.startsWith("!~", i) -> { toks.add("OP" to "!~"); i += 2 }
                    ch == '"' -> {
                        i++; val sb = StringBuilder()
                        while (i < s.length && s[i] != '"') {
                            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
                        }
                        i++ // closing "
                        toks.add("STR" to sb.toString())
                    }
                    else -> {
                        val start = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '(' && s[i] != ')' && s[i] != '"') {
                            if (s.startsWith("==", i) || s.startsWith("!=", i) || s.startsWith("=~", i) || s.startsWith("!~", i)) break
                            i++
                        }
                        val w = s.substring(start, i)
                        val up = w.uppercase()
                        when (up) {
                            "AND", "OR", "NOT" -> toks.add(up to w)
                            "CONTAINS" -> toks.add("OP" to "contains")
                            else -> toks.add("WORD" to w)
                        }
                    }
                }
            }
        }
        private var idx = 0
        private fun peek() = toks.getOrNull(idx)
        private fun peekType() = peek()?.first
        private fun peekWord(w: String) = peek()?.let { it.first == w || (it.first == "WORD" && it.second.equals(w, true)) } == true
        private fun next() = toks[idx++]
        private fun expect(type: String): String {
            val t = toks.getOrNull(idx) ?: throw MpdAck(ACK_ARG, "unexpected end of filter")
            if (t.first != type) throw MpdAck(ACK_ARG, "expected $type got ${t.second}")
            idx++; return t.second
        }
        fun parse(): MpdLibrary.FNode {
            val n = parseOr()
            if (idx != toks.size) throw MpdAck(ACK_ARG, "unexpected token in filter")
            return n
        }
        private fun parseOr(): MpdLibrary.FNode {
            var left = parseAnd()
            while (idx < toks.size && peekWord("OR")) { next(); val r = parseAnd(); left = MpdLibrary.FOr(listOf(left, r)) }
            return left
        }
        private fun parseAnd(): MpdLibrary.FNode {
            var left = parseUnary()
            while (idx < toks.size && peekWord("AND")) { next(); val r = parseUnary(); left = MpdLibrary.FAnd(listOf(left, r)) }
            return left
        }
        private fun parseUnary(): MpdLibrary.FNode {
            if (peekWord("NOT") || peek()?.first == "NOT") { next(); return MpdLibrary.FNot(parseUnary()) }
            if (peekType() == "LP") {
                // lookahead for comparison: ( WORD OP STR )
                if (idx + 3 < toks.size && toks[idx + 1].first == "WORD" && (toks[idx + 2].first == "OP")) {
                    next() // LP
                    val tag = expect("WORD")
                    val opRaw = expect("OP")
                    val v = expect("STR")
                    val rp = next(); if (rp.first != "RP") throw MpdAck(ACK_ARG, "expected )")
                    val op = when (opRaw) { "==" -> "eq"; "!=" -> "ne"; "contains" -> "contains"; "=~" -> "regex"; "!~" -> "notregex"; else -> "eq" }
                    return MpdLibrary.FTag(tag, op, v)
                }
                next(); val inner = parseOr(); val rp = next(); if (rp.first != "RP") throw MpdAck(ACK_ARG, "expected )"); return inner
            }
            throw MpdAck(ACK_ARG, "bad filter")
        }
    }

    // ── Playlist file parsers ─────────────────────────────────────────────

    private fun parseM3uFile(f: File): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        try {
            FileInputStream(f).bufferedReader().use { br ->
                var title: String? = null
                for (raw in br.lineSequence()) {
                    var line = raw.trim().removePrefix("\uFEFF")
                    if (line.isEmpty()) continue
                    if (line.startsWith("#EXTINF:")) { val c = line.indexOf(','); if (c >= 0) title = line.substring(c + 1).trim() }
                    else if (!line.startsWith("#")) {
                        val norm = line.replace('\\', '/')
                        val resolved = if (norm.startsWith("/") || norm.contains("://")) norm else File(f.parentFile, norm).absolutePath
                        val file = File(resolved)
                        if (file.exists() && MpdLibrary.isAudioFile(file.name)) {
                            val lib = library.lookup(file.absolutePath)
                            out.add(fileToMediaItem(file, lib))
                        } else if (resolved.startsWith("http")) out.add(streamItem(resolved))
                        title = null
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parsePlsFile(f: File): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        try {
            val props = mutableMapOf<String, String>()
            FileInputStream(f).bufferedReader().use { br ->
                for (raw in br.lineSequence()) {
                    var line = raw.trim().removePrefix("\uFEFF")
                    if (line.isEmpty() || line.startsWith("[")) continue
                    val eq = line.indexOf('='); if (eq < 0) continue
                    props[line.substring(0, eq).trim().lowercase()] = line.substring(eq + 1).trim()
                }
            }
            val n = props["numberofentries"]?.toIntOrNull() ?: 0
            for (i in 1..n) {
                val file = props["file$i"] ?: continue
                val norm = file.replace('\\', '/')
                val resolved = if (norm.startsWith("/") || norm.contains("://")) norm else File(f.parentFile, norm).absolutePath
                val fileObj = File(resolved)
                if (fileObj.exists() && MpdLibrary.isAudioFile(fileObj.name)) out.add(fileToMediaItem(fileObj, library.lookup(fileObj.absolutePath)))
                else if (resolved.startsWith("http")) out.add(streamItem(resolved))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseCueFile(f: File): List<MediaItem> {
        // Minimal cue: expand to single file item with clipping per track if possible
        // For MPD we flatten to one entry per track using same audio file with start/end
        try {
            var audioFile: String? = null
            data class CueTrack(var title: String? = null, var startMs: Long = 0)
            val tracks = mutableListOf<CueTrack>()
            var cur: CueTrack? = null
            FileInputStream(f).bufferedReader().use { br ->
                for (raw in br.lineSequence()) {
                    val line = raw.trim().removePrefix("\uFEFF")
                    val up = line.uppercase()
                    when {
                        up.startsWith("FILE") -> audioFile = line.substringAfter('"').substringBeforeLast('"')
                        up.startsWith("TRACK") -> { cur = CueTrack(); tracks.add(cur) }
                        up.startsWith("TITLE") && cur != null -> cur.title = line.substringAfter('"').substringBeforeLast('"')
                        up.startsWith("INDEX 01") && cur != null -> {
                            val t = line.substringAfter("INDEX 01").trim()
                            val parts = t.split(":")
                            if (parts.size == 3) {
                                val m = parts[0].toLongOrNull() ?: 0; val s = parts[1].toLongOrNull() ?: 0; val fr = parts[2].toLongOrNull() ?: 0
                                cur.startMs = m * 60 * 1000 + s * 1000 + fr * 1000 / 75
                            }
                        }
                    }
                }
            }
            if (audioFile != null && tracks.isNotEmpty()) {
                val audioPath = if (audioFile!!.startsWith("/") || audioFile!!.contains("://")) audioFile!! else File(f.parentFile, audioFile!!).absolutePath
                val audioUri = Uri.fromFile(File(audioPath)).toString()
                return tracks.mapIndexed { idx, tr ->
                    val next = tracks.getOrNull(idx + 1)?.startMs ?: C.TIME_UNSET
                    MediaItem.Builder().setMediaId("${audioUri}_${idx + 1}").setUri(audioUri).setMimeType(mimeFor(audioUri))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(tr.title ?: "Track ${idx + 1}").build())
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(tr.startMs).apply { if (next != C.TIME_UNSET) setEndPositionMs(next) }.build()).build()
                }
            }
        } catch (_: Exception) {}
        return emptyList()
    }
}
