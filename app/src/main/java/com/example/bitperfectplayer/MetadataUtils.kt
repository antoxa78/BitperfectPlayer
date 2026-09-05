package com.example.bitperfectplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class BasicMetadata(
    val title: String?,
    val artist: String?,
    val album: String?
)

object MetadataUtils {
    fun getMetadata(context: Context, uri: Uri): BasicMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            BasicMetadata(title, artist, album)
        } catch (e: Exception) {
            BasicMetadata(null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }
}
