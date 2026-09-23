#ifndef DSD_CONV_H
#define DSD_CONV_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Streaming DSD -> PCM converter for DSF/DFF files (any DSD rate, any
 * channel count up to DSD_CONV_MAX_CH).
 *
 * Stage 1: FFmpeg's dsd2pcm (8:1, one float per DSD byte) -> dsd_rate / 8.
 * Stage 2: windowed-sinc (Blackman-Harris) anti-alias low-pass + decimation
 *          to out_hz, designed for the actual ratio (2, 4, 8, 16), with the
 *          cut-off at ~0.44 x out_hz (78 kHz for 176.4 kHz, as the SACD path).
 *
 * Output level matches the SACD path: full-scale DSD maps to about +/-1.0,
 * i.e. SACD "0 dB" (50% modulation) to about 0.5.
 */

#define DSD_CONV_MAX_CH 8

typedef struct dsd_conv dsd_conv_t;

/* Picks the PCM rate for a DSD rate: 176400 for the 44.1 kHz family,
 * 192000 for the 48 kHz family; 0 if the rate is not a DSD rate. */
int dsd_conv_default_out_hz(int dsd_rate_hz);

/* Returns NULL if the combination is unsupported
 * (out_hz must divide dsd_rate/8 with a ratio of 1..32). */
dsd_conv_t *dsd_conv_create(int channels, int dsd_rate_hz, int out_hz);

/*
 * Converts `bytes_per_ch` DSD bytes per channel from `src` — channel-
 * interleaved bytes (ch0 byte, ch1 byte, ...), each byte MSB-first (earliest
 * DSD bit in bit 7) — appending interleaved float PCM to `out`.
 * `out` must hold at least dsd_conv_max_out_frames(c, bytes_per_ch) frames.
 * Returns the number of output frames written.
 */
long dsd_conv_process(dsd_conv_t *c, const uint8_t *src, size_t bytes_per_ch, float *out);

size_t dsd_conv_max_out_frames(const dsd_conv_t *c, size_t bytes_per_ch);

/* Clears filter state (after a seek). */
void dsd_conv_reset(dsd_conv_t *c);

int dsd_conv_out_hz(const dsd_conv_t *c);
int dsd_conv_taps(const dsd_conv_t *c);

void dsd_conv_destroy(dsd_conv_t *c);

#ifdef __cplusplus
}
#endif

#endif /* DSD_CONV_H */
