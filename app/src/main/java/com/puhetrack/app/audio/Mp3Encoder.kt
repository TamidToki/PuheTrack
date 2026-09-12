package com.puhetrack.app.audio

object Mp3Encoder {
    val available: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("puhe_lame")
            ok = true
        } catch (_: UnsatisfiedLinkError) {
            ok = false
        } catch (_: SecurityException) {
            ok = false
        }
        available = ok
    }

    @JvmStatic external fun nativeInit(path: String, sampleRate: Int, channels: Int, bitrateKbps: Int): Int
    @JvmStatic external fun nativeEncode(pcm: ShortArray, nsamples: Int): Int
    @JvmStatic external fun nativeCheckpoint(): Int
    @JvmStatic external fun nativeFlush(): Int
}
