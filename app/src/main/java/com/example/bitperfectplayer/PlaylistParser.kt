package com.example.bitperfectplayer

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
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

    fun parseCueFromStream(inputStream: InputStream, basePath: String?): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        try {
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var currentFile: String? = null
            var albumTitle: String? = null
            var albumArtist: String? = null

            data class CueTrack(val number: Int, var title: String? = null, var artist: String? = null, var startTimeMs: Long = 0)
            val tracks = mutableListOf<CueTrack>()
            var currentTrack: CueTrack? = null

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim()?.removePrefix("\uFEFF") ?: continue
                val upper = trimmed.uppercase()

                when {
                    upper.startsWith("FILE") -> {
                        currentFile = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("TITLE") && currentTrack == null -> {
                        albumTitle = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("PERFORMER") && currentTrack == null -> {
                        albumArtist = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("TRACK") -> {
                        val num = trimmed.split(" ")[1].toIntOrNull() ?: 0
                        currentTrack = CueTrack(num)
                        tracks.add(currentTrack)
                    }
                    upper.startsWith("TITLE") && currentTrack != null -> {
                        currentTrack.title = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("PERFORMER") && currentTrack != null -> {
                        currentTrack.artist = trimmed.substringAfter("\"").substringBeforeLast("\"")
                    }
                    upper.startsWith("INDEX 01") && currentTrack != null -> {
                        val timeStr = trimmed.substringAfter("INDEX 01").trim()
                        currentTrack.startTimeMs = parseCueTime(timeStr)
                    }
                }
            }

            if (currentFile != null && tracks.isNotEmpty()) {
                val audioUriString = resolveRelativePath(currentFile, basePath)
                val audioUri = parseEntryUri(audioUriString, basePath)

                if (audioUri != null) {
                    for (i in tracks.indices) {
                        val track = tracks[i]
                        val nextTrackStart = if (i + 1 < tracks.size) tracks[i+1].startTimeMs else C.TIME_UNSET

                        val metaBuilder = MediaMetadata.Builder()
                            .setTitle(track.title ?: "Track ${track.number}")
                            .setArtist(track.artist ?: albumArtist ?: "Unknown Artist")
                            .setAlbumTitle(albumTitle ?: "Unknown Album")

                        val clippingBuilder = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(track.startTimeMs)
                        if (nextTrackStart != C.TIME_UNSET) {
                            clippingBuilder.setEndPositionMs(nextTrackStart)
                        }

                        items.add(
                            MediaItem.Builder()
                                .setMediaId("${audioUri}_${track.number}")
                                .setUri(audioUri)
                                .setMimeType(mimeTypeFor(audioUri.toString()))
                                .setMediaMetadata(metaBuilder.build())
                                .setClippingConfiguration(clippingBuilder.build())
                                .build()
                        )
                    }
                }
            }
        } catch (e: Exception) { Log.w("PlaylistParser", "cue parse failed", e) }
        return items
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
            else                                  -> null
        }
    }

    private fun parseCueTime(timeStr: String): Long {
        // MM:SS:FF where FF is frames (1/75th of a second)
        val parts = timeStr.split(":")
        if (parts.size != 3) return 0
        val m = parts[0].toLongOrNull() ?: 0
        val s = parts[1].toLongOrNull() ?: 0
        val f = parts[2].toLongOrNull() ?: 0
        return (m * 60 * 1000) + (s * 1000) + (f * 1000 / 75)
    }
}
