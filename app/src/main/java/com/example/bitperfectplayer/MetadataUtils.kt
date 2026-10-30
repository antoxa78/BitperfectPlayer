package com.example.bitperfectplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class BasicMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Track number from the tags ("3" or "3/12" → 3), null when untagged. */
    val trackNumber: Int? = null,
    /** Disc number from the tags ("2" or "2/3" → 2), null when untagged. */
    val discNumber: Int? = null
)

object MetadataUtils {
    fun getMetadata(context: Context, uri: Uri): BasicMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val track = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            val disc = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            BasicMetadata(title, artist, album, track, disc)
        } catch (e: Exception) {
            BasicMetadata(null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    /** "3", "03", "3/12", " 3 of 12" → 3; anything without a leading positive number → null. */
    internal fun parseLeadingInt(raw: String?): Int? =
        raw?.trim()?.takeWhile { it in '0'..'9' }?.take(6)?.toIntOrNull()?.takeIf { it > 0 }
}
