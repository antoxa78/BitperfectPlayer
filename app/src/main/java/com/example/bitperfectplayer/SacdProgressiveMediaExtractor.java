package com.example.bitperfectplayer;

import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.DataReader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@link ProgressiveMediaExtractor} that wraps {@link SacdMediaExtractor} and reports a virtual,
 * advancing input position.
 *
 * <p>The SACD extractor generates PCM directly from the ISO (via {@link SacdRandomAccess}) and
 * never consumes bytes from the data source, so the input position would otherwise stay at 0.
 * That starves {@code ProgressiveMediaPeriod}'s load throttle ({@code currentInputPosition >
 * position + continueLoadingCheckIntervalBytes}), keeping {@code loadCondition} open forever and
 * {@code isLoading()} true — the player then sits in BUFFERING while the loader decodes the whole
 * track. Advancing {@link #getCurrentInputPosition()} by the size of each produced sample turns
 * the loader into a pull-driven, bursted reader, which unblocks the renderer and reaches READY.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SacdProgressiveMediaExtractor implements ProgressiveMediaExtractor {

    private final SacdRandomAccess reader;
    private final int area;
    private final int track;
    private final int outHz;

    private SacdMediaExtractor extractor;
    private ExtractorInput input;
    private long inputPosition;

    public SacdProgressiveMediaExtractor(SacdRandomAccess reader, int area, int track, int outHz) {
        this.reader = reader;
        this.area = area;
        this.track = track;
        this.outHz = outHz;
    }

    @Override
    public void init(
            DataReader dataReader,
            Uri uri,
            Map<String, List<String>> responseHeaders,
            long position,
            long length,
            ExtractorOutput output) {
        input = new DefaultExtractorInput(dataReader, position, length);
        inputPosition = position;
        SacdMediaExtractor e = new SacdMediaExtractor(reader, area, track, outHz);
        e.init(output);
        extractor = e;
    }

    @Override
    public void release() {
        if (extractor != null) {
            extractor.release();
        }
        extractor = null;
        input = null;
    }

    @Override
    public void disableSeekingOnMp3Streams() {
        // Not applicable; SACD output is generated, not demuxed.
    }

    @Override
    public long getCurrentInputPosition() {
        return inputPosition;
    }

    @Override
    public void seek(long position, long seekTimeUs) {
        inputPosition = position;
        if (extractor != null) {
            extractor.seek(position, seekTimeUs);
        }
    }

    @Override
    public int read(PositionHolder positionHolder) {
        if (extractor == null) {
            return Extractor.RESULT_END_OF_INPUT;
        }
        int result = extractor.read(input, positionHolder);
        long bytes = extractor.takeLastReadBytes();
        if (bytes > 0) {
            inputPosition += bytes;
        }
        return result;
    }
}