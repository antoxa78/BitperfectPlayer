package com.example.bitperfectplayer

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Playlist parsing (.m3u / .m3u8 / .pls / .cue) shared by the TV UI and the
 * embedded MPD server.
 *
 * These used to live as instance methods on MainActivity, which made them
 * unreachable from [MpdServer] (it only holds a Context, never an Activity) —
 * so remote playlists on SMB shares were advertised by `lsinfo` but could not
 * be loaded. Keeping them here lets both callers parse a stream from any
 * source, including an [jcifs.smb.SmbFile] InputStream.
 *
 * All entry resolution goes through [resolveRelativePath] + [parseEntryUri],
 * so the base path decides the result: a local directory yields `file://`
 * entries, an `smb://` base yields authenticated `smb://` entries that stream
 * through [SmbDataSource].
 */
object PlaylistParser {

    /** Extensions treated as playlist files (matches the browsable/expandable set). */
    fun isPlaylistName(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".m3u") || l.endsWith(".m3u8") || l.endsWith(".pls") || l.endsWith(".cue")
    }

    /**
     * Parses [inputStream] as the playlist type implied by [name].
     * Returns an empty list for names that aren't playlists, and for playlists
     * that parsed to nothing (the caller decides whether to fall back to
     * queueing the playlist file itself).
     */
    fun parsePlaylistStream(name: String, inputStream: InputStream, basePath: String? = null): List<MediaItem> {
        val l = name.lowercase()
        return when {
            l.endsWith(".m3u") || l.endsWith(".m3u8") -> parseM3uFromStream(inputStream, basePath)
            l.endsWith(".pls")                         -> parsePlsFromStream(inputStream, basePath)
            l.endsWith(".cue")                         -> parseCueFromStream(inputStream, basePath)
            else                                       -> emptyList()
        }
    }

    fun parseM3uFromStream(inputStream: InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentTitle: String? = null

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim()?.removePrefix("\uFEFF") ?: continue
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXTINF:")) {
                    // Attributes (tvg-id=..., tvg-logo=..., group-title=...) sit between
                    // the duration and the actual title, so the title starts after the
                    // LAST comma — indexOf(',') would pollute it with the attributes.
                    val comma = trimmed.lastIndexOf(',')
                    if (comma != -1) currentTitle = trimmed.substring(comma + 1).trim()
                } else if (!trimmed.startsWith("#")) {
                    // Normalise Windows path separators
                    val normalizedPath = trimmed.replace("\\", "/")
                    val itemUriString  = resolveRelativePath(normalizedPath, basePath)
                    val itemUri        = parseEntryUri(itemUriString, basePath) ?: run { currentTitle = null; continue }

                    val metaBuilder = MediaMetadata.Builder()
                    var finalTitle  = currentTitle ?: itemUri.lastPathSegment ?: trimmed
                    if (finalTitle.contains(" - ")) {
                        val parts = finalTitle.split(" - ", limit = 2)
                        metaBuilder.setArtist(parts[0].trim())
                        finalTitle = parts[1].trim()
                    }

                    items.add(
                        MediaItem.Builder()
                            .setMediaId(itemUri.toString())
                            .setUri(itemUri)
                            .setMimeType(mimeTypeFor(itemUri.toString()))
                            .setMediaMetadata(metaBuilder.setTitle(finalTitle).build())
                            .build()
                    )
                    currentTitle = null
                }
            }
        } catch (e: Exception) { Log.w("PlaylistParser", "m3u parse failed", e) }
        return items
    }

    fun parsePlsFromStream(inputStream: InputStream, basePath: String? = null): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            val props  = linkedMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim()?.removePrefix("\uFEFF") ?: continue
                if (trimmed.isEmpty() || trimmed.startsWith("[")) continue
                val eq = trimmed.indexOf('=')
                if (eq != -1) props[trimmed.substring(0, eq).trim().lowercase()] = trimmed.substring(eq + 1).trim()
            }

            val count = props.remove("numberofentries")?.toIntOrNull() ?: 0
            for (i in 1..count) {
                val file = props.remove("file$i") ?: continue
                val normalizedPath = file.replace("\\", "/")
                val itemUriString  = resolveRelativePath(normalizedPath, basePath)
                val itemUri        = parseEntryUri(itemUriString, basePath) ?: continue

                var finalTitle = props.remove("title$i")
                    ?: itemUri.lastPathSegment
                    ?: itemUriString.substringAfterLast("/").substringBeforeLast(".")
                props.remove("length$i") // consume but ignore

                val metaBuilder = MediaMetadata.Builder()
                if (finalTitle.contains(" - ")) {
                    val parts = finalTitle.split(" - ", limit = 2)
                    metaBuilder.setArtist(parts[0].trim())
                    finalTitle = parts[1].trim()
                }
                items.add(
                    MediaItem.Builder()
                        .setMediaId(itemUri.toString())
                        .setUri(itemUri)
                        .setMimeType(mimeTypeFor(itemUri.toString()))
                        .setMediaMetadata(metaBuilder.setTitle(finalTitle).build())
                        .build()
                )
            }
        } catch (e: Exception) { Log.w("PlaylistParser", "pls parse failed", e) }
        return items
    }

    /** CUE sheets are a few KB; anything far larger is not a CUE sheet. */
    private const val MAX_CUE_BYTES = 1 shl 20

    /**
     * Expands a CUE sheet into one clipped [MediaItem] per audio track.
     * Parsing and text decoding live in [CueSheet]; this resolves each track's
     * FILE against [basePath] (local dir, SAF document URI or smb:// dir) and
     * builds the items. Tracks of sheets with several FILE lines point at their
     * own file; a track whose file cannot be resolved is skipped.
     */
    fun parseCueFromStream(inputStream: InputStream, basePath: String?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val bytes = readCapped(inputStream, MAX_CUE_BYTES)
            if (bytes == null) {
                Log.w("PlaylistParser", "cue skipped: larger than $MAX_CUE_BYTES bytes")
                return items
            }
            val sheet = CueSheet.parse(CueSheet.decode(bytes))
            val resolved = HashMap<String, Uri?>()

            for (track in sheet.tracks) {
                val audioUri = resolved.getOrPut(track.file) {
                    parseEntryUri(resolveRelativePath(track.file, basePath), basePath)
                } ?: continue

                val meta = MediaMetadata.Builder()
                    .setTitle(track.title ?: "Track ${track.number}")
                    .setArtist(track.performer ?: sheet.performer ?: "Unknown Artist")
                    .setAlbumTitle(sheet.title ?: "Unknown Album")
                    .setTrackNumber(track.number)
                sheet.performer?.let { meta.setAlbumArtist(it) }

                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(track.startMs)
                track.endMs?.let { clipping.setEndPositionMs(it) }

                items.add(
                    MediaItem.Builder()
                        .setMediaId("${audioUri}_${track.number}")
                        .setUri(audioUri)
                        .setMimeType(mimeTypeFor(audioUri.toString()))
                        .setMediaMetadata(meta.build())
                        .setClippingConfiguration(clipping.build())
                        .build()
                )
            }
        } catch (e: Exception) { Log.w("PlaylistParser", "cue parse failed", e) }
        return items
    }

    /** Reads at most [limit] bytes; null if the stream is longer than that. */
    private fun readCapped(input: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /**
     * Resolves a relative playlist entry path against [basePath], handling
     * regular filesystem paths, SAF (Storage Access Framework) URIs and
     * `smb://` share paths.
     */
    private fun resolveRelativePath(path: String, basePath: String?): String {
        if (basePath == null || path.contains("://") || path.startsWith("/")) return path
        return when {
            basePath.contains("%2F") && !basePath.startsWith("file://") -> {
                val encoded = Uri.encode(path).replace("/", "%2F")
                if (basePath.endsWith("%2F")) "$basePath$encoded" else "$basePath%2F$encoded"
            }
            basePath.endsWith("/") -> "$basePath$path"
            else -> "$basePath/$path"
        }
    }

    private fun parseEntryUri(uriString: String, basePath: String?): Uri? = try {
        when {
            uriString.startsWith("/")          -> Uri.fromFile(java.io.File(uriString))
            uriString.startsWith("file://")    -> Uri.fromFile(java.io.File(uriString.substring(7)))
            uriString.startsWith("content://") ||
            uriString.startsWith("http://")    ||
            uriString.startsWith("https://")   ||
            uriString.startsWith("smb://")     -> uriString.toUri()
            // Last-ditch attempt: partial SAF path
            basePath == null && uriString.startsWith("primary%3A") -> uriString.toUri()
            else -> null
        }
    } catch (e: Exception) { null }

    fun mimeTypeFor(uriString: String): String? {
        val lower = uriString.lowercase()
        return when {
            lower.endsWith(".flac")               -> MimeTypes.AUDIO_FLAC
            lower.endsWith(".mp3")                -> MimeTypes.AUDIO_MPEG
            lower.endsWith(".wav")                -> MimeTypes.AUDIO_WAV
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            lower.endsWith(".ogg")                -> MimeTypes.AUDIO_OGG
            lower.endsWith(".ape")                -> "audio/x-ape"
            // Not used for routing (SacdMediaSourceFactory picks the WavPack/DSD
            // extractors by file name), but keeps every item's MIME type set.
            lower.endsWith(".wv") || lower.endsWith(".wvp") -> "audio/x-wavpack"
            lower.endsWith(".dsf")                -> "audio/x-dsf"
            lower.endsWith(".dff")                -> "audio/x-dff"
            else                                  -> null
        }
    }
}
