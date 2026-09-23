/*
 * Streaming DSD -> PCM converter for DSF/DFF (see dsd_conv.h).
 */
#include "dsd_conv.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "dsd.h"

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

struct dsd_conv {
    int channels;
    int dsd_rate;
    int out_hz;
    int decim;          /* (dsd_rate / 8) / out_hz */
    int taps;           /* odd FIR length */
    int taps_vec;       /* taps padded to a multiple of 4 (zero coefficients) */
    float *coeff;       /* taps_vec coefficients */

    DSDContext dsd[DSD_CONV_MAX_CH];

    /* Per channel: [history (taps-1) | new native samples | 4 zero pad]. */
    float *work[DSD_CONV_MAX_CH];
    size_t work_cap;    /* floats per channel buffer */

    /* Window-end index (into work, relative to the first new sample) of the
     * next output, carried across calls: 0..decim-1. */
    size_t phase;
};

int dsd_conv_default_out_hz(int dsd_rate_hz)
{
    if (dsd_rate_hz <= 0) return 0;
    if (dsd_rate_hz % 44100 == 0 && (dsd_rate_hz / 8) % 176400 == 0) return 176400;
    if (dsd_rate_hz % 48000 == 0 && (dsd_rate_hz / 8) % 192000 == 0) return 192000;
    return 0;
}

static void design_lowpass(float *coeff, int taps, int taps_vec, double fc, double fs)
{
    /* Blackman-Harris windowed sinc, unity DC gain. */
    const double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
    const double m = (double)(taps - 1) / 2.0;
    const double wc = 2.0 * fc / fs; /* normalized cut-off (cycles/sample * 2) */
    double sum = 0.0;
    double *h = (double *)malloc(sizeof(double) * (size_t)taps);
    for (int i = 0; i < taps; i++) {
        double x = i - m;
        double sinc = (x == 0.0) ? 1.0 : sin(M_PI * wc * x) / (M_PI * wc * x);
        double w = a0 - a1 * cos(2.0 * M_PI * i / (taps - 1))
                      + a2 * cos(4.0 * M_PI * i / (taps - 1))
                      - a3 * cos(6.0 * M_PI * i / (taps - 1));
        h[i] = sinc * w;
        sum += h[i];
    }
    for (int i = 0; i < taps; i++) coeff[i] = (float)(h[i] / sum);
    for (int i = taps; i < taps_vec; i++) coeff[i] = 0.0f;
    free(h);
}

static inline float fir_dot(const float *coeff, const float *win, int n_vec)
{
#if defined(__aarch64__)
    float32x4_t acc0 = vdupq_n_f32(0.0f), acc1 = vdupq_n_f32(0.0f);
    int i = 0;
    for (; i + 8 <= n_vec; i += 8) {
        acc0 = vfmaq_f32(acc0, vld1q_f32(coeff + i), vld1q_f32(win + i));
        acc1 = vfmaq_f32(acc1, vld1q_f32(coeff + i + 4), vld1q_f32(win + i + 4));
    }
    for (; i < n_vec; i += 4)
        acc0 = vfmaq_f32(acc0, vld1q_f32(coeff + i), vld1q_f32(win + i));
    return vaddvq_f32(vaddq_f32(acc0, acc1));
#else
    float r = 0.0f;
    for (int i = 0; i < n_vec; i++) r += coeff[i] * win[i];
    return r;
#endif
}

dsd_conv_t *dsd_conv_create(int channels, int dsd_rate_hz, int out_hz)
{
    if (channels < 1 || channels > DSD_CONV_MAX_CH) return NULL;
    if (dsd_rate_hz <= 0 || dsd_rate_hz % 8 != 0 || out_hz <= 0) return NULL;
    int native = dsd_rate_hz / 8;
    if (native % out_hz != 0) return NULL;
    int decim = native / out_hz;
    if (decim < 1 || decim > 32) return NULL;

    dsd_conv_t *c = (dsd_conv_t *)calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->channels = channels;
    c->dsd_rate = dsd_rate_hz;
    c->out_hz = out_hz;
    c->decim = decim;
    /* Same transition band (in Hz) for every ratio: 255 taps at 2:1, scaled
     * with the ratio. A 1:1 "decimation" still gets the low-pass (DSD noise
     * above ~100 kHz is otherwise passed straight through). */
    c->taps = (decim >= 2) ? 127 * decim + 1 : 255;
    c->taps_vec = (c->taps + 3) & ~3;
    c->coeff = (float *)calloc((size_t)c->taps_vec, sizeof(float));
    if (!c->coeff) { free(c); return NULL; }
    design_lowpass(c->coeff, c->taps, c->taps_vec, 0.4422 * out_hz, (double)native);

    ff_init_dsd_data();
    dsd_conv_reset(c);
    return c;
}

void dsd_conv_reset(dsd_conv_t *c)
{
    if (!c) return;
    for (int ch = 0; ch < c->channels; ch++) {
        memset(&c->dsd[ch], 0, sizeof(c->dsd[ch]));
        /* DSD "silence" is the idle pattern, not all zero bits; start the
         * dsd2pcm FIFO on 0x69 so the first samples don't thump. */
        memset(c->dsd[ch].buf, 0x69, sizeof(c->dsd[ch].buf));
        if (c->work[ch]) memset(c->work[ch], 0, sizeof(float) * c->work_cap);
    }
    c->phase = 0;
}

static int ensure_work(dsd_conv_t *c, size_t bytes_per_ch)
{
    size_t need = (size_t)(c->taps - 1) + bytes_per_ch + 4;
    if (need <= c->work_cap) return 0;
    size_t hist = (size_t)(c->taps - 1);
    for (int ch = 0; ch < c->channels; ch++) {
        float *nw = (float *)calloc(need, sizeof(float));
        if (!nw) return -1;
        if (c->work[ch]) memcpy(nw, c->work[ch], sizeof(float) * hist);
        free(c->work[ch]);
        c->work[ch] = nw;
    }
    c->work_cap = need;
    return 0;
}

size_t dsd_conv_max_out_frames(const dsd_conv_t *c, size_t bytes_per_ch)
{
    return c ? bytes_per_ch / (size_t)c->decim + 1 : 0;
}

long dsd_conv_process(dsd_conv_t *c, const uint8_t *src, size_t bytes_per_ch, float *out)
{
    if (!c || !src || !out) return -1;
    if (bytes_per_ch == 0) return 0;
    if (ensure_work(c, bytes_per_ch) != 0) return -1;

    const size_t hist = (size_t)(c->taps - 1);
    const size_t decim = (size_t)c->decim;
    const int nch = c->channels;
    long nout = 0;

    for (int ch = 0; ch < nch; ch++) {
        float *w = c->work[ch];
        /* Stage 1: one native-rate sample per DSD byte, after the history. */
        ff_dsd2pcm_translate(&c->dsd[ch], bytes_per_ch, 0 /* MSB-first */,
                             src + ch, nch, w + hist, 1);
        memset(w + hist + bytes_per_ch, 0, 4 * sizeof(float)); /* pad: never NaN */

        /* Stage 2: outputs whose window ends at new-sample index e
         * (e = phase, phase + decim, ...) — window covers w[e .. e+taps-1]. */
        long k = 0;
        for (size_t e = c->phase; e < bytes_per_ch; e += decim) {
            out[(size_t)k * (size_t)nch + (size_t)ch] = fir_dot(c->coeff, w + e, c->taps_vec);
            k++;
        }
        nout = k; /* identical for every channel */

        /* Keep the last taps-1 samples as history for the next call. */
        memmove(w, w + bytes_per_ch, sizeof(float) * hist);
    }

    /* Carry the output grid into the next call. */
    size_t next_e = c->phase + (size_t)nout * decim;
    c->phase = next_e - bytes_per_ch;
    return nout;
}

int dsd_conv_out_hz(const dsd_conv_t *c) { return c ? c->out_hz : 0; }
int dsd_conv_taps(const dsd_conv_t *c) { return c ? c->taps : 0; }

void dsd_conv_destroy(dsd_conv_t *c)
{
    if (!c) return;
    for (int ch = 0; ch < DSD_CONV_MAX_CH; ch++) free(c->work[ch]);
    free(c->coeff);
    free(c);
}
