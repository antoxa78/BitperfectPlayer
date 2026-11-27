/*
 * Minimal charset conversion shim for Android (bionic libc ships <iconv.h>
 * but not the iconv() implementation).  Substitutes the upstream
 * libcommon/charset.c which depends on iconv.
 *
 * Supports the conversions the SACD bridge actually needs:
 *   - UTF-8 passthrough
 *   - ISO-8859-1 / ASCII <-> UTF-8  (SACD text typically uses ISO-8859-1)
 *   - UTF-8 -> WCHAR_T (UTF-32LE byte array), used by scarletbook_print.c
 *
 * Replaces the API declared in libcommon/charset.h.
 */

#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <wchar.h>

#include "charset.h"

static int charset_is(const char *name, const char *target)
{
    return name && strncasecmp(name, target, strlen(target)) == 0;
}

static int is_utf8_name(const char *name)
{
    return charset_is(name, "utf-8") || charset_is(name, "utf8");
}

static int is_latin1_name(const char *name)
{
    return charset_is(name, "iso-8859-1") || charset_is(name, "latin1") ||
           charset_is(name, "iso8859-1") || charset_is(name, "iso_8859-1") ||
           charset_is(name, "us-ascii") || charset_is(name, "ascii") ||
           charset_is(name, "wchar_t") || charset_is(name, "ucs-2-internal") ||
           charset_is(name, "ucs-4-internal");
}

static int is_wide_name(const char *name)
{
    return charset_is(name, "wchar_t") || charset_is(name, "ucs-4-internal") ||
           charset_is(name, "ucs-2-internal");
}

static size_t utf8_encode(char *out, unsigned long cp)
{
    if (cp < 0x80) {
        out[0] = (char)cp;
        return 1;
    } else if (cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    } else if (cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    } else {
        out[0] = (char)(0xF0 | (cp >> 18));
        out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
        out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[3] = (char)(0x80 | (cp & 0x3F));
        return 4;
    }
}

/* Decodes the leading UTF-8 sequence; returns the codepoint and advances *in. */
static unsigned long utf8_decode(const unsigned char **in, size_t *left)
{
    const unsigned char *s = *in;
    unsigned long cp = 0;

    if (*left == 0)
        return 0xFFFD;
    if (s[0] < 0x80) {
        (*in)++; (*left)--;
        return s[0];
    }
    if ((s[0] & 0xE0) == 0xC0 && *left >= 2 && (s[1] & 0xC0) == 0x80) {
        cp = ((unsigned long)(s[0] & 0x1F) << 6) | (s[1] & 0x3F);
        *in += 2; *left -= 2;
        return cp;
    }
    if ((s[0] & 0xF0) == 0xE0 && *left >= 3 && (s[1] & 0xC0) == 0x80 && (s[2] & 0xC0) == 0x80) {
        cp = ((unsigned long)(s[0] & 0x0F) << 12) | ((unsigned long)(s[1] & 0x3F) << 6) | (s[2] & 0x3F);
        *in += 3; *left -= 3;
        return cp;
    }
    if ((s[0] & 0xF8) == 0xF0 && *left >= 4 && (s[1] & 0xC0) == 0x80 &&
        (s[2] & 0xC0) == 0x80 && (s[3] & 0xC0) == 0x80) {
        cp = ((unsigned long)(s[0] & 0x07) << 18) | ((unsigned long)(s[1] & 0x3F) << 12) |
             ((unsigned long)(s[2] & 0x3F) << 6) | (s[3] & 0x3F);
        *in += 4; *left -= 4;
        return cp;
    }
    /* invalid byte: consume one, replace with U+FFFD */
    (*in)++; (*left)--;
    return 0xFFFD;
}

char* charset_get_current(void)
{
    const char *env = getenv("CHARSET");
    return env && *env ? strdup(env) : strdup("UTF-8");
}

/*
 * Converts `instring` (insizebytes bytes) from charset `from` to charset `to`.
 * Returns a malloc'd, NUL-terminated buffer (length written to
 * *outsizebytes_ptr).  On allocation failure returns NULL.
 * Unsupported combinations degrade to a lossless passthrough copy.
 */
char* charset_convert_ext(const char *instring, size_t insizebytes,
                          size_t *outsizebytes_ptr, const char *from,
                          const char *to)
{
    char *out = NULL;
    size_t outsize = 0;

    if (instring == NULL) {
        if (outsizebytes_ptr) *outsizebytes_ptr = 0;
        return NULL;
    }
    if (outsizebytes_ptr) *outsizebytes_ptr = 0;

    /* Streaming codepoint decode needs the source non-NUL-terminated-safe. */
    if (is_wide_name(from)) {
        /* WCHAR_T -> UTF-8: each wchar_t is a 32-bit codepoint (wchar_t = 4 bytes). */
        size_t n = insizebytes / sizeof(wchar_t);
        const wchar_t *w = (const wchar_t *)instring;
        size_t i, cap = n * 4 + 1;
        out = malloc(cap);
        if (!out) return NULL;
        for (i = 0; i < n; i++) {
            outsize += utf8_encode(out + outsize, (unsigned long)w[i]);
        }
        out[outsize] = '\0';
    } else if (is_utf8_name(to)) {
        /* source = wide, latin1 or utf8 -> UTF-8 */
        if (is_wide_name(from)) {
            size_t n = insizebytes / sizeof(wchar_t);
            const wchar_t *w = (const wchar_t *)instring;
            size_t i, cap = n * 4 + 1;
            out = malloc(cap);
            if (!out) return NULL;
            for (i = 0; i < n; i++)
                outsize += utf8_encode(out + outsize, (unsigned long)w[i]);
            out[outsize] = '\0';
        } else if (is_latin1_name(from)) {
            size_t i, cap = insizebytes * 2 + 1;
            out = malloc(cap);
            if (!out) return NULL;
            for (i = 0; i < insizebytes; i++)
                outsize += utf8_encode(out + outsize, (unsigned char)instring[i]);
            out[outsize] = '\0';
        } else {
            /* UTF-8 or unknown: passthrough copy */
            out = malloc(insizebytes + 1);
            if (!out) return NULL;
            memcpy(out, instring, insizebytes);
            outsize = insizebytes;
            out[outsize] = '\0';
        }
    } else if (is_utf8_name(from)) {
        /* UTF-8 -> latin1 / ASCII / wide */
        if (is_wide_name(to)) {
            /* produce raw wchar_t array (wchar_t 4 bytes, -> UCS-4-INTERNAL) */
            size_t cap = (insizebytes + 1) * sizeof(wchar_t) + 4;
            wchar_t *w = malloc(cap);
            if (!w) return NULL;
            {
                size_t wi = 0;
                const unsigned char *in = (const unsigned char *)instring;
                size_t left = insizebytes;
                while (left) {
                    unsigned long cp = utf8_decode(&in, &left);
                    w[wi++] = (wchar_t)cp;
                }
                w[wi] = 0;
                out = (char *)w;
                outsize = wi * sizeof(wchar_t);
            }
        } else {
            size_t cap = insizebytes * 2 + 1;
            out = malloc(cap);
            if (!out) return NULL;
            {
                const unsigned char *in = (const unsigned char *)instring;
                size_t left = insizebytes;
                while (left) {
                    unsigned long cp = utf8_decode(&in, &left);
                    out[outsize++] = (char)((cp <= 0x7F || cp == 0xFFFD) ? (cp == 0xFFFD ? '?' : cp) : ((cp <= 0xFF) ? cp : '?'));
                }
            }
            out[outsize] = '\0';
        }
    } else {
        /* from/to unknown pair: passthrough */
        out = malloc(insizebytes + 1);
        if (!out) return NULL;
        memcpy(out, instring, insizebytes);
        outsize = insizebytes;
        out[outsize] = '\0';
    }

    if (outsizebytes_ptr) *outsizebytes_ptr = outsize;
    return out;
}

char* charset_convert(const char *string, size_t insizebytes,
                      const char *from, const char *to)
{
    size_t outsize = 0;
    char *out = charset_convert_ext(string, insizebytes, &outsize, from, to);
    if (out && out[outsize] != '\0')
        out[outsize] = '\0';
    return out;
}

wchar_t* utf8char2wchar(const char *instring)
{
    size_t n = instring ? strlen(instring) : 0;
    const unsigned char *in = (const unsigned char *)instring;
    size_t left = n;
    size_t wi = 0;
    wchar_t *w;

    if (!instring) {
        w = malloc(sizeof(wchar_t));
        if (w) w[0] = 0;
        return w;
    }

    /* worst case: all 2/4-byte sequences -> up to n codepoints */
    w = malloc((n + 1) * sizeof(wchar_t));
    if (!w) return NULL;
    while (left)
        w[wi++] = (wchar_t)utf8_decode(&in, &left);
    w[wi] = 0;
    return w;
}

char* charset_to_utf8(const char *string)
{
    if (!string) return NULL;
    return charset_convert(string, strlen(string), charset_get_current(), "UTF-8");
}

char* charset_from_utf8(const char *string)
{
    if (!string) return NULL;
    return charset_convert(string, strlen(string), "UTF-8", charset_get_current());
}