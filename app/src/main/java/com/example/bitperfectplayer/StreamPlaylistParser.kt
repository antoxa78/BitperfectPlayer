package com.example.bitperfectplayer

import java.net.URI

/**
 * Turns a radio station's playlist link (`…/listen.pls?sid=1`, `…/listen.m3u`)
 * into the stream URL it names.
 *
 * Radio directories often list a station as a small PLS/M3U document rather
 * than the stream itself. ExoPlayer has no extractor for either format, so the
 * item dies with `UnrecognizedInputFormatException` ("Source error") and the
 * station looks dead even though the link is valid. [parse] is deliberately
 * small and dependency-free: the playback path calls it on the loader thread
 * after fetching a bounded prefix of the document.
 *
 * HLS manifests are rejected on purpose — they list media segments, not a
 * stream, and expanding one would make the player open a single segment.
 */
object StreamPlaylistParser {

    /** HLS tags that distinguish a manifest from an M3U station list. */
    private val HLS_TAGS = arrayOf(
        "#EXT-X-STREAM-INF", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-ENDLIST", "#EXT-X-PLAYLIST-TYPE", "#EXT-X-MAP", "#EXT-X-KEY",
        "#EXT-X-BYTERANGE", "#EXT-X-DISCONTINUITY", "#EXT-X-VERSION",
        "#EXT-X-I-FRAMES-ONLY", "#EXT-X-MEDIA:"
    )

    /**
     * True when [url]'s path names a station playlist that may point at a
     * stream. `.m3u8` is intentionally excluded: that is HLS in every use this
     * app has seen, and it is not a station list.
     */
    fun isPlaylistUrl(url: String): Boolean {
        val path = try { URI(url).path } catch (_: Exception) { null } ?: return false
        val lower = path.lowercase()
        return lower.endsWith(".pls") || lower.endsWith(".m3u")
    }

    /**
     * Returns the first http(s) stream URL in [content], resolved against
     * [playlistUrl], or null when the document does not name one (an audio
     * body, an HLS manifest, a malformed playlist, …).
     *
     * The first entry wins: station links produced by radio directories are
     * single-entry PLS/M3U files. A plain list (no PLS header) is treated as
     * the Radio-Browser style "one stream URL per line" M3U.
     */
    fun parse(content: String, playlistUrl: String): String? {
        val lines = content.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null
        if (lines.any(::isHlsTag)) return null

        val candidate =
            if (lines.first().equals("[playlist]", ignoreCase = true)) {
                lines.asSequence().mapNotNull(::plsFile).firstOrNull()
            } else {
                lines.firstOrNull { !it.startsWith("#") }
            } ?: return null

        val resolved = resolve(candidate, playlistUrl) ?: return null
        return resolved.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
    }

    private fun isHlsTag(line: String): Boolean =
        HLS_TAGS.any { line.startsWith(it, ignoreCase = true) }

    /** `File1=…` from a PLS document; keys are case-insensitive. */
    private fun plsFile(line: String): String? {
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim().lowercase()
        if (!key.startsWith("file")) return null
        val index = key.substring(4)
        if (index.isEmpty() || !index.all(Char::isDigit)) return null
        return line.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
    }

    private fun resolve(candidate: String, base: String): String? = try {
        val ref = URI(candidate)
        if (ref.isAbsolute) ref.toString() else URI(base).resolve(ref).toString()
    } catch (_: Exception) { null }
}
