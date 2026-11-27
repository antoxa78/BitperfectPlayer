/*
 * SACD bridge: SACD ISO -> PCM (DSD/DST decode + DSD->PCM) for BitperfectPlayer.
 *
 * Uses GPL-2.0 code from sacd-ripper (libsacd / libdstdec / libcommon) and
 * LGPL-2.1 code from FFmpeg (libavcodec/dsd.c) for the DSD->PCM stage.
 * See third_party/sacd/COPYING and third_party/ffmpeg_dsd/COPYING.LGPLv2.1.
 *
 * Threading model
 * ---------------
 * A reader is driven by one caller thread (the extractor's loading thread).
 * Plain-DSD frames are converted on that thread. DST frames are decoded by
 * libdstdec's own thread pool and handed back on its write thread, so the
 * output FIFO (obuf / obuf_rd / obuf_wr / discard_out) and the DST in-flight
 * counters are guarded by fifo_mtx; the reader waits on fifo_cv for DST
 * frames still in the pipeline (in particular at the end of a track, so the
 * last frames are never cut off).
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
#include "dsd_conv.h"
#include "logging.h" /* init_logging, lm_main */

#define BRIDGE_TAG "SacdPcm"
#define BLOGI(...) __android_log_print(ANDROID_LOG_INFO, BRIDGE_TAG, __VA_ARGS__)
#define BLOGW(...) __android_log_print(ANDROID_LOG_WARN, BRIDGE_TAG, __VA_ARGS__)

/* ---------- one-time process init --------------------------------------- */

static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;
static FILE *g_dump = NULL; /* debug hook (PoC only): raw DSD frames */

static void sacd_init_impl(void);

static void sacd_init_once(void)
{
    pthread_once(&g_init_once, sacd_init_impl);
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

/* DSD bytes per channel in one SACD frame (1/75 s of DSD64). */
#define SACD_FRAME_BYTES_PER_CH FRAME_SIZE_64

/* Cap on DST frames submitted but not yet delivered (the decoder's own input
 * pool already bounds this; this keeps the reader from racing far ahead). */
#define DST_MAX_INFLIGHT 64

/* Forward seeks up to this many seconds are done by dropping output (keeps the
 * filters continuous); longer or backward seeks jump to the sector. */
#define SEEK_DISCARD_MAX_SEC 2

static float g_dsd_fir[DSD_FIR_TAPS_VEC];

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
#elif defined(__ARM_NEON)
#include <arm_neon.h>
/* armeabi-v7a: same dot product with ARMv7 NEON (no vaddvq). */
static inline float fir_dot256(const float *coeff, const float *win)
{
    float32x4_t acc = vdupq_n_f32(0.0f);
    for (int i = 0; i < 256; i += 4) {
        acc = vmlaq_f32(acc, vld1q_f32(coeff + i), vld1q_f32(win + i));
    }
    float32x2_t s = vadd_f32(vget_low_f32(acc), vget_high_f32(acc));
    s = vpadd_f32(s, s);
    return vget_lane_f32(s, 0);
}
#else
static inline float fir_dot256(const float *coeff, const float *win)
{
    float r = 0.0f;
    for (int i = 0; i < 255; i++) r += coeff[i] * win[i];
    return r;
}
#endif

/* Runs exactly once (pthread_once): the coefficient table is shared by every
 * reader, and two readers opening concurrently used to be able to normalize
 * it twice (a permanently wrong filter gain for the rest of the process). */
static void dsd_fir_init_coeffs(void)
{
    const double fc = 78000.0, fs = 352800.0;
    const double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
    const double m = (double)(DSD_FIR_TAPS - 1) / 2.0;
    double h[DSD_FIR_TAPS];
    double sum = 0.0;
    for (int i = 0; i < DSD_FIR_TAPS; i++) {
        double x = i - m;
        double sinc = (x == 0.0) ? 1.0 : sin(M_PI * 2.0 * fc / fs * x) /
                                        (M_PI * 2.0 * fc / fs * x);
        double w = a0 - a1 * cos(2.0 * M_PI * i / (DSD_FIR_TAPS - 1))
                       + a2 * cos(4.0 * M_PI * i / (DSD_FIR_TAPS - 1))
                       - a3 * cos(6.0 * M_PI * i / (DSD_FIR_TAPS - 1));
        h[i] = sinc * w;
        sum += h[i];
    }
    for (int i = 0; i < DSD_FIR_TAPS; i++) g_dsd_fir[i] = (float)(h[i] / sum);
    g_dsd_fir[DSD_FIR_TAPS] = 0.0f; /* padding for the NEON 256-tap dot */
}

static void sacd_init_impl(void)
{
    init_logging(1);
    ff_init_dsd_data();
    dsd_fir_init_coeffs();
    const char *p = getenv("SACD_DUMP_RAW");
    if (p) g_dump = fopen(p, "wb");
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
    int spf;        /* output frames per SACD frame (= 4704 / decim) */
    int dop;        /* 1 = emit DoP (DSD over PCM) instead of converting to PCM */
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
    dsd_conv_t *conv;     /* PCM path for ratios other than 2:1 (own anti-alias filter) */

    float *chbuf[2];      /* per-channel native-rate scratch */
    int    chbuf_cap;

    float  *fir_hist[2];  /* per-channel FIR history (stereo), DSD_FIR_HIST */
    float  *fir_scratch[2]; /* per-channel FIR staging: history tail + current frame */
    int     fir_scratch_cap;

    /* ── Output FIFO + DST bookkeeping: guarded by fifo_mtx ── */
    pthread_mutex_t fifo_mtx;
    pthread_cond_t  fifo_cv;
    int     fifo_sync_ready;
    float  *obuf;         /* output FIFO: decimated, channel-interleaved */
    size_t  obuf_cap, obuf_rd, obuf_wr;
    uint64_t discard_out; /* output frames to drop from the FIFO head (seek) */
    uint64_t dst_submitted, dst_delivered;
    int     dst_discard;  /* drop frames delivered while tearing the decoder down */
    int     error;        /* fatal (decoder / OOM); I/O errors are retryable */

    /* Seek state (reader thread only). */
    uint32_t skip_before_tc; /* frames with an earlier timecode are dropped (0 = off) */
    uint32_t first_tc_seen;  /* first frame timecode after a seek (UINT32_MAX = none yet) */

    /* Channel-parallel decode workers (one per channel; DSD2PCM + FIR are
     * independent across channels, and there are idle cores on the device). */
    int  workers_started;
    int  workers_created; /* number of worker threads actually created */
    pthread_t worker[2];
    pthread_mutex_t w_mtx[2];
    pthread_cond_t w_cv[2];
    int  w_go[2];      /* 1 = work pending for channel c */
    int  w_stop[2];
    struct worker_arg { sacd_pcm_reader_t *r; int c; } w_arg[2];
    const uint8_t *w_frame;
    size_t w_bpc;
    size_t w_nout;
    size_t w_obase;

    uint8_t *read_buf;
    uint64_t total_frames; /* expected output frames */
    int done;
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

/* DSD "silence" is the idle pattern 0x69, not all-zero bits: a zeroed FIFO
 * is full-scale negative and makes the first samples thump. */
static void dsd_ctx_reset(DSDContext *c)
{
    memset(c, 0, sizeof(*c));
    memset(c->buf, 0x69, sizeof(c->buf));
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
    if (n > 0) {
        out->track_title      = calloc(n, sizeof(char *));
        out->track_artist     = calloc(n, sizeof(char *));
        out->track_start_lsn  = calloc(n, sizeof(uint32_t));
        out->track_length_lsn = calloc(n, sizeof(uint32_t));
        out->track_duration_ms = calloc(n, sizeof(uint64_t));
        if (!out->track_title || !out->track_artist || !out->track_start_lsn ||
            !out->track_length_lsn || !out->track_duration_ms) {
            scarletbook_close(sb);
            sacd_album_info_free(out);
            return -1;
        }
    }

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
        if (info->track_title)  free(info->track_title[t]);
        if (info->track_artist) free(info->track_artist[t]);
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
    if (r->out_hz > r->native_hz || r->native_hz % r->out_hz != 0) goto fail;
    r->decim = r->native_hz / r->out_hz;
    /* Every SACD frame must map to a whole number of output frames (seeking
     * and the per-frame output grid rely on it): 1, 2, 3, 4, 6, 7, 8, ... */
    if (r->decim < 1 || SACD_FRAME_BYTES_PER_CH % r->decim != 0) goto fail;
    r->spf = SACD_FRAME_BYTES_PER_CH / r->decim;

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

    for (int c = 0; c < 2; c++) dsd_ctx_reset(&r->dsdctx[c]);

    if (r->decim != 2) {
        /* Sample-dropping without a low-pass would fold DSD's ultrasonic
         * noise into the audio band: use the DSF/DFF converter, which designs
         * a proper anti-alias filter for the ratio. */
        r->conv = dsd_conv_create(r->channels, dsd_rate_for_area(r->sb, r->area), r->out_hz);
        if (!r->conv) goto fail;
    }

    r->chbuf_cap = SACD_FRAME_BYTES_PER_CH; /* one DSD64 frame per channel */
    for (int c = 0; c < 2; c++) {
        r->chbuf[c] = malloc(sizeof(float) * (size_t)r->chbuf_cap);
        if (!r->chbuf[c]) goto fail;
    }
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

    r->total_frames = (uint64_t)d * (uint64_t)r->spf;
    r->emitted = 0;
    r->done = 0;
    r->error = 0;
    r->discard_out = 0;
    r->skip_before_tc = 0;
    r->first_tc_seen = UINT32_MAX;
    return 0;

fail:
    return -1;
}

static int sacd_pcm_alloc_sync(sacd_pcm_reader_t *r)
{
    if (pthread_mutex_init(&r->fifo_mtx, NULL) != 0) return -1;
    if (pthread_cond_init(&r->fifo_cv, NULL) != 0) {
        pthread_mutex_destroy(&r->fifo_mtx);
        return -1;
    }
    r->fifo_sync_ready = 1;
    return 0;
}

sacd_pcm_reader_t *sacd_pcm_open(const char *iso_path, int area, int track,
                                 int out_hz)
{
    if (!iso_path || track < 0) return NULL;

    sacd_pcm_reader_t *r = calloc(1, sizeof(*r));
    if (!r) return NULL;

    r->iso_path = strdup(iso_path);
    if (!r->iso_path || sacd_pcm_alloc_sync(r) != 0) { free(r->iso_path); free(r); return NULL; }
    r->area = area;
    r->track = track;
    r->out_hz = out_hz;

    sacd_init_once();

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
    if (sacd_pcm_alloc_sync(r) != 0) { free(r); return NULL; }

    r->is_cb = 1;
    r->cb_read = read;
    r->cb_size = size;
    r->cb_opaque = opaque;
    r->area = area;
    r->track = track;
    r->out_hz = out_hz;

    sacd_init_once();

    if (sacd_pcm_setup(r) != 0) {
        sacd_pcm_close(r);
        return NULL;
    }
    return r;
}

/* Tears the DST pipeline down; frames still in flight are dropped. */
static void dst_teardown(sacd_pcm_reader_t *r)
{
    if (!r->dst_dec) return;
    pthread_mutex_lock(&r->fifo_mtx);
    r->dst_discard = 1;
    pthread_mutex_unlock(&r->fifo_mtx);
    dst_decoder_destroy(r->dst_dec); /* joins its threads; callbacks see dst_discard */
    r->dst_dec = NULL;
    pthread_mutex_lock(&r->fifo_mtx);
    r->dst_discard = 0;
    r->dst_submitted = 0;
    r->dst_delivered = 0;
    pthread_cond_broadcast(&r->fifo_cv);
    pthread_mutex_unlock(&r->fifo_mtx);
}

static void sacd_pcm_release(sacd_pcm_reader_t *r)
{
    if (!r) return;
    if (r->fifo_sync_ready) dst_teardown(r);
    dsd_workers_stop(r);
    if (r->conv) dsd_conv_destroy(r->conv);
    r->conv = NULL;
    if (r->sb) scarletbook_close(r->sb);
    if (r->sacd) sacd_close(r->sacd);
    r->sb = NULL;
    r->sacd = NULL;
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
    if (r->fifo_sync_ready) {
        pthread_cond_destroy(&r->fifo_cv);
        pthread_mutex_destroy(&r->fifo_mtx);
    }
    free(r->iso_path);
    free(r);
}

int sacd_pcm_out_rate(sacd_pcm_reader_t *r) { return r ? r->out_hz : 0; }

int sacd_pcm_set_dop(sacd_pcm_reader_t *r, int enable)
{
    if (!r) return -1;
    if (!enable) { r->dop = 0; return 0; }
    /* DoP carries 16 DSD bits per sample: the output rate must be exactly
     * dsd_rate / 16, i.e. native (dsd/8) decimated by 2 — 176.4 kHz for DSD64. */
    if (r->channels != 2 || r->decim != 2) return -1;
    if (r->emitted != 0 || r->obuf_wr != 0) return -1; /* only before the first read */
    r->dop = 1;
    return 0;
}

long sacd_pcm_read_dop24(sacd_pcm_reader_t *r, uint8_t *out, long frames)
{
    if (!r || !out || !r->dop) return -1;
    int ch = r->channels;
    /* Read through the normal FIFO (keeps seek/trim/EOF semantics identical),
     * then convert the exactly-stored floats back to packed 24-bit LE. */
    float tmp[1024 * 2];
    long done = 0;
    while (done < frames) {
        long want = frames - done;
        if (want > 1024) want = 1024;
        long n = sacd_pcm_read(r, tmp, want);
        if (n < 0) return done > 0 ? done : -1;
        if (n == 0) break;
        for (long i = 0; i < n * ch; i++) {
            int32_t v = (int32_t)lrintf(tmp[i] * 8388608.0f); /* exact: v / 2^23 was stored */
            uint8_t *o = out + ((size_t)done * (size_t)ch + (size_t)i) * 3u;
            o[0] = (uint8_t)(v & 0xFF);
            o[1] = (uint8_t)((v >> 8) & 0xFF);
            o[2] = (uint8_t)((v >> 16) & 0xFF);
        }
        done += n;
        if (n < want) break; /* short read: end of track or retryable error */
    }
    return done;
}
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
/* DSD frame (byte-interleaved per channel, MSB-first) -> decimated,   */
/* interleaved float32 PCM into the output FIFO.                       */

/* Per-channel DSD->PCM + decimating FIR. Writes its channel's values into
 * the shared interleaved obuf at obase + m*ch + c (distinct slots per
 * channel, so no cross-channel writes race). */
static void channel_work(sacd_pcm_reader_t *r, int c, const uint8_t *fd,
                         size_t bytes_per_ch, size_t nout, size_t obase)
{
    if (r->dop) {
        /* DoP: every output sample carries 16 raw DSD bits of this channel —
         * two consecutive bytes, earlier byte in bits 15..8 — under an 8-bit
         * marker that alternates 0x05 / 0xFA from one sample (frame) to the
         * next. SACD frame bytes are already MSB-first (the order DoP wants),
         * so they are copied untouched: no filtering, no conversion.
         * Stored in the float FIFO as v / 2^23, which is exact for any 24-bit
         * integer and is read back exactly by sacd_pcm_read_dop24(). The
         * marker restarts at 0x05 each frame; frames hold an even number of
         * DoP samples (2352 for DSD64), so the alternation is unbroken. */
        const size_t ch = (size_t)r->channels;
        const uint8_t *src = fd + c;
        size_t base = obase + (size_t)c;
        size_t n = bytes_per_ch / 2;
        if (n > nout) n = nout;
        for (size_t m = 0; m < n; m++) {
            int32_t v = ((m & 1) ? 0xFA : 0x05) << 16
                      | (int32_t)src[(2 * m) * ch] << 8
                      | (int32_t)src[(2 * m + 1) * ch];
            if (v & 0x800000) v -= 0x1000000; /* sign-extend the 24-bit word */
            r->obuf[base + m * ch] = (float)v / 8388608.0f;
        }
        return;
    }
    /* Scarletbook stores DSD MSB-first (earliest bit in bit 7): lsbf = 0. */
    ff_dsd2pcm_translate(&r->dsdctx[c], bytes_per_ch, 0 /* MSB-first */,
                         fd + c, r->channels, r->chbuf[c], 1);

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
}

static void *dsd_worker_main(void *arg)
{
    struct worker_arg *wa = (struct worker_arg *)arg;
    sacd_pcm_reader_t *r = wa->r;
    int c = wa->c;
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
 * success. On failure any already-created workers are stopped and the decode
 * falls back to running channels inline. */
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
            r->workers_created = c; /* workers 0..c-1 exist and must be reaped */
            dsd_workers_stop(r);
            pthread_cond_destroy(&r->w_cv[c]);
            pthread_mutex_destroy(&r->w_mtx[c]);
            return -1;
        }
        r->workers_created++;
    }
    r->workers_started = 1;
    return 0;
}

static void dsd_workers_stop(sacd_pcm_reader_t *r)
{
    for (int c = 0; c < r->workers_created; c++) {
        pthread_mutex_lock(&r->w_mtx[c]);
        r->w_stop[c] = 1;
        pthread_cond_broadcast(&r->w_cv[c]);
        pthread_mutex_unlock(&r->w_mtx[c]);
        pthread_join(r->worker[c], NULL);
        pthread_cond_destroy(&r->w_cv[c]);
        pthread_mutex_destroy(&r->w_mtx[c]);
    }
    r->workers_created = 0;
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

/* Makes room for `floats` more samples in the FIFO. Caller holds fifo_mtx. */
static int fifo_reserve_locked(sacd_pcm_reader_t *r, size_t floats)
{
    size_t need = r->obuf_wr + floats;
    if (need <= r->obuf_cap) return 0;
    size_t cap = r->obuf_cap ? r->obuf_cap : 64;
    while (cap < need) cap *= 2;
    float *nbuf = realloc(r->obuf, sizeof(float) * cap);
    if (!nbuf) { r->error = -1; return -1; }
    r->obuf = nbuf;
    r->obuf_cap = cap;
    return 0;
}

/* Converts one complete DSD frame into the FIFO. Caller holds fifo_mtx. */
static void consume_dsd_frame_locked(sacd_pcm_reader_t *r, const uint8_t *frame_data,
                                     size_t frame_size)
{
    int ch = r->channels;
    sacd_dump_frame(frame_data, frame_size);
    size_t bytes_per_ch = frame_size / (size_t)ch;
    if (bytes_per_ch != (size_t)SACD_FRAME_BYTES_PER_CH) {
        /* Never happens for valid DSD64 / DST frames; the output grid (and
         * therefore seeking) relies on whole frames. */
        BLOGW("unexpected DSD frame size %zu (ch=%d) - frame dropped", frame_size, ch);
        return;
    }

    if (r->conv && !r->dop) {
        size_t maxf = dsd_conv_max_out_frames(r->conv, bytes_per_ch);
        if (fifo_reserve_locked(r, maxf * (size_t)ch) != 0) return;
        long n = dsd_conv_process(r->conv, frame_data, bytes_per_ch, r->obuf + r->obuf_wr);
        if (n < 0) { r->error = -1; return; }
        r->obuf_wr += (size_t)n * (size_t)ch;
        return;
    }

    size_t nout = bytes_per_ch / (size_t)r->decim; /* output frames this frame */
    if (fifo_reserve_locked(r, nout * (size_t)ch) != 0) return;
    size_t w = r->obuf_wr;
    dsd_dispatch(r, frame_data, bytes_per_ch, nout, w);
    r->obuf_wr = w + nout * (size_t)ch;
}

/* Resets everything that depends on the stream position (not the open image)
 * so decoding can restart at any sector. */
static int reset_decode_state(sacd_pcm_reader_t *r)
{
    if (r->dst) {
        dst_teardown(r);
        r->dst_dec = dst_decoder_create(r->channels, frame_decoded_cb, frame_error_cb, r);
        if (!r->dst_dec) {
            pthread_mutex_lock(&r->fifo_mtx);
            r->error = -1;
            pthread_mutex_unlock(&r->fifo_mtx);
            return -1;
        }
    }
    pthread_mutex_lock(&r->fifo_mtx);
    r->obuf_rd = r->obuf_wr = 0;
    r->discard_out = 0;
    r->error = 0;
    pthread_mutex_unlock(&r->fifo_mtx);

    for (int c = 0; c < 2; c++) {
        dsd_ctx_reset(&r->dsdctx[c]);
        if (r->fir_hist[c]) memset(r->fir_hist[c], 0, DSD_FIR_HIST * sizeof(float));
    }
    if (r->conv) dsd_conv_reset(r->conv);

    /* Scarletbook frame assembler: forget any half-assembled frame. */
    r->sb->frame.started = 0;
    r->sb->frame.size = 0;
    r->sb->frame.timecode.minutes = 0;
    r->sb->frame.timecode.seconds = 0;
    r->sb->frame.timecode.frames = 0;

    r->done = 0;
    r->skip_before_tc = 0;
    r->first_tc_seen = UINT32_MAX;
    return 0;
}

/* Reads, decrypts and parses the next block of sectors.
 * Returns 1 on success, 0 on a (retryable) read error, -1 on a fatal error. */
static int decode_next_block(sacd_pcm_reader_t *r)
{
    const uint32_t end_lsn = r->start_lsn + r->length_lsn;
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
    if (block_size == 0) return 1;

    uint32_t got = sacd_read_block_raw(r->sacd, r->cur_lsn, block_size, r->read_buf);
    if (got == 0) {
        /* Transient source failure (e.g. SMB): leave cur_lsn where it is so the
         * next read retries the same sectors instead of poisoning the reader. */
        return 0;
    }
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
    if (rc < 0) {
        /* A malformed sector: the assembler drops that frame and resyncs on
         * the next frame start - not a reason to stop the whole track. */
        BLOGW("bad audio sector(s) near LSN %u - skipped", r->cur_lsn);
    }
    pthread_mutex_lock(&r->fifo_mtx);
    int err = r->error;
    pthread_mutex_unlock(&r->fifo_mtx);
    return err ? -1 : 1;
}

/* ------------------------------------------------------------------ */
long sacd_pcm_read(sacd_pcm_reader_t *r, float *out, long frames)
{
    if (!r || !out || frames < 0) return -1;

    const uint32_t end_lsn = r->start_lsn + r->length_lsn;
    int ch = r->channels;
    long written = 0;

    for (;;) {
        pthread_mutex_lock(&r->fifo_mtx);
        if (r->error) {
            pthread_mutex_unlock(&r->fifo_mtx);
            break;
        }
        size_t avail = (r->obuf_wr - r->obuf_rd) / (size_t)ch;
        if (r->discard_out > 0 && avail > 0) {
            size_t drop = (r->discard_out < (uint64_t)avail) ? (size_t)r->discard_out : avail;
            r->obuf_rd += drop * (size_t)ch;
            r->discard_out -= drop;
            avail -= drop;
        }
        if (avail > 0 && written < frames) {
            size_t n = avail < (size_t)(frames - written) ? avail : (size_t)(frames - written);
            memcpy(out + (size_t)written * (size_t)ch,
                   r->obuf + r->obuf_rd, n * (size_t)ch * sizeof(float));
            r->obuf_rd += n * (size_t)ch;
            written += (long)n;
        }
        if (r->obuf_rd >= r->obuf_wr) r->obuf_rd = r->obuf_wr = 0;
        uint64_t inflight = r->dst_submitted - r->dst_delivered;
        pthread_mutex_unlock(&r->fifo_mtx);

        if (written >= frames || r->done) break;

        if (r->dst && inflight >= DST_MAX_INFLIGHT) {
            /* Enough DST frames queued: wait for output instead of reading on. */
            pthread_mutex_lock(&r->fifo_mtx);
            while (!r->error && r->dst_submitted - r->dst_delivered >= DST_MAX_INFLIGHT)
                pthread_cond_wait(&r->fifo_cv, &r->fifo_mtx);
            pthread_mutex_unlock(&r->fifo_mtx);
            continue;
        }

        if (r->cur_lsn >= end_lsn) {
            if (r->dst) {
                /* Every sector is read, but the last DST frames may still be
                 * decoding: wait for them before declaring end of track. */
                pthread_mutex_lock(&r->fifo_mtx);
                while (!r->error && r->dst_delivered < r->dst_submitted)
                    pthread_cond_wait(&r->fifo_cv, &r->fifo_mtx);
                pthread_mutex_unlock(&r->fifo_mtx);
            }
            r->done = 1;
            continue; /* drain what arrived, then stop */
        }

        int rc = decode_next_block(r);
        if (rc == 0) {
            /* Retryable I/O error: hand out what we have; report the error
             * only when nothing was produced, so no sample is ever lost. */
            r->emitted += (uint64_t)written;
            return written > 0 ? written : -1;
        }
        if (rc < 0) break;
    }

    r->emitted += (uint64_t)written;
    if (written == 0) {
        pthread_mutex_lock(&r->fifo_mtx);
        int err = r->error;
        pthread_mutex_unlock(&r->fifo_mtx);
        if (err) return -1;
    }
    return written;
}

/* Reads blocks until the first frame header after a seek has been seen.
 * Returns 1 when seen (or end of track), 0 on a retryable read error, -1 fatal. */
static int pump_until_first_frame(sacd_pcm_reader_t *r)
{
    const uint32_t end_lsn = r->start_lsn + r->length_lsn;
    while (r->first_tc_seen == UINT32_MAX && r->cur_lsn < end_lsn) {
        int rc = decode_next_block(r);
        if (rc <= 0) return rc;
    }
    return 1;
}

int sacd_pcm_seek_output_frame(sacd_pcm_reader_t *r, unsigned long long target)
{
    if (!r || !r->sb) return -1;
    if (target > r->total_frames) target = r->total_frames;

    pthread_mutex_lock(&r->fifo_mtx);
    int err = r->error;
    pthread_mutex_unlock(&r->fifo_mtx);

    /* Short forward hop: just drop the output in between (lazily, on the next
     * reads) - cheap, and the filters stay continuous. */
    if (!err && !r->done && target >= r->emitted &&
        target - r->emitted <= (uint64_t)r->out_hz * SEEK_DISCARD_MAX_SEC) {
        pthread_mutex_lock(&r->fifo_mtx);
        r->discard_out += target - r->emitted;
        pthread_mutex_unlock(&r->fifo_mtx);
        r->emitted = target;
        return 0;
    }

    /* Jump to (just before) the frame that holds the target: the sector is
     * estimated from the track's LSN range (exact for plain DSD's fixed frame
     * layout, close for variable-size DST), frames before the wanted
     * timecode are dropped unparsed/undecoded, and one frame of pre-roll lets
     * dsd2pcm and the decimation filter settle before the first kept sample. */
    const uint64_t spf = (uint64_t)r->spf;
    const uint64_t k = target / spf;
    const uint64_t intra = target - k * spf;
    const uint64_t pre = (k > 0) ? 1 : 0;
    const uint64_t kstart = k - pre;
    const uint32_t nframes = r->sys_end - r->sys_start;
    const uint32_t tc_want = r->sys_start + (uint32_t)kstart;

    uint32_t backoff = 32; /* sectors */
    int landed = 0;
    for (int attempt = 0; attempt < 8 && !landed; attempt++) {
        if (reset_decode_state(r) != 0) return -1;
        uint64_t est = r->start_lsn;
        if (nframes > 0 && r->length_lsn > 0)
            est += (uint64_t)r->length_lsn * kstart / nframes;
        est = (est > (uint64_t)r->start_lsn + backoff) ? est - backoff : r->start_lsn;
        r->cur_lsn = (uint32_t)est;
        r->skip_before_tc = tc_want;
        pthread_mutex_lock(&r->fifo_mtx);
        r->discard_out = pre * spf + intra;
        pthread_mutex_unlock(&r->fifo_mtx);
        r->emitted = target;
        if (r->cur_lsn == r->start_lsn) { landed = 1; break; }

        int rc = pump_until_first_frame(r);
        if (rc == 0) return -1;     /* retryable read error: caller may retry the seek */
        if (rc < 0) return -1;
        if (r->first_tc_seen == UINT32_MAX || r->first_tc_seen <= tc_want) {
            landed = 1;             /* at or before the wanted frame */
        } else {
            backoff *= 4;           /* overshot (DST is variable-rate): back off further */
        }
    }

    if (!landed) {
        /* Timecodes don't behave (damaged/odd image): fall back to decoding
         * from the start of the track and dropping everything before target. */
        BLOGW("seek: sector estimate did not converge - decoding from track start");
        if (reset_decode_state(r) != 0) return -1;
        r->cur_lsn = r->start_lsn;
        pthread_mutex_lock(&r->fifo_mtx);
        r->discard_out = target;
        pthread_mutex_unlock(&r->fifo_mtx);
        r->emitted = target;
    }
    return 0;
}

/* ---------- callbacks ------------------------------------------------ */

/* libdstdec write thread: one decoded DST frame, in stream order. */
static void frame_decoded_cb(uint8_t *frame_data, size_t frame_size, void *userdata)
{
    sacd_pcm_reader_t *r = (sacd_pcm_reader_t *)userdata;
    if (!r) return;
    pthread_mutex_lock(&r->fifo_mtx);
    r->dst_delivered++;
    if (!r->dst_discard && !r->error) consume_dsd_frame_locked(r, frame_data, frame_size);
    pthread_cond_broadcast(&r->fifo_cv);
    pthread_mutex_unlock(&r->fifo_mtx);
}

static void frame_error_cb(int frame_count, int frame_error_code,
                           const char *msg, void *userdata)
{
    (void)userdata;
    BLOGW("DST decode error (frame %d, code %d): %s", frame_count, frame_error_code,
          msg ? msg : "?");
}

/* Reader thread: one complete (DSD or DST-coded) frame from the assembler. */
static void frame_read_cb(scarletbook_handle_t *handle, uint8_t *frame_data,
                          size_t frame_size, void *userdata)
{
    sacd_pcm_reader_t *r = (sacd_pcm_reader_t *)userdata;
    if (!r) return;

    uint32_t tc = TIME_FRAMECOUNT(&handle->frame.timecode);
    if (r->first_tc_seen == UINT32_MAX) r->first_tc_seen = tc;
    if (r->sys_start > 0) {
        if (tc < r->sys_start || tc >= r->sys_end) return; /* trim */
    }
    if (r->skip_before_tc && tc < r->skip_before_tc) return; /* seek: not there yet */

    if (r->dst) {
        pthread_mutex_lock(&r->fifo_mtx);
        int err = r->error;
        if (!err) r->dst_submitted++;
        pthread_mutex_unlock(&r->fifo_mtx);
        if (!err) dst_decoder_decode(r->dst_dec, frame_data, frame_size);
    } else {
        pthread_mutex_lock(&r->fifo_mtx);
        if (!r->error) consume_dsd_frame_locked(r, frame_data, frame_size);
        pthread_mutex_unlock(&r->fifo_mtx);
    }
}
