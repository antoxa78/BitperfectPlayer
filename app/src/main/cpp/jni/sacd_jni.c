/*
 * JNI wrapper around the SACD->PCM bridge (sacd_pcm.h).
 *
 * Exposes:
 *   nativeLibraryVersion()         -> bridge version string
 *   nativeAlbumInfo(String iso, int area) -> JSON metadata string
 *   nativeDecodeTrackToWav(String iso, int area, int track,
 *                          int outHz, String outWavPath) -> status string
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <math.h>

#include <android/log.h>

#include "sacd_pcm.h"

#define LOG_TAG "SacdBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char *jstring_to_utf8(JNIEnv *env, jstring jsrc)
{
    if (!jsrc) return NULL;
    const char *utf = (*env)->GetStringUTFChars(env, jsrc, NULL);
    if (!utf) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, jsrc, utf);
    return copy;
}

static jstring new_status(JNIEnv *env, const char *fmt, ...)
{
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    return (*env)->NewStringUTF(env, buf);
}

/* Appends a JSON string literal (escapes " and \, drops control chars). */
static void json_append_string(char *dst, size_t cap, const char *s)
{
    size_t n = 0;
    for (const char *p = s; *p && n + 1 < cap; p++) {
        unsigned char c = (unsigned char)*p;
        if (c < 0x20) continue;
        if (c == '"' || c == '\\') {
            if (n + 2 >= cap) break;
            dst[n++] = '\\';
        }
        dst[n++] = (char)c;
    }
    dst[n] = '\0';
}

static jstring album_info_to_json(JNIEnv *env, const sacd_album_info_t *info)
{
    char *json = malloc(64 * 1024);
    size_t cap = 64 * 1024;
    if (!json) return new_status(env, "ERR OOM");

    int n = snprintf(json, cap,
        "{\"area\":%d,\"track_count\":%d,\"channel_count\":%d,"
        "\"dsd_rate_hz\":%d,\"dst\":%d,\"album_title\":\"",
        info->area, info->track_count, info->channel_count,
        info->dsd_rate_hz, info->dst);
    if (info->album_title) {
        size_t used = (size_t)n;
        json_append_string(json + used, cap - used, info->album_title);
        n = (int)strlen(json);
    }
    n += snprintf(json + n, cap - (size_t)n,
        "\",\"album_artist\":\"");
    if (info->album_artist) {
        json_append_string(json + (size_t)n, cap - (size_t)n, info->album_artist);
        n = (int)strlen(json);
    }
    n += snprintf(json + n, cap - (size_t)n, "\",\"tracks\":[");

    for (int i = 0; i < info->track_count; i++) {
        n += snprintf(json + n, cap - (size_t)n, "%s{\"title\":\"",
                      i ? "," : "");
        if (info->track_title && info->track_title[i]) {
            json_append_string(json + (size_t)n, cap - (size_t)n, info->track_title[i]);
            n = (int)strlen(json);
        }
        n += snprintf(json + n, cap - (size_t)n, "\",\"artist\":\"");
        if (info->track_artist && info->track_artist[i]) {
            json_append_string(json + (size_t)n, cap - (size_t)n, info->track_artist[i]);
            n = (int)strlen(json);
        }
        unsigned long long dur = info->track_duration_ms ? info->track_duration_ms[i] : 0ull;
        n += snprintf(json + n, cap - (size_t)n, "\",\"duration_ms\":%llu}",
                      dur);
    }
    n += snprintf(json + n, cap - (size_t)n, "]}");

    jstring out = (*env)->NewStringUTF(env, json);
    free(json);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeLibraryVersion(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, "sacd-pcm-android-v0.1");
}

JNIEXPORT jstring JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeAlbumInfo(JNIEnv *env, jobject thiz,
                                                              jstring jIsoPath, jint jArea)
{
    (void)thiz;
    char *iso = jstring_to_utf8(env, jIsoPath);
    if (!iso) return new_status(env, "ERR no iso path");
    if (access(iso, 0) != 0) {
        free(iso);
        return new_status(env, "ERR iso not readable: %s", iso);
    }

    sacd_album_info_t info;
    int rc = sacd_album_info_open(iso, jArea, &info);
    free(iso);
    if (rc != 0) return new_status(env, "ERR sacd_album_info_open failed rc=%d", rc);

    jstring out = album_info_to_json(env, &info);
    sacd_album_info_free(&info);
    return out;
}

static int write_wav_header(FILE *f, int rate, int ch, unsigned int data_bytes)
{
    unsigned int ry = rate * ch * 3; /* 24-bit PCM */
    unsigned char hdr[44] = {0};
    memcpy(hdr, "RIFF", 4);
    hdr[4] = (unsigned char)(36u + data_bytes);
    hdr[5] = (unsigned char)((36u + data_bytes) >> 8);
    hdr[6] = (unsigned char)((36u + data_bytes) >> 16);
    hdr[7] = (unsigned char)((36u + data_bytes) >> 24);
    memcpy(hdr + 8, "WAVEfmt ", 8);
    hdr[16] = 16; hdr[17] = 0;   /* fmt chunk size */
    hdr[20] = 1;  hdr[21] = 0;   /* PCM */
    hdr[22] = (unsigned char)ch; hdr[23] = 0;
    hdr[24] = (unsigned char)rate; hdr[25] = (unsigned char)(rate >> 8);
    hdr[26] = (unsigned char)(rate >> 16); hdr[27] = (unsigned char)(rate >> 24);
    hdr[28] = (unsigned char)ry; hdr[29] = (unsigned char)(ry >> 8);
    hdr[30] = (unsigned char)(ry >> 16); hdr[31] = (unsigned char)(ry >> 24);
    hdr[32] = (unsigned char)(ch * 3); hdr[33] = 0;
    hdr[34] = 24; hdr[35] = 0;
    memcpy(hdr + 36, "data", 4);
    hdr[40] = (unsigned char)data_bytes;
    hdr[41] = (unsigned char)(data_bytes >> 8);
    hdr[42] = (unsigned char)(data_bytes >> 16);
    hdr[43] = (unsigned char)(data_bytes >> 24);
    return fwrite(hdr, 1, 44, f) == 44 ? 0 : -1;
}

static int write_int24(FILE *f, float sample)
{
    if (sample > 1.0f) sample = 1.0f;
    if (sample < -1.0f) sample = -1.0f;
    int v = (int)lrintf(sample * 8388607.0f);
    if (v < -8388608) v = -8388608;
    if (v > 8388607) v = 8388607;
    unsigned char b[3];
    b[0] = (unsigned char)(v & 0xFF);
    b[1] = (unsigned char)((v >> 8) & 0xFF);
    b[2] = (unsigned char)((v >> 16) & 0xFF);
    return fwrite(b, 1, 3, f) == 3 ? 0 : -1;
}

#define DECODE_CHUNK_FRAMES 16384

JNIEXPORT jstring JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeDecodeTrackToWav(
    JNIEnv *env, jobject thiz, jstring jIsoPath, jint jArea, jint jTrack,
    jint jOutHz, jstring jWavPath)
{
    (void)thiz;
    char *iso = jstring_to_utf8(env, jIsoPath);
    char *wav = jstring_to_utf8(env, jWavPath);
    if (!iso || !wav) {
        free(iso); free(wav);
        return new_status(env, "ERR missing args");
    }

    sacd_pcm_reader_t *r = sacd_pcm_open(iso, jArea, jTrack, jOutHz);
    if (!r) {
        char *msg = new_status(env, "ERR sacd_pcm_open failed for %s", iso);
        free(iso); free(wav);
        return msg;
    }

    int rate = sacd_pcm_out_rate(r);
    int ch = sacd_pcm_channels(r);
    float *buf = malloc((size_t)DECODE_CHUNK_FRAMES * ch * sizeof(float));
    if (!buf) {
        sacd_pcm_close(r);
        free(iso); free(wav);
        return new_status(env, "ERR OOM");
    }

    FILE *f = fopen(wav, "wb");
    if (!f) {
        free(buf);
        sacd_pcm_close(r);
        free(iso); free(wav);
        return new_status(env, "ERR cannot open %s", wav);
    }

    /* Write a placeholder header; patch sizes after the first pass. */
    if (write_wav_header(f, rate, ch, 0) != 0) {
        fclose(f);
        free(buf);
        sacd_pcm_close(r);
        free(iso); free(wav);
        return new_status(env, "ERR write header");
    }

    /* Decode the whole track, then patch the header sizes. */
    long total_frames = 0;
    long chunk;
    while ((chunk = sacd_pcm_read(r, buf, DECODE_CHUNK_FRAMES)) > 0) {
        for (long i = 0; i < chunk * ch; i++) {
            if (write_int24(f, buf[i]) != 0) {
                fclose(f);
                free(buf);
                sacd_pcm_close(r);
                free(iso); free(wav);
                return new_status(env, "ERR write sample");
            }
        }
        total_frames += chunk;
        if ((total_frames % (rate / 10)) == 0) {
            LOGI("decode progress: %lld frames (%lld s / ~%lld s)",
                 (long long)total_frames, (long long)(total_frames / rate),
                 (long long)(sacd_pcm_duration_ms(r) / 1000));
        }
    }

    int rc = (chunk < 0) ? -1 : 0;
    fflush(f);
    unsigned int data_bytes = (unsigned int)((unsigned long long)total_frames * ch * 3);
    if (fseek(f, 0, SEEK_SET) == 0)
        write_wav_header(f, rate, ch, data_bytes);
    fclose(f);

    uint64_t dur_ms = sacd_pcm_duration_ms(r);
    sacd_pcm_close(r);
    free(buf);
    free(iso); free(wav);

    if (rc != 0) {
        return new_status(env, "ERR decode aborted");
    }
    return new_status(env, "OK frames=%lld rate=%d ch=%d duration_ms=%llu data_bytes=%u",
                      (long long)total_frames, rate, ch,
                      (unsigned long long)dur_ms, data_bytes);
}

/* ================================================================== */
/* Streaming reader API (SMB / remote sources via SacdRandomAccess)   */
/* ================================================================== */

/*
 * A kotlin object implementing
 *   interface SacdRandomAccess {
 *       fun read(offset: Long, length: Int): ByteArray
 *       fun length(): Long
 *   }
 * is kept as a global ref; reads punch through to it via JNI.
 */
typedef struct jni_sacd_ctx_s {
    JavaVM               *vm;
    jobject               g_reader;
    sacd_pcm_reader_t    *r;
    float                *scratch;   /* float32 scratch for int24 conversion */
    size_t                scratch_cap;
} jni_sacd_ctx_t;

static jmethodID g_mid_read;   /* (JJ)[B */
static jmethodID g_mid_length; /* ()J   */

static JNIEnv *jni_get_env(JavaVM *vm)
{
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
        return env;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) == JNI_OK)
        return env;
    return NULL;
}

static int jni_cb_read(void *opaque, int64_t offset, void *buf, int len)
{
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)opaque;
    if (!ctx || !ctx->g_reader) return -1;
    JNIEnv *env = jni_get_env(ctx->vm);
    if (!env || !g_mid_read) return -1;

    jbyteArray arr = (*env)->CallObjectMethod(env, ctx->g_reader, g_mid_read,
                                              (jlong)offset, (jint)len);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env); /* transport threw; let native side retry */
        return -1;
    }
    if (!arr) return -1;
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n > (jsize)len) n = (jsize)len;
    if (n > 0)
        (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
    (*env)->DeleteLocalRef(env, arr);
    return (int)n;
}

static int64_t jni_cb_size(void *opaque)
{
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)opaque;
    if (!ctx || !ctx->g_reader) return -1;
    JNIEnv *env = jni_get_env(ctx->vm);
    if (!env || !g_mid_length) return -1;
    jlong len = (*env)->CallLongMethod(env, ctx->g_reader, g_mid_length);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    return (int64_t)len;
}

static jni_sacd_ctx_t *jni_ctx_make(JNIEnv *env, jobject jReader)
{
    jni_sacd_ctx_t *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    if ((*env)->GetJavaVM(env, &ctx->vm) != JNI_OK) { free(ctx); return NULL; }
    ctx->g_reader = (*env)->NewGlobalRef(env, jReader);
    if (!ctx->g_reader) { free(ctx); return NULL; }

    if (!g_mid_read || !g_mid_length) {
        jclass cls = (*env)->GetObjectClass(env, jReader);
        if (cls) {
            g_mid_read   = (*env)->GetMethodID(env, cls, "read", "(JI)[B");
            g_mid_length = (*env)->GetMethodID(env, cls, "length", "()J");
            (*env)->DeleteLocalRef(env, cls);
        }
        if (!g_mid_read || !g_mid_length) {
            (*env)->DeleteGlobalRef(env, ctx->g_reader);
            free(ctx);
            return NULL;
        }
    }
    return ctx;
}

static void jni_ctx_free(JNIEnv *env, jni_sacd_ctx_t *ctx)
{
    if (!ctx) return;
    if (ctx->g_reader) (*env)->DeleteGlobalRef(env, ctx->g_reader);
    free(ctx->scratch);
    free(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeOpenSacd(JNIEnv *env, jobject thiz,
                                                            jobject jReader, jint jArea,
                                                            jint jTrack, jint jOutHz)
{
    (void)thiz;
    if (!jReader) return 0;

    jni_sacd_ctx_t *ctx = jni_ctx_make(env, jReader);
    if (!ctx) return 0;

    ctx->r = sacd_pcm_open_cb(jni_cb_read, jni_cb_size, ctx, jArea, jTrack, jOutHz);
    if (!ctx->r) {
        LOGE("nativeOpenSacd failed: area=%d track=%d outHz=%d", jArea, jTrack, jOutHz);
        jni_ctx_free(env, ctx);
        return 0;
    }
    LOGI("nativeOpenSacd ok: rate=%d ch=%d total_frames=%llu dur_ms=%llu",
         sacd_pcm_out_rate(ctx->r), sacd_pcm_channels(ctx->r),
         (unsigned long long)sacd_pcm_output_frames(ctx->r),
         (unsigned long long)sacd_pcm_duration_ms(ctx->r));
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdReadInt24(JNIEnv *env, jobject thiz,
    jlong handle, jint jMaxFrames)
{
    (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->r) return (*env)->NewByteArray(env, 0);

    int ch = sacd_pcm_channels(ctx->r);
    if (ch <= 0) return (*env)->NewByteArray(env, 0);
    if (jMaxFrames <= 0) jMaxFrames = 4096;

    size_t needf = (size_t)jMaxFrames * (size_t)ch;
    if (ctx->scratch_cap < needf) {
        float *nb = realloc(ctx->scratch, needf * sizeof(float));
        if (!nb) return (*env)->NewByteArray(env, 0);
        ctx->scratch = nb;
        ctx->scratch_cap = needf;
    }

    long frames = sacd_pcm_read(ctx->r, ctx->scratch, jMaxFrames);
    if (frames <= 0) return (*env)->NewByteArray(env, 0);

    size_t nbytes = (size_t)frames * (size_t)ch * 3u;
    uint8_t *tmp = malloc(nbytes);
    if (!tmp) return (*env)->NewByteArray(env, 0);

    size_t o = 0;
    for (long i = 0; i < (long)frames * ch; i++) {
        float s = ctx->scratch[i];
        if (s > 1.0f) s = 1.0f;
        if (s < -1.0f) s = -1.0f;
        int v = (int)lrintf(s * 8388607.0f);
        if (v < -8388608) v = -8388608;
        if (v > 8388607) v = 8388607;
        tmp[o++] = (uint8_t)(v & 0xFF);
        tmp[o++] = (uint8_t)((v >> 8) & 0xFF);
        tmp[o++] = (uint8_t)((v >> 16) & 0xFF);
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize)nbytes);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)nbytes, (const jbyte *)tmp);
    free(tmp);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdReadFloat(JNIEnv *env, jobject thiz,
    jlong handle, jint jMaxFrames)
{
    (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->r) return (*env)->NewByteArray(env, 0);

    int ch = sacd_pcm_channels(ctx->r);
    if (ch <= 0) return (*env)->NewByteArray(env, 0);
    if (jMaxFrames <= 0) jMaxFrames = 4096;

    size_t needf = (size_t)jMaxFrames * (size_t)ch;
    if (ctx->scratch_cap < needf) {
        float *nb = realloc(ctx->scratch, needf * sizeof(float));
        if (!nb) return (*env)->NewByteArray(env, 0);
        ctx->scratch = nb;
        ctx->scratch_cap = needf;
    }

    long frames = sacd_pcm_read(ctx->r, ctx->scratch, jMaxFrames);
    /* frames < 0: decode error (e.g. transient SMB failure) -> return NULL so the
     * extractor signals a load error and media3 retries instead of truncating the
     * track. frames == 0: clean EOF -> empty array. */
    if (frames < 0) return NULL;
    if (frames == 0) return (*env)->NewByteArray(env, 0);

    size_t nbytes = (size_t)frames * (size_t)ch * sizeof(float);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)nbytes);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)nbytes, (const jbyte *)ctx->scratch);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdSeek(JNIEnv *env, jobject thiz,
                                                            jlong handle, jlong jFrame)
{
    (void)env; (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    if (!ctx || !ctx->r) return -1;
    return sacd_pcm_seek_output_frame(ctx->r, (unsigned long long)jFrame);
}

JNIEXPORT void JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdClose(JNIEnv *env, jobject thiz,
                                                             jlong handle)
{
    (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    if (!ctx) return;
    if (ctx->r) sacd_pcm_close(ctx->r);
    jni_ctx_free(env, ctx);
}

JNIEXPORT jint JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdOutRate(JNIEnv *env, jobject thiz,
                                                               jlong handle)
{
    (void)env; (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    return (ctx && ctx->r) ? (jint)sacd_pcm_out_rate(ctx->r) : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdChannels(JNIEnv *env, jobject thiz,
                                                                jlong handle)
{
    (void)env; (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    return (ctx && ctx->r) ? (jint)sacd_pcm_channels(ctx->r) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdTotalFrames(JNIEnv *env, jobject thiz,
                                                                   jlong handle)
{
    (void)env; (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    return (ctx && ctx->r) ? (jlong)sacd_pcm_output_frames(ctx->r) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeSacdDurationMs(JNIEnv *env, jobject thiz,
                                                                  jlong handle)
{
    (void)env; (void)thiz;
    jni_sacd_ctx_t *ctx = (jni_sacd_ctx_t *)(intptr_t)handle;
    return (ctx && ctx->r) ? (jlong)sacd_pcm_duration_ms(ctx->r) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_bitperfectplayer_SacdBridge_nativeAlbumInfoReader(
    JNIEnv *env, jobject thiz, jobject jReader, jint jArea)
{
    (void)thiz;
    if (!jReader) return new_status(env, "ERR no reader");

    jni_sacd_ctx_t *ctx = jni_ctx_make(env, jReader);
    if (!ctx) return new_status(env, "ERR context");

    sacd_album_info_t info;
    int rc = sacd_album_info_open_cb(jni_cb_read, jni_cb_size, ctx, jArea, &info);
    jni_ctx_free(env, ctx);
    if (rc != 0) return new_status(env, "ERR sacd_album_info_open_cb failed rc=%d", rc);

    jstring out = album_info_to_json(env, &info);
    sacd_album_info_free(&info);
    return out;
}