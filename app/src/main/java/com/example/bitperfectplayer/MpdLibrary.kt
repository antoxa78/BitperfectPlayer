package com.example.bitperfectplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * Lightweight in-memory music database for the embedded MPD server.
 * Scans registered local folders (file://) plus fallback primary storage.
 * Tags are extracted via MediaMetadataRetriever; results are queryable
 * with a small filter-expression engine compatible with MALP.
 */
class MpdLibrary(private val context: Context) {

    companion object {
        private const val TAG = "MpdLibrary"
        private val AUDIO_EXT = listOf(
            ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".wma", ".ape", ".opus", ".iso"
        )
        fun isAudioFile(name: String): Boolean = AUDIO_EXT.any { name.lowercase().endsWith(it) }
    }

    data class Track(
        val path: String,
        val title: String,
        val artist: String,
        val albumArtist: String,
        val album: String,
        val genre: String,
        val trackNo: Int,
        val durationSec: Int,
        val lastModified: Long
    )

    // ── Filter AST ─────────────────────────────────────────────────────────
    sealed class FNode
    data class FTag(val tag: String, val op: String, val value: String) : FNode()
    data class FAnd(val parts: List<FNode>) : FNode()
    data class FOr(val parts: List<FNode>) : FNode()
    data class FNot(val inner: FNode) : FNode()

    private val appContext = context.applicationContext
    private val lock = Any()

    private var tracks: List<Track> = emptyList()
    private var byPath: Map<String, Track> = emptyMap()
    private var lastUpdate: Long = 0L

    @Volatile var scanning: Boolean = false
        private set
    @Volatile private var cancelRequested = false
    private var jobId: Int = 0

    /** Called on background thread when a scan finishes. */
    var onScanFinished: (() -> Unit)? = null

    // ── Public queries ──────────────────────────────────────────────────────

    fun all(): List<Track> = synchronized(lock) { tracks }
    fun lookup(path: String): Track? = synchronized(lock) { byPath[path] }
    fun lastUpdated(): Long = synchronized(lock) { lastUpdate }
    fun currentJob(): Int = synchronized(lock) { jobId }
    fun cancel() { cancelRequested = true }

    fun counts(): Triple<Int, Int, Int> = synchronized(lock) {
        val artists = tracks.map { it.artist.ifBlank { "Unknown Artist" } }
            .distinctBy { it.lowercase() }.size
        val albums = tracks.map { it.album.ifBlank { "Unknown Album" } }
            .distinctBy { it.lowercase() }.size
        Triple(artists, albums, tracks.size)
    }

    fun totalPlaytimeSec(): Long = synchronized(lock) { tracks.sumOf { it.durationSec.toLong() } }

    fun artists(): List<String> = synchronized(lock) {
        tracks.map { it.artist.ifBlank { "Unknown Artist" } }
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy { it.lowercase() })
    }

    fun albumArtists(): List<String> = synchronized(lock) {
        tracks.map { (it.albumArtist.ifBlank { it.artist }).ifBlank { "Unknown Artist" } }
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy { it.lowercase() })
    }

    fun albumsFor(artistFilter: String?): List<String> = synchronized(lock) {
        var seq = tracks.asSequence()
        if (!artistFilter.isNullOrBlank()) {
            val af = artistFilter.lowercase()
            seq = seq.filter {
                it.artist.equals(af, ignoreCase = true) ||
                    it.albumArtist.equals(af, ignoreCase = true)
            }
        }
        seq.map { it.album.ifBlank { "Unknown Album" } }
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy { it.lowercase() })
            .toList()
    }

    fun genres(): List<String> = synchronized(lock) {
        tracks.mapNotNull { it.genre.takeIf { g -> g.isNotBlank() } }
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy { it.lowercase() })
    }

    fun query(node: FNode): List<Track> = synchronized(lock) {
        tracks.filter { matches(it, node) }
    }

    // ── Matching ────────────────────────────────────────────────────────────

    fun matches(t: Track, node: FNode): Boolean = when (node) {
        is FAnd -> node.parts.all { matches(t, it) }
        is FOr -> node.parts.any { matches(t, it) }
        is FNot -> !matches(t, node.inner)
        is FTag -> {
            val field = fieldFor(t, node.tag)
            when (node.op) {
                "eq" -> field.equals(node.value, ignoreCase = true)
                "ne" -> !field.equals(node.value, ignoreCase = true)
                "contains" -> field.contains(node.value, ignoreCase = true)
                "regex" -> try {
                    Regex(node.value, RegexOption.IGNORE_CASE).containsMatchIn(field)
                } catch (_: Exception) { false }
                "notregex" -> try {
                    !Regex(node.value, RegexOption.IGNORE_CASE).containsMatchIn(field)
                } catch (_: Exception) { true }
                "prefix" -> field.trimEnd('/').startsWith(node.value.trimEnd('/'), ignoreCase = true)
                else -> false
            }
        }
    }

    private fun fieldFor(t: Track, tag: String): String = when (tag.lowercase()) {
        "artist" -> t.artist
        "albumartist" -> t.albumArtist.ifBlank { t.artist }
        "album" -> t.album
        "title" -> t.title
        "genre" -> t.genre
        "track" -> t.trackNo.toString()
        "file" -> t.path
        "base" -> t.path
        "any" -> "${t.title}\n${t.artist}\n${t.albumArtist}\n${t.album}\n${t.genre}\n${t.path}"
        else -> ""
    }

    // ── Scan ────────────────────────────────────────────────────────────────

    fun startUpdate(): Int {
        synchronized(lock) {
            if (scanning) throw MpdServer.MpdAck(54, "already updating database")
            jobId += 1
            scanning = true
            cancelRequested = false
        }
        val j = synchronized(lock) { jobId }
        Thread({ doScan(j) }, "mpd-db-scan").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
        return j
    }

    private fun doScan(job: Int) {
        try {
            val files = ArrayList<File>()
            for (root in libraryRoots()) {
                if (cancelRequested) return
                walk(root, files)
            }
            val list = ArrayList<Track>(files.size)
            for (f in files) {
                if (cancelRequested) {
                    Log.i(TAG, "Scan cancelled ($job)")
                    return
                }
                list.add(extract(f))
            }
            synchronized(lock) {
                tracks = list.sortedBy { it.path.lowercase() }
                byPath = tracks.associateBy { it.path }
                lastUpdate = System.currentTimeMillis() / 1000L
            }
            Log.i(TAG, "MPD database scan finished: ${list.size} tracks (job $job)")
            onScanFinished?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
        } finally {
            scanning = false
        }
    }

    private fun libraryRoots(): List<File> {
        val out = LinkedHashSet<File>()
        try {
            val prefs = appContext.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            val raw = prefs.getString("music_folders", "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val uriStr = arr.getJSONObject(i).optString("uri", "")
                if (uriStr.startsWith("file://")) {
                    try {
                        val decoded = Uri.decode(uriStr.removePrefix("file://"))
                        val f = File(decoded)
                        if (f.isDirectory && f.canRead()) out.add(f)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read music_folders", e)
        }
        if (out.isEmpty()) {
            val primary = android.os.Environment.getExternalStorageDirectory()
            if (primary != null && primary.isDirectory) out.add(primary)
        }
        return out.toList()
    }

    private fun walk(dir: File, out: MutableList<File>) {
        if (cancelRequested) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        for (c in children) {
            if (cancelRequested) return
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) walk(c, out)
            else if (isAudioFile(c.name)) out.add(c)
        }
    }

    /**
     * Finds playlist files (.m3u/.m3u8/.pls/.cue) across the registered library
     * roots. Returns name-without-extension -> file; first match wins.
     */
    fun findPlaylistFiles(): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        try {
            for (root in libraryRoots()) walkPlaylists(root, out)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find playlist files", e)
        }
        return out
    }

    private fun walkPlaylists(dir: File, out: MutableMap<String, File>) {
        if (cancelRequested) return
        val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
        for (c in children) {
            if (cancelRequested) return
            if (c.name.startsWith(".")) continue
            if (c.isDirectory) walkPlaylists(c, out)
            else {
                val lc = c.name.lowercase()
                if (lc.endsWith(".m3u") || lc.endsWith(".m3u8") || lc.endsWith(".pls") || lc.endsWith(".cue")) {
                    val key = c.nameWithoutExtension
                    if (!out.containsKey(key)) out[key] = c
                }
            }
        }
    }

    private fun extract(f: File): Track {
        val fallbackTitle = f.nameWithoutExtension
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: ""
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            val trackNo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore("/")?.trim()?.toIntOrNull() ?: 0
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Track(
                path = f.absolutePath,
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = genre,
                trackNo = trackNo,
                durationSec = (durMs / 1000L).toInt(),
                lastModified = f.lastModified()
            )
        } catch (_: Exception) {
            Track(f.absolutePath, fallbackTitle, "", "", "", "", 0, 0, f.lastModified())
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
