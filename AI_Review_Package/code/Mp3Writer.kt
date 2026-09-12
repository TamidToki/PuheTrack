package com.puhetrack.app.audio

import java.io.File

class Mp3Writer(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val bitrateKbps: Int = 96
) : PcmSink {
    private val shortBuf = ShortArray(8192)
    private var framesOut: Long = 0
    var bytesOut: Long = 0
        private set

    override fun start() {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val rc = Mp3Encoder.nativeInit(file.absolutePath, sampleRate, 1, bitrateKbps)
        if (rc != 0) throw IllegalStateException("mp3 init failed: $rc")
        // Write 0.5s of silence to guarantee valid MP3 frames even if session is skipped/tiny
        writeSamples(ByteArray(sampleRate), sampleRate)
    }

    override fun writeSamples(pcm16: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val chunkBytes = minOf((shortBuf.size * 2), length - off)
            val n = chunkBytes / 2
            var i = 0
            while (i < n) {
                val lo = pcm16[off + i * 2].toInt() and 0xFF
                val hi = pcm16[off + i * 2 + 1].toInt()
                shortBuf[i] = ((hi shl 8) or lo).toShort()
                i++
            }
            val written = Mp3Encoder.nativeEncode(shortBuf, n)
            if (written > 0) bytesOut += written
            framesOut += n
            off += chunkBytes
        }
    }

    override fun stop() {
        Mp3Encoder.nativeFlush()
    }
}
