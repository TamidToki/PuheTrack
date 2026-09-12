/* Hand-written config.h for the PuheTrack Android build.
 * Replaces the autoconf-generated config.h (see lame 3.100 configMS.h
 * for the equivalent Windows file). Only what libmp3lame needs:
 * the ieee754 float typedef used by util.h/util.c.
 * Everything CPU-specific (TAKEHIRO_IEEE, HAVE_NASM, HAVE_XMMINTRIN_H,
 * USE_FAST_LOG) is intentionally left undefined so the portable C
 * code paths are used on ARM.
 */
#ifndef PUHE_LAME_CONFIG_H
#define PUHE_LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MATH_H 1
#define HAVE_LIMITS_H 1
#define HAVE_CTYPE_H 1
#define HAVE_ERRNO_H 1
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_STRCHR 1
#define HAVE_MEMCPY 1

typedef float ieee754_float32_t;

#endif
