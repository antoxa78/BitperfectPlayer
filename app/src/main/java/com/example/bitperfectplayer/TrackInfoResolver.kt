package com.example.bitperfectplayer

import androidx.core.net.toUri
import androidx.media3.common.Player

/**
 * Shared "track title / artist" resolution used by the main-screen Now Playing
 * card and the full-screen Now Playing activity, so both always show the same
 * info. Mirrors the full-screen logic exactly (ICY streams, fallbacks, and
 * "Artist - Title" delimiter splitting).
 */
object TrackInfoResolver {

    data class Info(val track: String, val artist: String, val album: String = "")

    fun resolve(controller: Player?, icyInfo: IcyStreamInfo?): Info {
        val metadata  = controller?.mediaMetadata
        val mediaItem = controller?.currentMediaItem
        val itemMeta  = mediaItem?.mediaMetadata
        val itemTitle    = itemMeta?.title?.toString()?.takeIf { it.isNotBlank() }
        val itemArtist   = itemMeta?.artist?.toString()?.takeIf { it.isNotBlank() }
        val itemAlbumArtist = itemMeta?.albumArtist?.toString()?.takeIf { it.isNotBlank() }
        val displayTitle = metadata?.displayTitle?.toString() ?: itemMeta?.displayTitle?.toString()
        val subtitle     = metadata?.subtitle?.toString() ?: itemMeta?.subtitle?.toString()
        val description  = metadata?.description?.toString() ?: itemMeta?.description?.toString()

        val mediaId     = mediaItem?.mediaId ?: ""
        val isStream    = mediaId.startsWith("http://") || mediaId.startsWith("https://")
        val icyForItem  = icyInfo?.takeIf { it.mediaId == mediaId }
        val icyTrack    = icyForItem?.title?.takeIf { it.isNotBlank() }
        val icyDesc     = icyForItem?.description
        val station     = metadata?.station?.toString()?.takeIf { it.isNotBlank() }
                          ?: itemMeta?.station?.toString()?.takeIf { it.isNotBlank() }
                          ?: icyForItem?.station

        // Item-first resolution: the live (extractor) metadata is incomplete right
        // after a track transition, so prefer the item's static metadata for a
        // stable display. Live tags only fill gaps for LOCAL files (a stream's
        // live tags are the stream's own junk and are ignored).
        val liveTitle  = if (!isStream) metadata?.title?.toString()?.takeIf { it.isNotBlank() } else null
        val liveArtist = if (!isStream) metadata?.artist?.toString()?.takeIf { it.isNotBlank() } else null
        val liveAlbumArtist = if (!isStream) metadata?.albumArtist?.toString()?.takeIf { it.isNotBlank() } else null

        val title = itemTitle ?: liveTitle ?: displayTitle
        val albumArtist = itemAlbumArtist ?: liveAlbumArtist ?: ""
        // Streams never use the live extractor tags (they are the stream's own
        // junk — e.g. "various"); only the item + ICY info describe the stream.
        var dispArtist = if (isStream) itemArtist ?: "" else itemArtist ?: liveArtist ?: albumArtist
        var dispTrack  = when {
            icyTrack != null -> icyTrack
            isStream && !station.isNullOrBlank() -> station
            isStream -> streamTitleFallback(mediaId, title ?: displayTitle) ?: displayTitle ?: "Unknown Title"
            else -> title ?: displayTitle ?: "Unknown Title"
        }
        if (!isStream && (dispArtist.isEmpty() || dispArtist.equals("Unknown Artist", ignoreCase = true))) {
            dispArtist = when {
                !subtitle.isNullOrBlank()    && subtitle    != dispTrack -> subtitle
                !description.isNullOrBlank() && description != dispTrack -> description
                else -> dispArtist
            }
        }
        val delims = arrayOf(" - ", " – ", " — ", " : ", " | ")
        // Only ICY stream titles get the "Artist - Track" split — splitting local
        // filenames corrupts track-number titles like "04 - Is It A Dream".
        if (icyTrack != null && (dispArtist.isEmpty() || dispArtist.equals("Unknown Artist", ignoreCase = true))) {
            var split = false
            for (d in delims) { if (dispTrack.contains(d)) { val p = dispTrack.split(d, limit=2); dispArtist = p[0].trim(); dispTrack = p[1].trim(); split = true; break } }
            if (!split && dispArtist.contains(" - ")) { val p = dispArtist.split(" - ", limit=2); dispArtist = p[0].trim(); dispTrack = p[1].trim() }
        }
        // When the title embeds the known artist ("Artist - Track"), strip the
        // prefix so the artist value is not shown twice.
        if (dispArtist.isNotBlank() && !dispArtist.equals("Unknown Artist", ignoreCase = true)) {
            for (d in delims) {
                val prefix = dispArtist + d
                if (dispTrack.startsWith(prefix)) { dispTrack = dispTrack.substring(prefix.length).trim(); break }
            }
        }
        if (dispArtist.isEmpty() || dispArtist.equals("Unknown Artist", ignoreCase = true)) {
            dispArtist = when {
                isStream -> station ?: streamHost(mediaId) ?: "Unknown Artist"
                else -> station ?: displayTitle ?: "Unknown Artist"
            }
        }
        if (dispArtist == dispTrack && !station.isNullOrBlank()) dispArtist = station
        // Album / disc row: station or stream description for streams, live
        // album tag for local files (same resolution as the full-screen player).
        val album = when {
            !station.isNullOrBlank()      && station      != dispTrack && station      != dispArtist -> station
            !icyDesc.isNullOrBlank()      && icyDesc      != dispTrack && icyDesc      != dispArtist -> icyDesc
            !displayTitle.isNullOrBlank() && displayTitle != dispTrack && displayTitle != dispArtist -> displayTitle
            else -> metadata?.albumTitle?.toString() ?: itemMeta?.albumTitle?.toString() ?: ""
        }
        return Info(dispTrack, dispArtist, album)
    }

    /** Host of a stream URL (without www.), or null for non-stream media. */
    fun streamHost(mediaId: String): String? {
        if (!mediaId.startsWith("http://") && !mediaId.startsWith("https://")) return null
        return mediaId.toUri().host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    /**
     * Display title for a stream that broadcasts no usable metadata.
     * Prettifies a static title that is just the URL's file name (drops the
     * extension), otherwise derives a name from the URL path or host.
     */
    fun streamTitleFallback(mediaId: String, staticTitle: String?): String? {
        if (!mediaId.startsWith("http://") && !mediaId.startsWith("https://")) return null
        val last = mediaId.toUri().lastPathSegment
        if (!staticTitle.isNullOrBlank()) {
            return if (last != null && staticTitle == last) last.substringBeforeLast(".") else staticTitle
        }
        val name = last?.substringBeforeLast(".")
        if (!name.isNullOrBlank()) return name
        return mediaId.toUri().host?.removePrefix("www.")
    }
}