package com.example.bitperfectplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * Routes DSD items to dedicated sources; everything else is delegated to the
 * wrapped [MediaSource.Factory] unchanged:
 *  - SACD track items ("sacd:..." mediaIds) -> [SacdMediaExtractor]
 *  - .dsf / .dff files -> [DsdFileExtractor], read through [dataSourceFactory]
 *    (local, content:// and SMB alike).
 * Whether DSD is sent as DoP or converted to PCM is decided from
 * [PlaybackService.isDopOutputActive] each time a track starts loading.
 */
@OptIn(UnstableApi::class)
class SacdMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val context: Context? = null,
    private val dataSourceFactory: DataSource.Factory? = null,
) : MediaSource.Factory {

    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.getSupportedTypes()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Route on the "sacd:" mediaId prefix we stamp in buildTrackMediaItems.
        // localConfiguration/mimeType is not reliable here: media3 strips the
        // local configuration for sources created via the session, so gate on
        // the machine-readable mediaId alone.
        val info = SacdSupport.parseTrackInfo(mediaItem.mediaId)
        if (info != null) return createSacdSource(info, mediaItem)

        val dsf = dataSourceFactory
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.mediaId
        if (dsf != null && (DsdFileExtractor.isDsdFileUri(uri) || isDsdContentUri(uri))) {
            return createDsdFileSource(mediaItem, dsf)
        }
        return delegate.createMediaSource(mediaItem)
    }

    /**
     * content:// URIs often carry no file name (MediaStore ids, some document
     * providers), so the extension check alone would send a DSF/DFF to the
     * default extractors, which cannot play it: ask the provider for the
     * display name instead.
     */
    private fun isDsdContentUri(uri: String): Boolean {
        val ctx = context ?: return false
        if (!uri.startsWith("content://")) return false
        return try {
            ctx.contentResolver.query(
                Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: return@use false
                    DsdFileExtractor.isDsdFileUri(name)
                } else false
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun dopActive(): Boolean = context?.let { PlaybackService.isDopOutputActive(it) } == true

    private fun createDsdFileSource(mediaItem: MediaItem, dsf: DataSource.Factory): MediaSource {
        // Decided when the track starts loading (not when it was queued), so a
        // change in Settings applies to every DSD track that hasn't loaded yet.
        val extractors = ExtractorsFactory { arrayOf<Extractor>(DsdFileExtractor(dopActive())) }
        val factory = ProgressiveMediaSource.Factory(dsf, extractors)
        loadErrorHandlingPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(mediaItem)
    }

    private fun createSacdSource(info: SacdSupport.TrackInfo, mediaItem: MediaItem): MediaSource {
        // Each extractor instance owns its own reader (and closes it in release()),
        // so two periods of the same source never share an SMB connection.
        val progressiveExtractorFactory = ProgressiveMediaExtractor.Factory {
            SacdProgressiveMediaExtractor(
                SacdSupport.buildRandomAccess(info.srcUri),
                info.area,
                info.track,
                info.outHz,
                dopActive()  // evaluated when the track starts loading
            )
        }
        val dataSourceFactory = DataSource.Factory { SacdPassthroughDataSource() }
        val factory = ProgressiveMediaSource.Factory(dataSourceFactory, progressiveExtractorFactory)
        // Same retry policy as every other source (SMB read errors are IOExceptions).
        loadErrorHandlingPolicy?.let { factory.setLoadErrorHandlingPolicy(it) }
        return factory.createMediaSource(mediaItem)
    }
}

/**
 * DataSource for SACD playback. The extractor drives all ISO I/O itself through
 * [SacdRandomAccess], so this only has to expose a valid stream without reading.
 */
@OptIn(UnstableApi::class)
class SacdPassthroughDataSource : BaseDataSource(false) {
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = null

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}