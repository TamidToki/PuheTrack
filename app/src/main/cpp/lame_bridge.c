// JNI bridge: streams 16-bit PCM from Kotlin and writes MP3 via LAME.
// Single recording session at a time (one global encoder handle).

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include "lame.h"

static lame_global_flags *gfp = NULL;
static FILE *outfile = NULL;

JNIEXPORT jint JNICALL
Java_com_puhetrack_app_audio_Mp3Encoder_nativeInit(JNIEnv *env, jclass clazz,
                                                   jstring path,
                                                   jint sampleRate,
                                                   jint channels,
                                                   jint bitrateKbps) {
    if (gfp) {
        lame_close(gfp);
        gfp = NULL;
    }
    if (outfile) {
        fclose(outfile);
        outfile = NULL;
    }

    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return -1;
    outfile = fopen(cpath, "wb");
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (!outfile) return -1;

    gfp = lame_init();
    if (!gfp) {
        fclose(outfile);
        outfile = NULL;
        return -2;
    }
    lame_set_in_samplerate(gfp, sampleRate);
    lame_set_num_channels(gfp, channels);
    lame_set_brate(gfp, bitrateKbps);
    lame_set_mode(gfp, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_quality(gfp, 5); // fast, transparent for speech
    lame_set_bWriteVbrTag(gfp, 0);
    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        gfp = NULL;
        fclose(outfile);
        outfile = NULL;
        return -3;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_puhetrack_app_audio_Mp3Encoder_nativeEncode(JNIEnv *env, jclass clazz,
                                                     jshortArray pcm,
                                                     jint nsamples) {
    if (!gfp || !outfile) return -1;
    jshort *buf = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (!buf) return -2;
    int mp3buf_size = (int) (1.25 * nsamples + 7200);
    unsigned char *mp3buf = (unsigned char *) malloc(mp3buf_size);
    if (!mp3buf) {
        (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
        return -3;
    }
    int n = 0;
    if (lame_get_num_channels(gfp) == 1) {
        n = lame_encode_buffer(gfp, buf, NULL, nsamples, mp3buf, mp3buf_size);
    } else {
        n = lame_encode_buffer_interleaved(gfp, buf, nsamples, mp3buf, mp3buf_size);
    }
    (*env)->ReleaseShortArrayElements(env, pcm, buf, JNI_ABORT);
    if (n < 0) {
        free(mp3buf);
        return n;
    }
    int written = 0;
    if (n > 0) {
        written = (int) fwrite(mp3buf, 1, (size_t) n, outfile);
        if (written != n) {
            free(mp3buf);
            return -100;
        }
    }
    free(mp3buf);
    return written;
}

JNIEXPORT jint JNICALL
Java_com_puhetrack_app_audio_Mp3Encoder_nativeFlush(JNIEnv *env, jclass clazz) {
    int total = 0;
    if (gfp && outfile) {
        unsigned char mp3buf[7200];
        int n = lame_encode_flush(gfp, mp3buf, sizeof(mp3buf));
        if (n < 0) total = n;
        if (n > 0) {
            int written = (int) fwrite(mp3buf, 1, (size_t) n, outfile);
            total = written == n ? written : -100;
        }
        if (fflush(outfile) != 0 && total >= 0) total = -101;
        if (fclose(outfile) != 0 && total >= 0) total = -102;
        outfile = NULL;
        lame_close(gfp);
        gfp = NULL;
    }
    return total;
}

JNIEXPORT jint JNICALL
Java_com_puhetrack_app_audio_Mp3Encoder_nativeCheckpoint(JNIEnv *env, jclass clazz) {
    if (!outfile) return -1;
    if (fflush(outfile) != 0) return -101;
    int fd = fileno(outfile);
    if (fd < 0 || fsync(fd) != 0) return -103;
    return 0;
}
