/*
 * Minimal compatibility shim for FFmpeg's libavutil/attributes.h used by
 * the vendored libavcodec/dsd.c.  Provides only the attribute macros that
 * dsd.c actually uses.
 *
 * Orginal file is part of FFmpeg, licensed under LGPL-2.1-or-later.
 * See ../COPYING.LGPLv2.1
 */

#ifndef FFMPEG_AVUTIL_ATTRIBUTES_H
#define FFMPEG_AVUTIL_ATTRIBUTES_H

#ifndef av_cold
#    define av_cold
#endif

#ifndef av_always_inline
#    define av_always_inline inline __attribute__((always_inline))
#endif

#ifndef av_used
#    define av_used __attribute__((used))
#endif

#endif /* FFMPEG_AVUTIL_ATTRIBUTES_H */