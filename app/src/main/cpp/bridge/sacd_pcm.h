#ifndef SACD_PCM_H
#define SACD_PCM_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SACD_CH_MAX 6
#define SACD_DEFAULT_OUT_HZ 176400

/* Callback-based block reader (see sacd_input.h for the signatures). */
typedef int (*sacd_block_read_fn)(void *opaque, int64_t offset, void *buf, int len);
typedef int64_t (*sacd_block_size_fn)(void *opaque);

/*
 * Parsed SACD ISO album metadata for one area (stereo area is area 0).
 * All strings are UTF-8, malloc'd.  Freed by sacd_album_info_free().
 */
typedef struct {
    const char *version;         /* library version marker */
    int    area;
    int    track_count;
    int    channel_count;        /* of the parsed area (2 for stereo) */
    int    dsd_rate_hz;          /* 2822400 for DSD64 */
    int    dst;                  /* 1 if the area is DST-compressed */
    char  *album_title;
    char  *album_artist;
    char **track_title;          /* [track_count] */
    char **track_artist;         /* [track_count] */
    uint32_t *track_start_lsn;   /* [track_count] */
    uint32_t *track_length_lsn;  /* [track_count] */
    uint64_t *track_duration_ms; /* [track_count] */
} sacd_album_info_t;

/*
 * Parses an SACD ISO and extracts metadata for an audio area.
 * Returns 0 on success, -1 on error.  On success *out must be released
 * with sacd_album_info_free().
 */
int sacd_album_info_open(const char *iso_path, int area, sacd_album_info_t *out);

/*
 * Same as sacd_album_info_open() but reads the image through callbacks.
 * See sacd_input_read_fn / sacd_input_size_fn in sacd_input.h.
 */
int sacd_album_info_open_cb(sacd_block_read_fn read, sacd_block_size_fn size,
                            void *opaque, int area, sacd_album_info_t *out);

void sacd_album_info_free(sacd_album_info_t *info);

typedef struct sacd_pcm_reader sacd_pcm_reader_t;

/*
 * Opens a streaming reader for one track of an SACD ISO.
 *
 *   iso_path     path to the SACD ISO image
 *   area         0 = two-channel area, 1 = multi-channel area
 *   track        0-based track index within the area
 *   out_hz       requested PCM output rate (e.g. 176400).
 *                Must divide the decoder's native rate (DSD64 -> 352800) so
 *                that one SACD frame (4704 DSD bytes per channel) maps to a
 *                whole number of output frames: 352800, 176400, 117600,
 *                88200, 58800, 50400, 44100, ... 0 selects SACD_DEFAULT_OUT_HZ.
 *                176400 uses the built-in 255-tap decimator; other rates use
 *                the dsd_conv anti-alias filter designed for the ratio.
 *
 * Decoded output is float32, L/R interleaved, in the range roughly [-1, 1].
 * Returns NULL on error.
 */
sacd_pcm_reader_t *sacd_pcm_open(const char *iso_path, int area, int track,
                                 int out_hz);

/*
 * Same as sacd_pcm_open() but reads the image through callbacks
 * (streaming sources such as SMB).
 */
sacd_pcm_reader_t *sacd_pcm_open_cb(sacd_block_read_fn read, sacd_block_size_fn size,
                                    void *opaque, int area, int track, int out_hz);

void sacd_pcm_close(sacd_pcm_reader_t *r);

/*
 * Switches the reader to DoP (DSD over PCM) output: instead of converting DSD
 * to PCM, each output sample carries 16 raw DSD bits under the 0x05/0xFA DoP
 * marker, for DACs that decode DSD themselves. Requires a stereo area and an
 * output rate of dsd_rate/16 (176400 for SACD's DSD64). Must be called before
 * the first read. Returns 0 on success, -1 if not possible.
 */
int sacd_pcm_set_dop(sacd_pcm_reader_t *r, int enable);

/*
 * DoP mode only: reads up to `frames` frames as packed 24-bit little-endian
 * DoP samples (3 bytes per channel sample) into `out`. Returns frames read,
 * 0 at end of track, -1 on error.
 */
long sacd_pcm_read_dop24(sacd_pcm_reader_t *r, uint8_t *out, long frames);

/*
 * Decodes up to `frames` output frames (one frame = `channels` interleaved
 * float samples) into `out`.
 *
 * Returns the number of frames written (>=0); 0 means end of track;
 * -1 on error.  A read error from the source is not sticky: frames already
 * decoded are returned first, and the next call retries the same sectors.
 * Streaming: state (position, DSD filter state, DST decoder) is kept across
 * calls.
 */
long sacd_pcm_read(sacd_pcm_reader_t *r, float *out, long frames);

/*
 * Seeks to the given output frame index (0-based).  Short forward hops
 * (<= 2 s) drop output lazily; anything else jumps straight to the sector
 * holding the target frame (estimated from the track's LSN range and
 * verified by frame timecode), with one frame of pre-roll so the filters
 * settle.  Also clears a previous read error.  Returns 0 on success, -1 on
 * error (e.g. the source could not be read; the seek may be retried).
 */
int sacd_pcm_seek_output_frame(sacd_pcm_reader_t *r, unsigned long long output_frame);

/* Configured output rate / channel count for the reader. */
int sacd_pcm_out_rate(sacd_pcm_reader_t *r);
int sacd_pcm_channels(sacd_pcm_reader_t *r);

/* Expected total output frames for the whole track. */
uint64_t sacd_pcm_output_frames(sacd_pcm_reader_t *r);

/* Estimated track length in milliseconds (from the disc TOC). */
uint64_t sacd_pcm_duration_ms(sacd_pcm_reader_t *r);

#ifdef __cplusplus
}
#endif

#endif /* SACD_PCM_H */