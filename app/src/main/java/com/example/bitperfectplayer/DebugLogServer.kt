package com.example.bitperfectplayer

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Serves [DebugLog] over the LAN as plain HTTP, so the log is readable from a
 * phone or a PC while the USB DAC stays connected.
 *
 * This exists because enabling USB debugging on a Xiaomi TV Box (and several
 * other Android TV boxes) flips the shared USB port into device mode and
 * removes the DAC from the bus — so there is no moment at which adb and the
 * DAC are both available. A network reader is the only way to watch the log
 * during a USB-audio session.
 *
 * Endpoints:
 *  - `/`        auto-refreshing HTML viewer
 *  - `/log`     plain text (`?lines=N` to limit)
 *  - `/clear`   empties the buffer and deletes the on-disk copy
 *  - `/status`  capture state / file path
 *
 * The listener is opt-in (`Settings → Debug Log → Share over Wi-Fi`), bound to
 * the configured port on all interfaces, and every line it serves has already
 * had MPD passwords and SMB credentials scrubbed by [DebugLog].
 */
object DebugLogServer {

    private const val TAG = "DebugLogServer"
    const val DEFAULT_PORT = 6790

    private const val PREFS = "AppSettings"
    private const val KEY_ENABLED = "debug_log_server"
    private const val KEY_PORT = "debug_log_port"

    @Volatile private var running = false
    @Volatile private var boundPort = 0
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var appContext: Context? = null

    // ── Configuration ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun getConfiguredPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)

    /** Port actually bound, or 0 while the server is down. */
    fun getPort(): Int = boundPort

    fun isRunning(): Boolean = running

    /** Persists the toggle and starts/stops the listener to match. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        apply(ctx)
    }

    /** Applies the persisted toggle. Safe to call from process start. */
    fun apply(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (isEnabled(ctx)) start(ctx) else stop()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        val port = getConfiguredPort(context.applicationContext)
        running = true
        acceptThread = Thread({ acceptLoop(port) }, "debuglog-http").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        boundPort = 0
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(port: Int) {
        var bound: ServerSocket? = null
        for (attempt in 1..3) {
            if (!running) return
            try {
                bound = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "bind attempt $attempt failed on $port: ${e.message}")
                try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
            }
        }
        val ss = bound
        if (ss == null || !running) { running = false; return }
        serverSocket = ss
        boundPort = ss.localPort
        Log.i(TAG, "Debug log server listening on $boundPort")
        while (running) {
            val client = try { ss.accept() } catch (_: Exception) { if (running) continue else break }
            try { handle(client) } catch (t: Throwable) { Log.d(TAG, "request failed: ${t.message}") }
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readLine(input) ?: return
            // Drain headers (bounded) so the client doesn't see a reset.
            var headers = 0
            while (headers++ < 50) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            when (path) {
                "/" -> respond(out, "200 OK", "text/html; charset=utf-8", page().toByteArray(StandardCharsets.UTF_8))
                "/log" -> {
                    val lines = parseLines(query)
                    val body = if (query.contains("file=1")) {
                        val ctx = appContext
                        if (ctx == null) "no context" else DebugLog.fileText(ctx, lines)
                    } else DebugLog.text(lines)
                    respondText(out, body)
                }
                "/clear" -> { DebugLog.clear(); respondText(out, "cleared\n") }
                "/status" -> respondText(out, statusText())
                "/favicon.ico" -> respond(out, "204 No Content", "text/plain", ByteArray(0))
                else -> respondText(out, "not found\n", "404 Not Found")
            }
            out.flush()
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun parseLines(query: String): Int {
        val raw = query.split('&').firstOrNull { it.startsWith("lines=") }
            ?.substringAfter('=')?.toIntOrNull() ?: DebugLog.MAX_LINES
        return raw.coerceIn(1, DebugLog.MAX_LINES)
    }

    private fun statusText(): String = buildString {
        val ctx = appContext
        append("capturing: ${DebugLog.isCapturing()}\n")
        append("state: ${DebugLog.status()}\n")
        append("lines: ${DebugLog.lineCount()}\n")
        append("pid: ${android.os.Process.myPid()}\n")
        append("port: ${boundPort}\n")
        if (ctx != null) append("file: ${DebugLog.logFile(ctx)?.absolutePath ?: "unavailable"}\n")
    }

    private fun page(): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Bitperfect Player - Debug Log</title>
        <style>
          body { margin:0; background:#111; color:#ddd; font:13px/1.45 ui-monospace,Menlo,Consolas,monospace; }
          header { position:sticky; top:0; background:#1c1c1c; border-bottom:1px solid #333;
                   padding:10px 14px; display:flex; gap:16px; align-items:center; flex-wrap:wrap; }
          header strong { color:#fff; font-weight:600; }
          header span { color:#888; }
          button { background:#2a2a2a; color:#ddd; border:1px solid #555; border-radius:6px;
                   padding:5px 12px; font:inherit; cursor:pointer; }
          pre { margin:0; padding:14px; white-space:pre-wrap; word-break:break-all; }
        </style></head>
        <body>
        <header>
          <strong>Debug Log</strong>
          <span id="meta">loading...</span>
          <button onclick="fetch('/clear').then(tick)">Clear</button>
        </header>
        <pre id="log"></pre>
        <script>
          const meta = document.getElementById('meta'), log = document.getElementById('log');
          async function tick() {
            try {
              const atBottom = window.scrollY + window.innerHeight >= document.body.scrollHeight - 40;
              const res = await fetch('/log?lines=1500', {cache: 'no-store'});
              log.textContent = await res.text();
              const st = await fetch('/status', {cache: 'no-store'});
              meta.textContent = (await st.text()).replace(/\n/g, '   ');
              if (atBottom) window.scrollTo(0, document.body.scrollHeight);
            } catch (e) { meta.textContent = 'connection lost - retrying'; }
          }
          tick(); setInterval(tick, 2000);
        </script>
        </body></html>
    """.trimIndent()

    // ── Wire helpers ────────────────────────────────────────────────────────

    private fun respondText(out: OutputStream, body: String, status: String = "200 OK") =
        respond(out, status, "text/plain; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))

    private fun respond(out: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }
}
