package com.example.bitperfectplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import jcifs.smb.SmbFile
import java.io.File

/**
 * WavPack-specific plumbing: turning a media URI into a seekable source for
 * [WavPackExtractor].
 *
 * The extractor reads through a [SacdRandomAccess] rather than a Media3 `DataSource`
 * because seeking needs arbitrary byte access, which `ExtractorInput` does not offer.
 * The interface and its SMB/local backends are shared with the SACD path; only the
 * `content://` backend and the dispatch below are specific to this file.
 */
object WavPackSupport {

    private const val TAG = "WavPackSupport"

    /**
     * Opens [uri] for reading, or returns null when it cannot be opened. Runs on the
     * caller's thread: SMB handles are opened lazily by the backend, so this is safe to
     * call while building the media source on the main thread.
     */
    fun openAccess(uri: String, context: Context?): SacdRandomAccess? = try {
        when {
            uri.startsWith("smb://", ignoreCase = true) ->
                SmbSacdRandomAccess(SmbFile(uri, SmbContext.getContextForUri(uri)))

            uri.startsWith("content://") -> openContent(uri, context)

            else -> {
                val path = if (uri.startsWith("file://")) {
                    uri.toUri().path ?: uri.removePrefix("file://")
                } else {
                    uri
                }
                val f = File(path)
                if (f.isFile) LocalSacdRandomAccess(f) else null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "openAccess($uri) failed", e)
        null
    }

    private fun openContent(uri: String, context: Context?): SacdRandomAccess? {
        val ctx = context ?: return null
        val afd = ctx.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r") ?: return null
        // Some providers report an unknown length; the stream treats <0 as "until EOF".
        return ContentResolverRandomAccess(afd)
    }
}
