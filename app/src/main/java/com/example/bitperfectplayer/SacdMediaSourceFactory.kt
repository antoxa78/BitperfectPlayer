package com.example.bitperfectplayer

import android.net.Uri
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

/**
 * Routes SACD track items ("sacd:..." mediaIds) to a [ProgressiveMediaSource]
 * driven by [SacdMediaExtractor]; everything else is delegated to the wrapped
 * [MediaSource.Factory] unchanged.
 */
@OptIn(UnstableApi::class)
class SacdMediaSourceFactory(
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory {

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
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.getSupportedTypes()

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        // Route on the "sacd:" mediaId prefix we stamp in buildTrackMediaItems.
        // localConfiguration/mimeType is not reliable here: media3 strips the
        // local configuration for sources created via the session, so gate on
        // the machine-readable mediaId alone.
        val info = SacdSupport.parseTrackInfo(mediaItem.mediaId)
        if (info == null) {
            return delegate.createMediaSource(mediaItem)
        }
        return createSacdSource(info, mediaItem)
    }

    private fun createSacdSource(info: SacdSupport.TrackInfo, mediaItem: MediaItem): MediaSource {
        // Each extractor instance owns its own reader (and closes it in release()),
        // so two periods of the same source never share an SMB connection.
        val progressiveExtractorFactory = ProgressiveMediaExtractor.Factory {
            SacdProgressiveMediaExtractor(
                SacdSupport.buildRandomAccess(info.srcUri),
                info.area,
                info.track,
                info.outHz
            )
        }
        val dataSourceFactory = DataSource.Factory { SacdPassthroughDataSource() }
        return ProgressiveMediaSource.Factory(dataSourceFactory, progressiveExtractorFactory)
            .createMediaSource(mediaItem)
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