/*
 * Minimal compatibility shim for FFmpeg's libavutil/thread.h used by the
 * vendored libavcodec/dsd.c.  Maps ff_thread_once() onto POSIX pthread_once.
 *
 * Original file is part of FFmpeg, licensed under LGPL-2.1-or-later.
 * See ../COPYING.LGPLv2.1
 */

#ifndef FFMPEG_AVUTIL_THREAD_H
#define FFMPEG_AVUTIL_THREAD_H

#include <pthread.h>
#include "attributes.h"

typedef pthread_once_t AVOnce;

#define AV_ONCE_INIT PTHREAD_ONCE_INIT

static av_cold inline int ff_thread_once(AVOnce *once, void (*routine)(void))
{
    return pthread_once(once, routine);
}

#endif /* FFMPEG_AVUTIL_THREAD_H */