/**
 * @file native-audio-engine.cpp
 * @brief Native FLAC decode → USB audio engine.
 *
 * Bypasses the entire ExoPlayer audio pipeline for FLAC files:
 * FLACParser decodes → bit-depth conversion → submitPcmToUrbs.
 * Single native thread, zero JNI in the hot path.
 *
 * The engine is controlled from Kotlin via JNI (start/pause/seek/stop)
 * and reports position via an atomic counter.
 */

#include "native-audio-engine.h"
#include "usb-audio-output.h"

#include <flac_parser.h>
#include <data_source.h>

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>

#define TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── MmapDataSource: memory-mapped file for zero-syscall reads ───────

/** DataSource with dedicated I/O thread. Decode thread NEVER does disk I/O.
 *  8MB buffer with compaction. I/O thread is the ONLY thread touching the fd.
 *  When buffer misses, decode waits for I/O thread to fill (no direct pread64). */
class AsyncBufferedDataSource : public DataSource {
    int fd_;
    bool ownsFd_;
    off64_t fileLength_;

    uint8_t *buf_;
    static const size_t BUF_CAP = 8 * 1024 * 1024;
    static const size_t READ_CHUNK = 128 * 1024;

    // Protected by mutex (shared between I/O thread and decode thread)
    pthread_mutex_t mu_;
    pthread_cond_t cond_;   // signal decode thread when data available
    pthread_cond_t ioCond_; // wake the I/O thread: space freed, seek, shutdown
    off64_t bufStart_;
    size_t bufFilled_;
    off64_t ioPos_;         // next read position for I/O thread
    bool seekPending_;
    off64_t seekTarget_;
    bool alive_;

    bool eof_;

    /** Caller holds mu_. Timed wait on ioCond_ (spurious wake-ups are fine). */
    static void ioWaitLocked(AsyncBufferedDataSource *ds, int ms) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += (long)ms * 1000000L;
        ts.tv_sec += ts.tv_nsec / 1000000000L;
        ts.tv_nsec %= 1000000000L;
        pthread_cond_timedwait(&ds->ioCond_, &ds->mu_, &ts);
    }

    static void *ioLoop(void *arg) {
        auto *ds = static_cast<AsyncBufferedDataSource *>(arg);
        uint8_t *tempBuf = (uint8_t *)malloc(READ_CHUNK);

        while (true) {
            pthread_mutex_lock(&ds->mu_);
            if (!ds->alive_) { pthread_mutex_unlock(&ds->mu_); break; }

            if (ds->seekPending_) {
                ds->bufStart_ = ds->seekTarget_;
                ds->bufFilled_ = 0;
                ds->ioPos_ = ds->seekTarget_;
                ds->seekPending_ = false;
                ds->eof_ = false;
            }

            if (ds->bufFilled_ >= BUF_CAP || ds->eof_) {
                // Buffer full (or file fully read): sleep until the reader
                // frees space, a seek arrives or we shut down, instead of
                // waking every 5 ms for the whole track.
                ioWaitLocked(ds, 200);
                pthread_mutex_unlock(&ds->mu_);
                continue;
            }

            off64_t readPos = ds->ioPos_;
            size_t space = BUF_CAP - ds->bufFilled_;
            pthread_mutex_unlock(&ds->mu_);

            // Read into TEMP buffer (not main buffer — avoids race with compaction)
            size_t toRead = space < READ_CHUNK ? space : READ_CHUNK;
            ssize_t n = pread64(ds->fd_, tempBuf, toRead, readPos);

            if (n > 0) {
                pthread_mutex_lock(&ds->mu_);
                if (ds->ioPos_ == readPos && !ds->seekPending_) {
                    // Copy to CURRENT end of buffer (safe — mutex held)
                    size_t currentFilled = ds->bufFilled_;
                    if (currentFilled + n <= BUF_CAP) {
                        memcpy(ds->buf_ + currentFilled, tempBuf, n);
                        ds->bufFilled_ = currentFilled + n;
                        ds->ioPos_ += n;
                    }
                    pthread_cond_signal(&ds->cond_);
                }
                pthread_mutex_unlock(&ds->mu_);
            } else {
                pthread_mutex_lock(&ds->mu_);
                if (n == 0 && ds->ioPos_ == readPos && !ds->seekPending_) {
                    ds->eof_ = true;   // idle until a seek rewinds us
                } else {
                    ioWaitLocked(ds, 5); // read error: brief back-off, then retry
                }
                pthread_mutex_unlock(&ds->mu_);
            }
        }
        free(tempBuf);
        return nullptr;
    }

    pthread_t ioThread_;
public:
    AsyncBufferedDataSource(int fd, bool ownsFd) : fd_(fd), ownsFd_(ownsFd),
            fileLength_(0), buf_(nullptr), bufStart_(0), bufFilled_(0),
            ioPos_(0), seekPending_(false), seekTarget_(0), alive_(true), eof_(false) {
        struct stat st;
        if (fstat(fd, &st) == 0) fileLength_ = st.st_size;
        buf_ = (uint8_t *)malloc(BUF_CAP);
        pthread_mutex_init(&mu_, nullptr);
        pthread_cond_init(&cond_, nullptr);
        pthread_cond_init(&ioCond_, nullptr);
        posix_fadvise(fd, 0, fileLength_, POSIX_FADV_SEQUENTIAL);
        // Only readahead first 2MB — enough for FLAC metadata + initial frames.
        // Reading the entire file monopolizes the FUSE daemon on SD cards,
        // blocking our pread64 calls (measured: >5s timeout for 128KB).
        off64_t raSize = fileLength_ < 2*1024*1024 ? fileLength_ : 2*1024*1024;
        readahead(fd, 0, raSize);
        pthread_create(&ioThread_, nullptr, ioLoop, this);
        LOGI("AsyncBufferedDataSource: fd=%d size=%lld (8MB buf, readahead issued)",
             fd, (long long)fileLength_);
    }

    ~AsyncBufferedDataSource() override {
        pthread_mutex_lock(&mu_);
        alive_ = false;
        pthread_cond_broadcast(&ioCond_);
        pthread_mutex_unlock(&mu_);
        pthread_join(ioThread_, nullptr);
        pthread_mutex_destroy(&mu_);
        pthread_cond_destroy(&cond_);
        pthread_cond_destroy(&ioCond_);
        free(buf_);
        if (ownsFd_ && fd_ >= 0) close(fd_);
    }

    ssize_t readAt(off64_t offset, void *const data, size_t size) override {
        if (offset >= fileLength_) return 0;

        pthread_mutex_lock(&mu_);

        // Fast path: data is already in buffer
        if (offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_) {
            memcpy(data, buf_ + (offset - bufStart_), size);

            // Compact when consumed past half. ">=": a reader that has caught
            // up exactly with the I/O thread must compact too, or a full buffer
            // is never freed and every later read bypasses it (direct pread on
            // the audio thread).
            size_t consumed = (size_t)(offset + size - bufStart_);
            if (consumed > BUF_CAP / 2 && bufFilled_ >= consumed) {
                size_t remaining = bufFilled_ - consumed;
                memmove(buf_, buf_ + consumed, remaining);
                bufStart_ = offset + size;
                bufFilled_ = remaining;
                pthread_cond_signal(&ioCond_);  // space for the I/O thread again
            }
            pthread_mutex_unlock(&mu_);
            return (ssize_t)size;
        }

        // Partial hit: return what we have
        if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
            size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
            memcpy(data, buf_ + (offset - bufStart_), avail);
            // Everything buffered is consumed: release it once past half.
            if ((size_t)(offset - bufStart_) + avail > BUF_CAP / 2) {
                bufStart_ = offset + (off64_t)avail;
                bufFilled_ = 0;
                pthread_cond_signal(&ioCond_);
            }
            pthread_mutex_unlock(&mu_);
            return (ssize_t)avail;
        }

        // Miss: data not in buffer. For small reads (e.g., FLAC seek binary search),
        // do a direct pread64 — much faster than waiting for the I/O thread to
        // seek+fill, especially on SD card through FUSE.
        if (size <= 64 * 1024) {
            pthread_mutex_unlock(&mu_);
            ssize_t n = pread64(fd_, data, size, offset);
            return n > 0 ? n : 0;
        }

        // Large miss: redirect I/O thread and wait
        seekTarget_ = offset;
        seekPending_ = true;
        pthread_cond_signal(&ioCond_);

        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 15;

        while (!(offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_)) {
            if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
                size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
                memcpy(data, buf_ + (offset - bufStart_), avail);
                pthread_mutex_unlock(&mu_);
                return (ssize_t)avail;
            }
            int rc = pthread_cond_timedwait(&cond_, &mu_, &deadline);
            if (rc != 0) {
                LOGW("readAt: timeout waiting for data @ %lld", (long long)offset);
                pthread_mutex_unlock(&mu_);
                return 0;
            }
        }

        memcpy(data, buf_ + (offset - bufStart_), size);

        size_t consumed = (size_t)(offset + size - bufStart_);
        if (consumed > BUF_CAP / 2 && bufFilled_ >= consumed) {
            size_t remaining = bufFilled_ - consumed;
            memmove(buf_, buf_ + consumed, remaining);
            bufStart_ = offset + size;
            bufFilled_ = remaining;
            pthread_cond_signal(&ioCond_);
        }

        pthread_mutex_unlock(&mu_);
        return (ssize_t)size;
    }

    off64_t getLength() override { return fileLength_; }
};

// ── NativeAudioEngine ───────────────────────────────────────────────

struct NativeAudioEngine {
    // Input
    AsyncBufferedDataSource *dataSource;
    FLACParser *parser;

    // USB output (owned by UsbAudioStream on the Java side)
    UsbAudioContext *usbCtx;

    // Stream info (from FLAC metadata)
    int sampleRate;
    int channels;
    int bitsPerSample;
    int dacBitDepth;  // from UsbAudioContext

    // Decode thread
    pthread_t thread;
    bool threadStarted;   // pthread_create succeeded and not yet joined
    std::atomic<bool> running;
    std::atomic<bool> paused;

    // ── Gapless: next track prepared off the audio path ──
    // nativeSetNextFd() opens and parses the next FLAC on a background
    // "preparer" thread (metadata can be MBs of cover art — far longer than
    // the 80 ms USB ring could cover). At end of stream the decode thread
    // swaps the prepared parser in and keeps feeding the SAME USB ring, so
    // there is no drain, no underrun and no gap between tracks.
    pthread_mutex_t nextMu;
    pthread_cond_t nextCv;
    int nextGeneration;             // bumped by every set/clear; stale preparers discard
    int preparersInFlight;          // detached preparer threads still running
    AsyncBufferedDataSource *nextDataSource;  // prepared, owned (null if none)
    FLACParser *nextParser;
    std::atomic<int> trackSwitches; // gapless file switches performed so far

    // Position tracking
    std::atomic<int64_t> framesDecoded;
    /** Pending seek target sample, -1 = none. Taken by the decode thread with
     *  exchange(-1), so a seek requested while another one is being carried
     *  out is never lost (the old target + flag pair could drop it, and the
     *  target itself was read and written unsynchronized). */
    std::atomic<int64_t> seekTarget;

    // Buffers
    uint8_t *pcmBuffer;       // raw decoded PCM from FLACParser
    uint8_t *convertBuffer;   // bit-depth converted PCM for USB
    size_t pcmBufferSize;
    size_t convertBufferSize;
};

// ── Thread priority ─────────────────────────────────────────────────

/** Android's ANDROID_PRIORITY_URGENT_AUDIO (nice -19), the level AudioFlinger's
 *  own mixer threads use. Apps may raise their own threads this far: zygote
 *  gives app processes an RLIMIT_NICE that allows it. SCHED_FIFO, which this
 *  engine used to request, is NOT permitted for apps (no CAP_SYS_NICE and
 *  RLIMIT_RTPRIO = 0), so that call always failed silently and left the
 *  decode thread — the only thread feeding the USB ring — at normal priority. */
static const int kUrgentAudioNice = -19;

static void raiseToUrgentAudioPriority(const char *who) {
    pid_t tid = (pid_t)syscall(SYS_gettid);
    if (setpriority(PRIO_PROCESS, (id_t)tid, kUrgentAudioNice) == 0) {
        LOGI("%s: thread %d raised to nice %d (urgent audio)", who, (int)tid, kUrgentAudioNice);
    } else {
        LOGW("%s: setpriority(%d) failed errno=%d (%s) — running at default priority",
             who, kUrgentAudioNice, errno, strerror(errno));
    }
}

// ── Gapless next-track handling ─────────────────────────────────────

/** Caller holds nextMu. Frees any prepared-but-unused next track. */
static void discardNextLocked(NativeAudioEngine *engine) {
    delete engine->nextParser;        // parser references the data source: delete it first
    delete engine->nextDataSource;
    engine->nextParser = nullptr;
    engine->nextDataSource = nullptr;
}

struct PrepareArgs {
    NativeAudioEngine *engine;
    int fd;          // owned (dup'd)
    int generation;
};

/** Background thread: open + parse the next FLAC's metadata, then hand it to
 *  the engine if nothing newer was queued meanwhile. Detached; the engine's
 *  destroy waits for preparersInFlight to reach zero. */
static void *prepareThreadFunc(void *arg) {
    auto *a = static_cast<PrepareArgs *>(arg);
    NativeAudioEngine *engine = a->engine;

    // Superseded before we even started (rapid queue edits): don't allocate an
    // 8 MB buffer + I/O thread and parse a file nobody will play.
    pthread_mutex_lock(&engine->nextMu);
    bool stale = a->generation != engine->nextGeneration;
    if (stale) {
        engine->preparersInFlight--;
        pthread_cond_broadcast(&engine->nextCv);
    }
    pthread_mutex_unlock(&engine->nextMu);
    if (stale) {
        close(a->fd);
        delete a;
        return nullptr;
    }

    auto *ds = new AsyncBufferedDataSource(a->fd, true);  // takes ownership of fd
    auto *parser = new FLACParser(ds);
    bool ok = parser->init() && parser->decodeMetadata();
    if (!ok) LOGW("Gapless: preparing next track failed (FLAC init/metadata)");

    pthread_mutex_lock(&engine->nextMu);
    if (ok && a->generation == engine->nextGeneration) {
        discardNextLocked(engine);
        engine->nextParser = parser;
        engine->nextDataSource = ds;
        parser = nullptr;
        ds = nullptr;
        LOGI("Gapless: next track prepared (%u Hz, %u ch, %u-bit)",
             engine->nextParser->getSampleRate(), engine->nextParser->getChannels(),
             engine->nextParser->getBitsPerSample());
    }
    engine->preparersInFlight--;
    pthread_cond_broadcast(&engine->nextCv);
    pthread_mutex_unlock(&engine->nextMu);

    delete parser;   // only non-null if not handed over (failed or superseded)
    delete ds;
    delete a;
    return nullptr;
}

/** True if this engine can play [bits]-per-sample PCM into its USB format. */
static bool isSupportedSourceDepth(const NativeAudioEngine *engine, int bits) {
    return bits == engine->dacBitDepth ||
           (engine->dacBitDepth == 32 && (bits == 16 || bits == 24));
}

/**
 * Called by the decode thread at end of stream. Swaps in the prepared next
 * track if one is (or, within a short bound, becomes) available and matches
 * the running USB stream's sample rate and channel count. Returns true if
 * decoding should simply continue with the new file.
 */
static bool takeNextTrack(NativeAudioEngine *engine) {
    FLACParser *np = nullptr;
    AsyncBufferedDataSource *nds = nullptr;

    pthread_mutex_lock(&engine->nextMu);
    // A preparer queued very late may still be parsing: wait briefly for it
    // rather than giving up on gapless (the ring holds ~80 ms, so a long
    // wait costs a gap either way — but not a skipped transition).
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 2;
    while (engine->nextParser == nullptr && engine->preparersInFlight > 0 &&
           engine->running.load()) {
        if (pthread_cond_timedwait(&engine->nextCv, &engine->nextMu, &deadline) != 0) break;
    }
    np = engine->nextParser;
    nds = engine->nextDataSource;
    engine->nextParser = nullptr;
    engine->nextDataSource = nullptr;
    pthread_mutex_unlock(&engine->nextMu);

    if (np == nullptr) return false;

    int rate = (int)np->getSampleRate();
    int ch = (int)np->getChannels();
    int bits = (int)np->getBitsPerSample();
    if (rate != engine->sampleRate || ch != engine->channels ||
        !isSupportedSourceDepth(engine, bits)) {
        LOGI("Gapless: next track %d Hz/%d ch/%d-bit does not match stream %d Hz/%d ch "
             "— ending normally (player reconfigures)",
             rate, ch, bits, engine->sampleRate, engine->channels);
        delete np;
        delete nds;
        return false;
    }

    // Grow decode buffers if the next file has a larger block size / depth.
    size_t maxBlock = np->getMaxBlockSize();
    size_t pcmNeed = maxBlock * (size_t)ch * (size_t)(bits / 8);
    size_t convNeed = maxBlock * (size_t)ch * 4;
    if (pcmNeed > engine->pcmBufferSize) {
        auto *b = (uint8_t *)realloc(engine->pcmBuffer, pcmNeed);
        if (!b) { delete np; delete nds; LOGE("Gapless: OOM growing pcm buffer"); return false; }
        engine->pcmBuffer = b;
        engine->pcmBufferSize = pcmNeed;
    }
    if (convNeed > engine->convertBufferSize) {
        auto *b = (uint8_t *)realloc(engine->convertBuffer, convNeed);
        if (!b) { delete np; delete nds; LOGE("Gapless: OOM growing convert buffer"); return false; }
        engine->convertBuffer = b;
        engine->convertBufferSize = convNeed;
    }

    // Only this thread uses parser/dataSource, so the swap needs no lock.
    delete engine->parser;
    delete engine->dataSource;
    engine->parser = np;
    engine->dataSource = nds;
    engine->bitsPerSample = bits;
    // A seek still pending here was aimed at the file that just ended.
    engine->seekTarget.store(-1);
    engine->framesDecoded.store(0);
    int n = engine->trackSwitches.fetch_add(1) + 1;
    LOGI("Gapless: switched to next track #%d (%d Hz, %d ch, %d-bit) without draining USB",
         n, rate, ch, bits);
    return true;
}

static void *decodeThreadFunc(void *arg) {
    auto *engine = static_cast<NativeAudioEngine *>(arg);
    raiseToUrgentAudioPriority("Decode thread");
    LOGI("Decode thread started: rate=%d ch=%d bits=%d dacBits=%d",
         engine->sampleRate, engine->channels,
         engine->bitsPerSample, engine->dacBitDepth);

    while (engine->running.load()) {
        // Handle pause
        if (engine->paused.load()) {
            usleep(20000);  // 20ms
            continue;
        }

        // Handle seek
        int64_t targetSample = engine->seekTarget.exchange(-1);
        if (targetSample >= 0) {
            FLAC__uint64 totalSamples = engine->parser->getTotalSamples();
            LOGI("Seek: target=%lld total=%llu state=%s",
                 (long long)targetSample, (unsigned long long)totalSamples,
                 engine->parser->getDecoderStateString());

            // Clamp to valid range
            if (targetSample < 0) targetSample = 0;
            if (totalSamples > 0 && (FLAC__uint64)targetSample >= totalSamples) {
                targetSample = (int64_t)(totalSamples - 1);
            }

            bool seekOk = engine->parser->seekAbsolute((FLAC__uint64)targetSample);
            if (seekOk) {
                engine->framesDecoded.store(targetSample);
                LOGI("Seek OK: sample %lld (%.1f sec), state=%s",
                     (long long)targetSample,
                     (double)targetSample / engine->sampleRate,
                     engine->parser->getDecoderStateString());
            } else {
                LOGE("Seek FAILED: target=%lld total=%llu state=%s — resetting",
                     (long long)targetSample, (unsigned long long)totalSamples,
                     engine->parser->getDecoderStateString());
                engine->parser->reset(0);
                engine->parser->decodeMetadata();
                engine->framesDecoded.store(0);
            }
        }

        // Decode one FLAC frame
        size_t bytesRead = engine->parser->readBuffer(
            engine->pcmBuffer, engine->pcmBufferSize);

        if (bytesRead == (size_t)-1 || bytesRead == 0) {
            if (engine->parser->isDecoderAtEndOfStream()) {
                LOGI("End of FLAC stream, %lld frames decoded",
                     (long long)engine->framesDecoded.load());
                // Gapless: continue straight into the queued next track (same
                // USB stream, residual bytes carried over) instead of exiting.
                if (engine->running.load() && takeNextTrack(engine)) continue;
            } else {
                LOGE("Decode error: %s",
                     engine->parser->getDecoderStateString());
            }
            break;
        }

        // Calculate frame count from decoded bytes
        int srcBytesPerSample = engine->bitsPerSample / 8;
        int srcBytesPerFrame = srcBytesPerSample * engine->channels;
        int framesInBuffer = (int)(bytesRead / srcBytesPerFrame);
        int totalSamples = framesInBuffer * engine->channels;

        // Convert bit depth: source → DAC
        const uint8_t *usbData;
        int usbBytes;

        if (engine->bitsPerSample == engine->dacBitDepth) {
            // Same bit depth — direct
            usbData = engine->pcmBuffer;
            usbBytes = (int)bytesRead;
        } else if (engine->bitsPerSample == 16 && engine->dacBitDepth == 32) {
            padInt16ToInt32(engine->pcmBuffer, engine->convertBuffer, totalSamples);
            usbData = engine->convertBuffer;
            usbBytes = totalSamples * 4;
        } else if (engine->bitsPerSample == 24 && engine->dacBitDepth == 32) {
            // FLACParser outputs 24-bit as packed 3-byte samples (little-endian)
            padInt24ToInt32(engine->pcmBuffer, engine->convertBuffer, totalSamples);
            usbData = engine->convertBuffer;
            usbBytes = totalSamples * 4;
        } else {
            LOGE("Unsupported bit-depth conversion: %d → %d",
                 engine->bitsPerSample, engine->dacBitDepth);
            break;
        }

        // Check running before USB submit (allows quick exit on stop)
        if (!engine->running.load()) break;

        // Submit to USB (blocks naturally on URB pipeline = perfect backpressure)
        submitPcmToUrbs(engine->usbCtx, usbData, usbBytes);

        int64_t newTotal = engine->framesDecoded.fetch_add(framesInBuffer) + framesInBuffer;
        // Log every ~1 second of audio
        if (newTotal % engine->sampleRate < framesInBuffer) {
            LOGI("Decode: %lld frames (~%.0f sec)",
                 (long long)newTotal, (double)newTotal / engine->sampleRate);
        }
    }

    engine->running.store(false);
    LOGI("Decode thread exited, %lld total frames",
         (long long)engine->framesDecoded.load());
    return nullptr;
}

// ── JNI entry points ────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreate(
        JNIEnv *, jobject, jstring jFilePath, jlong usbHandle) {
    // Will be implemented after Kotlin wrapper is ready
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreateFromFd(
        JNIEnv *, jobject, jint fd, jlong usbHandle) {
    auto *usbCtx = reinterpret_cast<UsbAudioContext *>(usbHandle);
    if (!usbCtx) {
        LOGE("nativeCreateFromFd: null USB context");
        return 0;
    }

    // Duplicate fd so we own it
    int ownedFd = dup(fd);
    if (ownedFd < 0) {
        LOGE("nativeCreateFromFd: dup() failed errno=%d", errno);
        return 0;
    }

    auto *ds = new AsyncBufferedDataSource(ownedFd, true);
    auto *parser = new FLACParser(ds);

    if (!parser->init()) {
        LOGE("nativeCreateFromFd: FLACParser init failed");
        delete parser;
        delete ds;
        return 0;
    }

    if (!parser->decodeMetadata()) {
        LOGE("nativeCreateFromFd: metadata decode failed");
        delete parser;
        delete ds;
        return 0;
    }

    auto *engine = new NativeAudioEngine();
    engine->dataSource = ds;
    engine->parser = parser;
    engine->usbCtx = usbCtx;
    engine->sampleRate = (int)parser->getSampleRate();
    engine->channels = (int)parser->getChannels();
    engine->bitsPerSample = (int)parser->getBitsPerSample();
    engine->dacBitDepth = usbCtx->bitDepth;
    engine->running.store(false);
    engine->paused.store(false);
    engine->framesDecoded.store(0);
    engine->seekTarget.store(-1);
    engine->threadStarted = false;
    pthread_mutex_init(&engine->nextMu, nullptr);
    pthread_cond_init(&engine->nextCv, nullptr);
    engine->nextGeneration = 0;
    engine->preparersInFlight = 0;
    engine->nextDataSource = nullptr;
    engine->nextParser = nullptr;
    engine->trackSwitches.store(0);

    // Allocate decode buffers
    size_t maxBlock = parser->getMaxBlockSize();
    engine->pcmBufferSize = maxBlock * engine->channels * (engine->bitsPerSample / 8);
    engine->convertBufferSize = maxBlock * engine->channels * 4;  // max 32-bit output
    engine->pcmBuffer = (uint8_t *)malloc(engine->pcmBufferSize);
    engine->convertBuffer = (uint8_t *)malloc(engine->convertBufferSize);

    if (!engine->pcmBuffer || !engine->convertBuffer) {
        LOGE("nativeCreateFromFd: buffer allocation failed");
        free(engine->pcmBuffer);
        free(engine->convertBuffer);
        delete parser;
        delete ds;
        pthread_cond_destroy(&engine->nextCv);
        pthread_mutex_destroy(&engine->nextMu);
        delete engine;
        return 0;
    }

    LOGI("Engine created: rate=%d ch=%d bits=%d dacBits=%d maxBlock=%zu",
         engine->sampleRate, engine->channels, engine->bitsPerSample,
         engine->dacBitDepth, maxBlock);

    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStart(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->running.load()) return JNI_FALSE;

    // A previous decode thread that ended on its own (EOF) is still joinable.
    if (engine->threadStarted) {
        pthread_join(engine->thread, nullptr);
        engine->threadStarted = false;
    }

    // The paused state is kept: the caller pauses a fresh engine BEFORE
    // starting it, so no audio from the start of the file reaches the DAC
    // before the first seek to the real position (it used to start playing
    // immediately and was paused a moment later — an audible blip on resume).
    engine->running.store(true);

    int ret = pthread_create(&engine->thread, nullptr, decodeThreadFunc, engine);
    if (ret != 0) {
        LOGE("nativeStart: pthread_create failed ret=%d", ret);
        engine->running.store(false);
        return JNI_FALSE;
    }
    engine->threadStarted = true;
    // Priority is raised by the decode thread itself (raiseToUrgentAudioPriority).

    LOGI("Engine started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativePause(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->paused.store(true);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->paused.store(false);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeSeek(
        JNIEnv *, jobject, jlong handle, jlong positionUs) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return JNI_FALSE;

    int64_t target = positionUs * engine->sampleRate / 1000000LL;
    if (target < 0) target = 0;
    // Update framesDecoded immediately so getCurrentPositionUs returns the
    // seek target right away, before the decode thread processes the seek.
    // Prevents ExoPlayer from seeing a stale backwards position jump.
    engine->framesDecoded.store(target);
    engine->seekTarget.store(target);
    LOGI("Seek requested: %lld us → sample %lld", (long long)positionUs, (long long)target);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStop(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    engine->running.store(false);
    engine->paused.store(false);
    // Wake a decode thread waiting for a late-prepared next track.
    pthread_mutex_lock(&engine->nextMu);
    pthread_cond_broadcast(&engine->nextCv);
    pthread_mutex_unlock(&engine->nextMu);
    if (engine->threadStarted) {
        pthread_join(engine->thread, nullptr);
        engine->threadStarted = false;
    }
    LOGI("Engine stopped");
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    engine->running.store(false);
    pthread_mutex_lock(&engine->nextMu);
    pthread_cond_broadcast(&engine->nextCv);
    pthread_mutex_unlock(&engine->nextMu);
    // Join even if the thread already ended by itself (EOF): an exited but
    // unjoined pthread leaks its stack.
    if (engine->threadStarted) {
        pthread_join(engine->thread, nullptr);
        engine->threadStarted = false;
    }

    // Invalidate any in-flight preparer and wait for it: it holds a pointer
    // to this engine's mutex.
    pthread_mutex_lock(&engine->nextMu);
    engine->nextGeneration++;
    discardNextLocked(engine);
    while (engine->preparersInFlight > 0) {
        pthread_cond_wait(&engine->nextCv, &engine->nextMu);
    }
    discardNextLocked(engine);  // a preparer can't hand over after the bump, but be safe
    pthread_mutex_unlock(&engine->nextMu);
    pthread_cond_destroy(&engine->nextCv);
    pthread_mutex_destroy(&engine->nextMu);

    free(engine->pcmBuffer);
    free(engine->convertBuffer);
    delete engine->parser;
    delete engine->dataSource;
    LOGI("Engine destroyed, %lld total frames, %d gapless switches",
         (long long)engine->framesDecoded.load(), engine->trackSwitches.load());
    delete engine;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeSetNextFd(
        JNIEnv *, jobject, jlong handle, jint fd) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || fd < 0) return JNI_FALSE;

    int owned = dup(fd);
    if (owned < 0) {
        LOGE("nativeSetNextFd: dup() failed errno=%d", errno);
        return JNI_FALSE;
    }

    auto *args = new PrepareArgs();
    args->engine = engine;
    args->fd = owned;

    pthread_mutex_lock(&engine->nextMu);
    engine->nextGeneration++;
    discardNextLocked(engine);
    args->generation = engine->nextGeneration;
    engine->preparersInFlight++;
    pthread_mutex_unlock(&engine->nextMu);

    pthread_t t;
    if (pthread_create(&t, nullptr, prepareThreadFunc, args) != 0) {
        LOGE("nativeSetNextFd: pthread_create failed");
        pthread_mutex_lock(&engine->nextMu);
        engine->preparersInFlight--;
        pthread_cond_broadcast(&engine->nextCv);
        pthread_mutex_unlock(&engine->nextMu);
        close(owned);
        delete args;
        return JNI_FALSE;
    }
    pthread_detach(t);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeClearNext(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;
    pthread_mutex_lock(&engine->nextMu);
    engine->nextGeneration++;   // any preparer still running will discard its result
    discardNextLocked(engine);
    pthread_mutex_unlock(&engine->nextMu);
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetTrackSwitchCount(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? (jint)engine->trackSwitches.load() : 0;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetPositionUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->sampleRate <= 0) return 0;
    return engine->framesDecoded.load() * 1000000LL / engine->sampleRate;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSampleRate(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetChannels(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->channels : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetBitsPerSample(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->bitsPerSample : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeIsRunning(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return (engine && engine->running.load()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
