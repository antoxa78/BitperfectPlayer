/*
 * Minimal compatibility shim for FFmpeg's libavutil/reverse.h used by the
 * vendored libavcodec/dsd.c.
 *
 * Original file is part of FFmpeg, licensed under LGPL-2.1-or-later.
 * See ../COPYING.LGPLv2.1
 */

#ifndef FFMPEG_AVUTIL_REVERSE_H
#define FFMPEG_AVUTIL_REVERSE_H

#include <stdint.h>

extern const uint8_t ff_reverse[256];

#endif /* FFMPEG_AVUTIL_REVERSE_H */