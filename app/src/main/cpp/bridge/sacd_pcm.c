/*
 * SACD bridge: SACD ISO -> PCM (DSD/DST decode + DSD->PCM) for BitperfectPlayer.
 *
 * Uses GPL-2.0 code from sacd-ripper (libsacd / libdstdec / libcommon) and
 * LGPL-2.1 code from FFmpeg (libavcodec/dsd.c) for the DSD->PCM stage.
 * See third_party/sacd/COPYING and third_party/ffmpeg_dsd/COPYING.LGPLv2.1.
 */

#include "sacd_pcm.h"

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#include "sacd_reader.h"
#include "scarletbook_read.h"
#include "scarletbook.h"
#include "scarletbook_helpers.h"
#include "dst_decoder.h"
#include "dsd.h" /* ffmpeg DSD->PCM */
#include "logging.h" /* init_logging, lm_main */

static int sacd_init_once(void)
{
    static int done = 0;
    static int ok = 0;
    if (!done) {
        done = 1;
        init_logging(1); /* safe to call more than once; see libcommon */
        ok = 1;
    }
    return ok;
}

/* Debug hook (PoC only): dump the raw DSD frames fed to the DSD->PCM stage. */
static FILE *g_dump = NULL;
static void sacd_dump_open(void)
{
    const char *p = getenv("SACD_DUMP_RAW");
    if (p) g_dump = fopen(p, "wb");
}
static void sacd_dump_frame(const uint8_t *d, size_t n)
{
    if (g_dump) fwrite(d, 1, n, g_dump);
}

#define MAX_PROCESSING_BLOCK_SIZE 512

/* Decimation (native 352.8 kHz -> 176.4 kHz) anti-alias low-pass filter.
 * 255-tap windowed-sinc, fc = 78 kHz @ 352800 Hz sampling. */
#define DSD_FIR_TAPS 255
#define DSD_FIR_TAPS_VEC 256 /* padded to a multiple of 4 for NEON (last coeff = 0) */
#define DSD_FIR_DELAY (DSD_FIR_TAPS / 2) /* group delay in input samples */
#define DSD_FIR_HIST (DSD_FIR_TAPS - 1)

static float g_dsd_fir[DSD_FIR_TAPS_VEC];
static int g_dsd_fir_init = 0;

#if defined(__aarch64__)
#include <arm_neon.h>
/* 256-tap contiguous dot product, fully NEON (4-wide, no tail). */
static inline float fir_dot256(const float *coeff, const float *win)
{
    float32x4_t acc = vdupq_n_f32(0.0f);
    for (int i = 0; i < 256; i += 4) {
        float32x4_t a = vld1q_f32(coeff + i);
        float32x4_t b = vld1q_f32(win + i);
        acc = vmlaq_f32(acc, a, b);
    }
    return vaddvq_f32(acc);
}
#else
static inline float fir_dot256(const float *coeff, const float *win)
{
    float r = 0.0f;
    for (int i = 0; i < 255; i++) r += coeff[i] * win[i];
    return r;
}
#endif

static void dsd_fir_init_coeffs(void)
{
    if (g_dsd_fir_init) return;
    const double fc = 78000.0, fs = 352800.0;
    const double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
    const double m = (double)(DSD_FIR_TAPS - 1) / 2.0;
    double sum = 0.0;
    for (int i = 0; i < DSD_FIR_TAPS; i++) {
        double x = i - m;
        double sinc = (x == 0.0) ? 1.0 : sin(M_PI * 2.0 * fc / fs * x) /
                                        (M_PI * 2.0 * fc / fs * x);
        double w = a0 - a1 * cos(2.0 * M_PI * i / (DSD_FIR_TAPS - 1))
                       + a2 * cos(4.0 * M_PI * i / (DSD_FIR_TAPS - 1))
                       - a3 * cos(6.0 * M_PI * i / (DSD_FIR_TAPS - 1));
        g_dsd_fir[i] = (float)(sinc * w);
        sum += g_dsd_fir[i];
    }
    for (int i = 0; i < DSD_FIR_TAPS; i++) g_dsd_fir[i] = (float)(g_dsd_fir[i] / sum);
    g_dsd_fir[DSD_FIR_TAPS] = 0.0f; /* padding for the NEON 256-tap dot */
    g_dsd_fir_init = 1;
}

struct sacd_pcm_reader {
    sacd_reader_t *sacd;
    scarletbook_handle_t *sb;

    /* Source: either a local image path or a callback-backed reader. */
    int is_cb;
    char *iso_path;
    sacd_block_read_fn cb_read;
    sacd_block_size_fn cb_size;
    void *cb_opaque;

    int area;
    int track;

    uint64_t emitted;   /* output frames already handed to the caller */

    int channels;   /* channel count of the area */
    int dst;        /* 1 if DST-compressed */
    int out_hz;     /* requested output rate */
    int decim;      /* native_hz / out_hz (>=1) */
    int native_hz;  /* dsd2pcm output rate = dsd_rate >> 3 */

    uint32_t start_lsn;
    uint32_t length_lsn;
    uint32_t cur_lsn;
    uint32_t sys_start, sys_end; /* track timecode window (frames) */

    uint32_t enc_start_1, enc_end_1, enc_start_2, enc_end_2;
    int checked_non_encrypted;
    int non_encrypted;

    dst_decoder_t *dst_dec;
    DSDContext dsdctx[2]; /* per-channel DSD->PCM state (stereo) */

    float *chbuf[2];      /* per-channel native-rate scratch */
    int    chbuf_cap;

    float  *fir_hist[2];  /* per-channel FIR history (stereo), DSD_FIR_HIST */
    float  *fir_scratch[2]; /* per-channel FIR staging: history tail + current frame */
    int     fir_scratch_cap;
    size_t  fir_off;      /* bytes_per_ch of the current frame (decoder grid) */

    float  *obuf;         /* output FIFO: decimated, channel-interleaved */
    size_t  obuf_cap, obuf_rd, obuf_wr;

    /* Channel-parallel decode workers (one per channel; DSD2PCM + FIR are
     * independent across channels, and there are idle cores on the device). */
    int  workers_started;
    pthread_t worker[2];
    pthread_mutex_t w_mtx[2];
    pthread_cond_t w_cv[2];
    int  w_go[2];      /* 1 = work pending for channel c */
    int  w_stop[2];
    struct { sacd_pcm_reader_t *r; int c; } w_arg[2];
    const uint8_t *w_frame;
    size_t w_bpc;
    size_t w_nout;
    size_t w_obase;

    uint8_t *read_buf;
    uint64_t total_frames; /* expected output frames */
    int done;
    int error;
};

static void frame_decoded_cb(uint8_t *frame_data, size_t frame_size, void *userdata);
static void frame_read_cb(scarletbook_handle_t *handle, uint8_t *frame_data,
                          size_t frame_size, void *userdata);
static void frame_error_cb(int frame_count, int frame_error_code,
                           const char *msg, void *userdata);

static int dsd_rate_for_area(const scarletbook_handle_t *sb, int area)
{
    (void)sb;
    (void)area;
    return 2822400; /* DSD64 - libsacd hard-codes FRAME_SIZE_64 */
}

/* ------------------------------------------------------------------ */
static int sacd_album_info_parse(sacd_reader_t *sacd, int area, sacd_album_info_t *out)
{
    if (!sacd || !out || (area < 0 || area > 3)) return -1;
    memset(out, 0, sizeof(*out));

    scarletbook_handle_t *sb = scarletbook_open(sacd);
    if (!sb) return -1;

    int a = -1;
    if (area < sb->area_count && sb->area[area].area_toc &&
        sb->area[area].area_toc->channel_count > 0) {
        a = area;
    }
    if (a < 0) {
        scarletbook_close(sb); return -1;
    }

    const area_toc_t *toc = sb->area[a].area_toc;
    out->area = a;
    out->track_count = toc->track_count;
    out->channel_count = toc->channel_count;
    out->dsd_rate_hz = dsd_rate_for_area(sb, a);
    out->dst = (toc->frame_format == FRAME_FORMAT_DST);

    if (sb->master_text.album_title)  out->album_title  = strdup(sb->master_text.album_title);
    if (sb->master_text.album_artist) out->album_artist = strdup(sb->master_text.album_artist);

    size_t n = (size_t)out->track_count;
    out->track_title      = calloc(n, sizeof(char *));
    out->track_artist     = calloc(n, sizeof(char *));
    out->track_start_lsn  = calloc(n, sizeof(uint32_t));
    out->track_length_lsn = calloc(n, sizeof(uint32_t));
    out->track_duration_ms = calloc(n, sizeof(uint64_t));

    for (int t = 0; t < out->track_count; t++) {
        char *tt = sb->area[a].area_track_text[t].track_type_title;
        char *tp = sb->area[a].area_track_text[t].track_type_performer;
        if (tt && *tt) out->track_title[t]  = strdup(tt);
        if (tp && *tp) out->track_artist[t] = strdup(tp);

        uint32_t dur = (uint32_t)sb->area[a].area_tracklist_time->duration[t].minutes * 60 * SACD_FRAME_RATE +
                       (uint32_t)sb->area[a].area_tracklist_time->duration[t].seconds * SACD_FRAME_RATE +
                       (uint32_t)sb->area[a].area_tracklist_time->duration[t].frames;
        out->track_start_lsn[t]   = sb->area[a].area_tracklist_offset->track_start_lsn[t];
        out->track_length_lsn[t]  = sb->area[a].area_tracklist_offset->track_length_lsn[t];
        out->track_duration_ms[t] = (uint64_t)dur * 1000 / SACD_FRAME_RATE;
    }

    scarletbook_close(sb);
    return 0;
}

int sacd_album_info_open(const char *iso_path, int area, sacd_album_info_t *out)
{
    if (!iso_path) return -1;

    sacd_init_once();
    sacd_dump_open();

    sacd_reader_t *sacd = sacd_open(iso_path);
    if (!sacd) return -1;

    int rc = sacd_album_info_parse(sacd, area, out);
    sacd_close(sacd);
    return rc;
}

int sacd_album_info_open_cb(sacd_block_read_fn read, sacd_block_size_fn size,
                            void *opaque, int area, sacd_album_info_t *out)
{
    if (!read || !size) return -1;

    sacd_init_once();
    sacd_dump_open();

    sacd_reader_t *sacd = sacd_open_cb(read, size, opaque);
    if (!sacd) return -1;

    int rc = sacd_album_info_parse(sacd, area, out);
    sacd_close(sacd);
    return rc;
}

void sacd_album_info_free(sacd_album_info_t *info)
{
    if (!info) return;
    free(info->album_title);
    free(info->album_artist);
    for (int t = 0; t < info->track_count; t++) {
        free(info->track_title[t]);
        free(info->track_artist[t]);
    }
    free(info->track_title);
    free(info->track_artist);
    free(info->track_start_lsn);
    free(info->track_length_lsn);
    free(info->track_duration_ms);
    memset(info, 0, sizeof(*info));
}

/* ------------------------------------------------------------------ */
/* Common pipeline setup; assumes source / area / track / out_hz set.  */
/* Returns 0 on success; on failure leaves partial state for            */
/* sacd_pcm_close() to release.                                        */
static int dsd_workers_start(sacd_pcm_reader_t *r);
static void dsd_workers_stop(sacd_pcm_reader_t *r);

static int sacd_pcm_setup(sacd_pcm_reader_t *r)
{
    r->sacd = r->is_cb ? sacd_open_cb(r->cb_read, r->cb_size, r->cb_opaque)
                       : sacd_open(r->iso_path);
    if (!r->sacd) goto fail;
    r->sb = scarletbook_open(r->sacd);
    if (!r->sb) goto fail;

    if (r->area < 0 || r->area >= r->sb->area_count) goto fail;
    const area_toc_t *toc = r->sb->area[r->area].area_toc;
    if (!toc || r->track >= toc->track_count) goto fail;

    r->channels = toc->channel_count;
    if (r->channels != 2) { /* bridge targets the stereo (2ch) area */ goto fail; }
    r->dst = (toc->frame_format == FRAME_FORMAT_DST);

    r->native_hz = dsd_rate_for_area(r->sb, r->area) >> 3; /* DSD64 -> 352800 */
    r->out_hz = (r->out_hz > 0) ? r->out_hz : SACD_DEFAULT_OUT_HZ;
    if (r->native_hz % r->out_hz != 0) goto fail;
    r->decim = r->native_hz / r->out_hz;
    if (r->decim < 1) goto fail;

    r->start_lsn  = r->sb->area[r->area].area_tracklist_offset->track_start_lsn[r->track];
    r->length_lsn = r->sb->area[r->area].area_tracklist_offset->track_length_lsn[r->track];
    r->cur_lsn = r->start_lsn;

    uint32_t s = TIME_FRAMECOUNT(&r->sb->area[r->area].area_tracklist_time->start[r->track]);
    uint32_t d = TIME_FRAMECOUNT(&r->sb->area[r->area].area_tracklist_time->duration[r->track]);
    r->sys_start = s;
    r->sys_end = s + d;

    if (r->sb->area[0].area_toc) {
        r->enc_start_1 = r->sb->area[0].area_toc->track_start;
        r->enc_end_1   = r->sb->area[0].area_toc->track_end;
    }
    if (r->sb->area[1].area_toc) {
        r->enc_start_2 = r->sb->area[1].area_toc->track_start;
        r->enc_end_2   = r->sb->area[1].area_toc->track_end;
    }

    if (r->dst) {
        r->dst_dec = dst_decoder_create(r->channels, frame_decoded_cb,
                                        frame_error_cb, r);
        if (!r->dst_dec) goto fail;
    }

    ff_init_dsd_data();

    r->chbuf_cap = 4704; /* one DSD64 frame per channel */
    for (int c = 0; c < 2; c++) {
        r->chbuf[c] = malloc(sizeof(float) * (size_t)r->chbuf_cap);
        if (!r->chbuf[c]) goto fail;
    }
    dsd_fir_init_coeffs();
    for (int c = 0; c < 2; c++) {
        r->fir_hist[c] = calloc(DSD_FIR_HIST, sizeof(float));
        if (!r->fir_hist[c]) goto fail;
    }
    r->fir_scratch_cap = (int)(DSD_FIR_HIST + (size_t)r->chbuf_cap + 8);
    for (int c = 0; c < 2; c++) {
        r->fir_scratch[c] = malloc(sizeof(float) * (size_t)r->fir_scratch_cap);
        if (!r->fir_scratch[c]) goto fail;
    }
    r->read_buf = malloc((size_t)MAX_PROCESSING_BLOCK_SIZE * SACD_LSN_SIZE);
    if (!r->read_buf) goto fail;
    r->obuf_cap = (size_t)4096 * 2; /* stereo floats */
    r->obuf = malloc(sizeof(float) * r->obuf_cap);
    if (!r->obuf) goto fail;
    r->obuf_rd = 0;
    r->obuf_wr = 0;
    dsd_workers_start(r); /* best-effort; falls back to inline channels */

    r->total_frames = (uint64_t)d * (uint64_t)r->out_hz / SACD_FRAME_RATE;
    r->emitted = 0;
    r->done = 0;
    r->error = 0;
    return 0;

fail:
    return -1;
}

sacd_pcm_reader_t *sacd_pcm_open(const char *iso_path, int area, int track,
                                 int out_hz)
{
    if (!iso_path || track < 0) return NULL;

    sacd_pcm_reader_t *r = calloc(1, sizeof(*r));
    if (!r) return NULL;

    r->iso_path = strdup(iso_path);
    if (!r->iso_path) { free(r); return NULL; }
    r->area = area;
    r->track = track;
    r->out_hz = out_hz;

    sacd_init_once();
    sacd_dump_open();

    if (sacd_pcm_setup(r) != 0) {
        sacd_pcm_close(r);
        return NULL;
    }
    return r;
}

sacd_pcm_reader_t *sacd_pcm_open_cb(sacd_block_read_fn read, sacd_block_size_fn size,
                                    void *opaque, int area, int track, int out_hz)
{
    if (!read || !size || track < 0) return NULL;

    sacd_pcm_reader_t *r = calloc(1, sizeof(*r));
    if (!r) return NULL;

    r->is_cb = 1;
    r->cb_read = read;
    r->cb_size = size;
    r->cb_opaque = opaque;
    r->area = area;
    r->track = track;
    r->out_hz = out_hz;

    sacd_init_once();
    sacd_dump_open();

    if (sacd_pcm_setup(r) != 0) {
        sacd_pcm_close(r);
        return NULL;
    }
    return r;
}

static void sacd_pcm_release(sacd_pcm_reader_t *r)
{
    if (!r) return;
    dsd_workers_stop(r);
    if (r->dst_dec) dst_decoder_destroy(r->dst_dec);
    if (r->sb) scarletbook_close(r->sb);
    if (r->sacd) sacd_close(r->sacd);
    for (int c = 0; c < 2; c++) free(r->chbuf[c]);
    for (int c = 0; c < 2; c++) free(r->fir_hist[c]);
    for (int c = 0; c < 2; c++) free(r->fir_scratch[c]);
    free(r->read_buf);
    free(r->obuf);
}

void sacd_pcm_close(sacd_pcm_reader_t *r)
{
    if (!r) return;
    sacd_pcm_release(r);
    free(r->iso_path);
    free(r);
}

int sacd_pcm_seek_output_frame(sacd_pcm_reader_t *r, unsigned long long target)
{
    if (!r || r->error) return -1;
    if (target > r->total_frames) target = r->total_frames;

    if (target < r->emitted) {
        sacd_pcm_release(r);
        r->dst_dec = NULL;
        r->sb = NULL;
        r->sacd = NULL;
        for (int c = 0; c < 2; c++) r->chbuf[c] = NULL;
        for (int c = 0; c < 2; c++) r->fir_hist[c] = NULL;
        for (int c = 0; c < 2; c++) r->fir_scratch[c] = NULL;
        r->fir_scratch_cap = 0;
        r->workers_started = 0;
        r->read_buf = NULL;
        r->obuf = NULL;
        if (sacd_pcm_setup(r) != 0) { r->error = -1; return -1; }
    }

    uint64_t want = target - r->emitted;
    if (want == 0) return 0;

    long chunk = 4096;
    float *tmp = malloc(sizeof(float) * (size_t)chunk * 2u);
    if (!tmp) return -1;
    while (want > 0 && !r->error) {
        long n = sacd_pcm_read(r, tmp, (want < (uint64_t)chunk) ? (long)want : chunk);
        if (n <= 0) break;
        want -= (uint64_t)n;
    }
    free(tmp);
    return r->error ? -1 : 0;
}

int sacd_pcm_out_rate(sacd_pcm_reader_t *r) { return r ? r->out_hz : 0; }
int sacd_pcm_channels(sacd_pcm_reader_t *r) { return r ? r->channels : 0; }
uint64_t sacd_pcm_output_frames(sacd_pcm_reader_t *r)
{
    return r ? r->total_frames : 0;
}
uint64_t sacd_pcm_duration_ms(sacd_pcm_reader_t *r)
{
    return r ? (uint64_t)(r->sys_end - r->sys_start) * 1000 / SACD_FRAME_RATE : 0;
}

/* ------------------------------------------------------------------ */
/* DSD frame (byte-interleaved per channel, LSB-first) -> decimated,   */
/* interleaved float32 PCM into the output FIFO.                       */

/* Per-channel DSD->PCM + decimating FIR. Writes its channel's values into
 * the shared interleaved obuf at obase + m*ch + c (distinct slots per
 * channel, so no cross-channel writes race). */
static void channel_work(sacd_pcm_reader_t *r, int c, const uint8_t *fd,
                         size_t bytes_per_ch, size_t nout, size_t obase)
{
    ff_dsd2pcm_translate(&r->dsdctx[c], bytes_per_ch, 0 /*lsbf; disc bytes are
                                 LSB-first, so the msbf tables (== REV+lsbf1)
                                 match what the DSF writer/ffmpeg produce */,
                         fd + c, r->channels, r->chbuf[c], 1);

    if (r->decim == 2 && bytes_per_ch == (size_t)r->chbuf_cap) {
        /* Anti-aliased decimate by 2: streaming 255-tap low-pass FIR with a
         * constant group delay of DSD_FIR_DELAY input samples. Output m is
         * computed from input window [2m-(TAPS-1), 2m]; a per-channel staging
         * buffer holds the previous frame's tail followed by the current
         * frame, so every window is contiguous and the dot product is a single
         * aligned NEON loop over 256 taps (255 real + 1 zero pad). */
        const size_t hist_n = DSD_FIR_TAPS - 1;
        float *scr = r->fir_scratch[c];
        memcpy(scr, r->fir_hist[c], hist_n * sizeof(float));
        memcpy(scr + hist_n, r->chbuf[c], bytes_per_ch * sizeof(float));
        size_t base = obase + (size_t)c;
        for (size_t m = 0; m < nout; m++) {
            r->obuf[base + m * (size_t)r->channels] = fir_dot256(g_dsd_fir, scr + 2 * m);
        }
        memcpy(r->fir_hist[c], scr + bytes_per_ch, hist_n * sizeof(float));
    } else if (r->decim == 1) {
        size_t base = obase + (size_t)c;
        for (size_t n = 0; n < bytes_per_ch; n++) r->obuf[base + n * (size_t)r->channels] = r->chbuf[c][n];
    } else {
        size_t base = obase + (size_t)c;
        for (size_t n = 0; n < bytes_per_ch; n += (size_t)r->decim)
            r->obuf[base + (n / (size_t)r->decim) * (size_t)r->channels] = r->chbuf[c][n];
    }
}

static void *dsd_worker_main(void *arg)
{
    sacd_pcm_reader_t *r = ((struct { sacd_pcm_reader_t *r; int c; } *)arg)->r;
    int c = ((struct { sacd_pcm_reader_t *r; int c; } *)arg)->c;
    pthread_mutex_lock(&r->w_mtx[c]);
    for (;;) {
        while (!r->w_go[c] && !r->w_stop[c]) pthread_cond_wait(&r->w_cv[c], &r->w_mtx[c]);
        if (r->w_stop[c]) break;
        pthread_mutex_unlock(&r->w_mtx[c]);
        channel_work(r, c, r->w_frame, r->w_bpc, r->w_nout, r->w_obase);
        pthread_mutex_lock(&r->w_mtx[c]);
        r->w_go[c] = 0;
        pthread_cond_broadcast(&r->w_cv[c]);
    }
    pthread_mutex_unlock(&r->w_mtx[c]);
    return NULL;
}

/* Starts the per-channel worker threads (stereo). Idempotent; returns 0 on
 * success. On failure the decode falls back to running channels inline. */
static int dsd_workers_start(sacd_pcm_reader_t *r)
{
    if (r->workers_started) return 0;
    for (int c = 0; c < r->channels && c < 2; c++) {
        pthread_mutex_init(&r->w_mtx[c], NULL);
        pthread_cond_init(&r->w_cv[c], NULL);
        r->w_go[c] = 0;
        r->w_stop[c] = 0;
        r->w_arg[c].r = r;
        r->w_arg[c].c = c;
        if (pthread_create(&r->worker[c], NULL, dsd_worker_main, &r->w_arg[c]) != 0) {
            r->w_stop[c] = 1;
            return -1;
        }
    }
    r->workers_started = 1;
    return 0;
}

static void dsd_workers_stop(sacd_pcm_reader_t *r)
{
    if (!r->workers_started) return;
    for (int c = 0; c < 2; c++) {
        pthread_mutex_lock(&r->w_mtx[c]);
        r->w_stop[c] = 1;
        pthread_cond_broadcast(&r->w_cv[c]);
        pthread_mutex_unlock(&r->w_mtx[c]);
        pthread_join(r->worker[c], NULL);
    }
    r->workers_started = 0;
}

/* Dispatches one DSD frame's per-channel work to the workers and waits. */
static void dsd_dispatch(sacd_pcm_reader_t *r, const uint8_t *frame_data,
                         size_t bytes_per_ch, size_t nout, size_t obase)
{
    r->w_frame = frame_data;
    r->w_bpc = bytes_per_ch;
    r->w_nout = nout;
    r->w_obase = obase;
    if (r->workers_started && r->channels >= 2) {
        for (int c = 0; c < r->channels && c < 2; c++) {
            pthread_mutex_lock(&r->w_mtx[c]);
            r->w_go[c] = 1;
            pthread_cond_broadcast(&r->w_cv[c]);
            pthread_mutex_unlock(&r->w_mtx[c]);
        }
        for (int c = 0; c < r->channels && c < 2; c++) {
            pthread_mutex_lock(&r->w_mtx[c]);
            while (r->w_go[c]) pthread_cond_wait(&r->w_cv[c], &r->w_mtx[c]);
            pthread_mutex_unlock(&r->w_mtx[c]);
        }
    } else {
        for (int c = 0; c < r->channels && c < 2; c++) {
            channel_work(r, c, frame_data, bytes_per_ch, nout, obase);
        }
    }
}

static void consume_dsd_frame(sacd_pcm_reader_t *r, const uint8_t *frame_data,
                              size_t frame_size)
{
    int ch = r->channels;
    sacd_dump_frame(frame_data, frame_size);
    size_t bytes_per_ch = frame_size / (size_t)ch;
    if (bytes_per_ch == 0 || bytes_per_ch > (size_t)r->chbuf_cap) return;

    size_t nout = bytes_per_ch / (size_t)r->decim; /* output frames this frame */
    if (r->obuf_wr + nout * (size_t)ch > r->obuf_cap) {
        size_t need = r->obuf_wr + nout * (size_t)ch;
        size_t cap = r->obuf_cap ? r->obuf_cap : 64;
        while (cap < need) cap *= 2;
        float *nbuf = realloc(r->obuf, sizeof(float) * cap);
        if (!nbuf) { r->error = -1; return; }
        r->obuf = nbuf;
        r->obuf_cap = cap;
    }

    size_t w = r->obuf_wr;
    dsd_dispatch(r, frame_data, bytes_per_ch, nout, w);
    r->obuf_wr = w + nout * (size_t)ch;
}

/* ------------------------------------------------------------------ */
long sacd_pcm_read(sacd_pcm_reader_t *r, float *out, long frames)
{
    if (!r || !out || frames < 0) return -1;
    if (r->error) return -1;
    if (r->done && r->obuf_rd >= r->obuf_wr) return 0;

    const uint32_t end_lsn = r->start_lsn + r->length_lsn;
    int ch = r->channels;
    long written = 0;

    while (written < frames) {
        /* drain what we have */
        size_t avail = r->obuf_wr - r->obuf_rd;
        if (avail > 0) {
            long n = (avail / (size_t)ch) < (size_t)(frames - written)
                     ? (long)(avail / (size_t)ch) : (frames - written);
            memcpy(out + (size_t)written * (size_t)ch,
                   r->obuf + r->obuf_rd, (size_t)n * (size_t)ch * sizeof(float));
            r->obuf_rd += (size_t)n * (size_t)ch;
            written += n;
            if (r->obuf_rd >= r->obuf_wr) r->obuf_rd = r->obuf_wr = 0;
            if (written >= frames) break;
        }

        if (r->done) break;

        /* decode the next block of sectors */
        if (r->cur_lsn < end_lsn) {
            uint32_t block_size;
            int encrypted;
            if (r->cur_lsn < r->enc_start_1) {
                block_size = (r->enc_start_1 - r->cur_lsn < MAX_PROCESSING_BLOCK_SIZE)
                             ? r->enc_start_1 - r->cur_lsn : MAX_PROCESSING_BLOCK_SIZE;
                encrypted = 0;
            } else if (r->cur_lsn >= r->enc_start_1 && r->cur_lsn <= r->enc_end_1) {
                block_size = (r->enc_end_1 + 1 - r->cur_lsn < MAX_PROCESSING_BLOCK_SIZE)
                             ? r->enc_end_1 + 1 - r->cur_lsn : MAX_PROCESSING_BLOCK_SIZE;
                encrypted = 1;
            } else if (r->cur_lsn > r->enc_end_1 && r->cur_lsn < r->enc_start_2) {
                block_size = (r->enc_start_2 - r->cur_lsn < MAX_PROCESSING_BLOCK_SIZE)
                             ? r->enc_start_2 - r->cur_lsn : MAX_PROCESSING_BLOCK_SIZE;
                encrypted = 0;
            } else if (r->cur_lsn >= r->enc_start_2 && r->cur_lsn <= r->enc_end_2) {
                block_size = (r->enc_end_2 + 1 - r->cur_lsn < MAX_PROCESSING_BLOCK_SIZE)
                             ? r->enc_end_2 + 1 - r->cur_lsn : MAX_PROCESSING_BLOCK_SIZE;
                encrypted = 1;
            } else {
                block_size = MAX_PROCESSING_BLOCK_SIZE;
                encrypted = 0;
            }
            if (block_size > end_lsn - r->cur_lsn)
                block_size = end_lsn - r->cur_lsn;
            if (block_size == 0) { r->done = 1; break; }

            uint32_t got = sacd_read_block_raw(r->sacd, r->cur_lsn, block_size, r->read_buf);
            if (got == 0) { r->error = -1; return -1; }
            r->cur_lsn += got;

            if (encrypted && !r->checked_non_encrypted) {
                switch (r->sb->area[r->area].area_toc->frame_format) {
                case FRAME_FORMAT_DSD_3_IN_14:
                case FRAME_FORMAT_DSD_3_IN_16:
                    r->non_encrypted = (*(uint64_t *)(r->read_buf + 16) == 0);
                    break;
                default:
                    break;
                }
                r->checked_non_encrypted = 1;
            }
            if (encrypted && !r->non_encrypted) {
                sacd_decrypt(r->sacd, r->read_buf, got);
            }

            int lbs = (r->cur_lsn >= end_lsn);
            int rc = scarletbook_process_frames(r->sb, r->read_buf, (int)got, lbs,
                                                 frame_read_cb, r);
            if (rc < 0 || r->error) {
                r->error = -1;
                r->emitted += (uint64_t)written;
                return written > 0 ? written : -1;
            }
        } else {
            r->done = 1;
        }
        if (written == 0 && r->done && r->obuf_rd >= r->obuf_wr) break; /* drained */
    }
    r->emitted += (uint64_t)written;
    return written;
}

/* ---------- callbacks ------------------------------------------------ */

static void frame_decoded_cb(uint8_t *frame_data, size_t frame_size, void *userdata)
{
    sacd_pcm_reader_t *r = (sacd_pcm_reader_t *)userdata;
    if (!r || r->error) return;
    consume_dsd_frame(r, frame_data, frame_size);
}

static void frame_error_cb(int frame_count, int frame_error_code,
                           const char *msg, void *userdata)
{
    (void)frame_count; (void)frame_error_code; (void)userdata;
    fprintf(stderr, "DST decode error: %s\n", msg ? msg : "?");
}

static void frame_read_cb(scarletbook_handle_t *handle, uint8_t *frame_data,
                          size_t frame_size, void *userdata)
{
    sacd_pcm_reader_t *r = (sacd_pcm_reader_t *)userdata;
    if (!r || r->error) return;

    uint32_t tc = TIME_FRAMECOUNT(&handle->frame.timecode);
    if (r->sys_start > 0) {
        if (tc < r->sys_start || tc >= r->sys_end) return; /* trim */
    }

    if (r->dst) {
        dst_decoder_decode(r->dst_dec, frame_data, frame_size);
    } else {
        consume_dsd_frame(r, frame_data, frame_size);
    }
}