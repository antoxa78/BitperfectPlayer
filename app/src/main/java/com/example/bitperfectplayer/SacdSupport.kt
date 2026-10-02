package com.example.bitperfectplayer

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.util.Log
import jcifs.smb.SmbFile
import org.json.JSONObject

/**
 * SACD ISO helpers shared by the browse UI and the media source factory.
 *
 * Track MediaItems carry a "sacd:" mediaId the [SacdMediaSourceFactory]
 * recognizes; the encoded track index / rate / duration let the extractor
 * build an accurate timeline without re-parsing the ISO.
 */
object SacdSupport {
    private const val TAG = "SacdSupport"
    const val MIME = "application/x-sacd"
    const val OUT_HZ = 176400
    const val AREA_STEREO = 0
    private const val PREFIX = "sacd:"

    fun isIsoName(name: String): Boolean = name.lowercase().endsWith(".iso")

    data class TrackInfo(
        val area: Int,
        val track: Int,
        val outHz: Int,
        val durationMs: Long,
        val srcUri: String
    )

    fun mediaIdFor(info: TrackInfo): String =
        "$PREFIX${info.area}:${info.track}:${info.outHz}:${info.durationMs}:${info.srcUri}"

    fun parseTrackInfo(mediaId: String): TrackInfo? {
        if (!mediaId.startsWith(PREFIX)) return null
        val parts = mediaId.removePrefix(PREFIX).split(":", limit = 5)
        if (parts.size != 5) return null
        val area = parts[0].toIntOrNull() ?: return null
        val track = parts[1].toIntOrNull() ?: return null
        val outHz = parts[2].toIntOrNull() ?: return null
        val durationMs = parts[3].toLongOrNull() ?: return null
        if (outHz <= 0 || durationMs < 0 || parts[4].isEmpty()) return null
        return TrackInfo(area, track, outHz, durationMs, parts[4])
    }

    fun isSacdItem(mediaItem: MediaItem): Boolean =
        mediaItem.mediaId.startsWith(PREFIX) ||
            mediaItem.localConfiguration?.mimeType == MIME

    /** Builds a random-access source for an smb://, file:// or plain path uri. */
    fun buildRandomAccess(srcUri: String): SacdRandomAccess =
        if (srcUri.startsWith("smb://", ignoreCase = true)) {
            SmbSacdRandomAccess(SmbFile(srcUri, SmbContext.getContextForUri(srcUri)))
        } else {
            val path = if (srcUri.startsWith("file://")) {
                srcUri.toUri().path ?: srcUri.substringAfter("file://")
            } else {
                srcUri
            }
            LocalSacdRandomAccess(java.io.File(path))
        }

    /**
     * Parses the native album-info JSON and builds one MediaItem per stereo track.
     * [access] owns the underlying file handle and is closed by the caller.
     */
    fun buildTrackMediaItems(
        access: SacdRandomAccess,
        area: Int,
        albumTitle: String?,
        srcUri: String
    ): Result<List<MediaItem>> {
        val json = try {
            SacdBridge.nativeAlbumInfoReader(access, area)
        } catch (e: Exception) {
            Log.w(TAG, "nativeAlbumInfoReader threw", e)
            return Result.failure(e)
        }
        if (json.startsWith("ERR")) {
            Log.w(TAG, "nativeAlbumInfoReader -> $json")
            return Result.failure(RuntimeException(json))
        }
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val album = obj.optString("album_title").ifEmpty { albumTitle ?: "" }
        val albumArtist = obj.optString("album_artist")
        val tracksJson = obj.optJSONArray("tracks") ?: run {
            Log.w(TAG, "no 'tracks' in album JSON")
            return Result.success(emptyList())
        }

        val items = mutableListOf<MediaItem>()
        for (i in 0 until tracksJson.length()) {
            val t = tracksJson.optJSONObject(i) ?: continue
            val durationMs = t.optLong("duration_ms", 0L)
            // Numbered title keeps disk/album order when folder scans re-sort by
            // extracted track number (matches how other tracks are displayed).
            val rawTitle = t.optString("title").ifEmpty { "Track ${i + 1}" }
            val title = "${i + 1}. $rawTitle"
            val artist = t.optString("artist").ifEmpty { albumArtist }
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .build()
            val info = TrackInfo(area, i, OUT_HZ, durationMs, srcUri)
            items.add(
                MediaItem.Builder()
                    .setMediaId(mediaIdFor(info))
                    .setUri(srcUri.toUri())
                    .setMimeType(MIME)
                    .setMediaMetadata(metadata)
                    .build()
            )
        }
        Log.i(TAG, "buildTrackMediaItems: ${items.size} tracks from $srcUri")
        return Result.success(items)
    }
}