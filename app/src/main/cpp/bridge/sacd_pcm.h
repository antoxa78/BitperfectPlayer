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
 *                Accepts rates <= the decoder's native rate (DSD64 -> 352800).
 *                0 selects SACD_DEFAULT_OUT_HZ.
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
 * Decodes up to `frames` output frames (one frame = `channels` interleaved
 * float samples) into `out`.
 *
 * Returns the number of frames written (>=0); 0 means end of track;
 * -1 on error.  Streaming: state (position, DSD filter state, DST decoder)
 * is kept across calls.
 */
long sacd_pcm_read(sacd_pcm_reader_t *r, float *out, long frames);

/*
 * Seeks to the given output frame index (0-based).  Backwards seeks restart
 * the decoder from the start of the track and fast-forward; forwards seeks
 * decode-and-discard.  Returns 0 on success, -1 on error.
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