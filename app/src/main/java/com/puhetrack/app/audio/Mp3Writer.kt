package com.puhetrack.app.audio

import java.io.File
import java.io.IOException

class Mp3Writer(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val bitrateKbps: Int = 96
) : PcmSink {
    private val shortBuf = ShortArray(8192)
    private var framesOut: Long = 0
    private var framesSinceCheckpoint: Long = 0
    private var started = false
    var bytesOut: Long = 0
        private set

    override fun start() {
        check(!started) { "MP3 writer already started" }
        acquireEncoderLease()
        file.parentFile?.mkdirs()
        try {
            framesOut = 0
            framesSinceCheckpoint = 0
            bytesOut = 0
            if (file.exists() && !file.delete()) throw IOException("Could not replace ${file.name}")
            val rc = Mp3Encoder.nativeInit(file.absolutePath, sampleRate, 1, bitrateKbps)
            if (rc != 0) throw IOException("MP3 encoder initialization failed ($rc)")
            started = true
        } catch (t: Throwable) {
            releaseEncoderLease()
            throw t
        }
    }

    override fun writeSamples(pcm16: ByteArray, length: Int) {
        check(started) { "MP3 writer is not started" }
        require(length in 0..pcm16.size) { "Invalid PCM length" }
        require(length % 2 == 0) { "PCM16 length must be frame-aligned" }
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
            if (written < 0) throw IOException("MP3 encoding failed ($written)")
            bytesOut += written
            framesOut += n
            framesSinceCheckpoint += n
            if (framesSinceCheckpoint >= sampleRate) {
                val checkpoint = Mp3Encoder.nativeCheckpoint()
                if (checkpoint != 0) throw IOException("MP3 durability checkpoint failed ($checkpoint)")
                framesSinceCheckpoint = 0
            }
            off += chunkBytes
        }
    }

    override fun stop() {
        if (!started) return
        try {
            val written = Mp3Encoder.nativeFlush()
            if (written < 0) throw IOException("MP3 finalization failed ($written)")
            bytesOut += written
        } finally {
            started = false
            releaseEncoderLease()
        }
    }

    private fun acquireEncoderLease() {
        synchronized(encoderLock) {
            check(activeOwner == null) {
                "The MP3 encoder is in use by an active recording. Stop recording before trimming."
            }
            activeOwner = this
        }
    }

    private fun releaseEncoderLease() {
        synchronized(encoderLock) {
            if (activeOwner === this) activeOwner = null
        }
    }

    companion object {
        private val encoderLock = Any()
        private var activeOwner: Mp3Writer? = null

        fun isEncoderInUse(): Boolean = synchronized(encoderLock) { activeOwner != null }
    }
}
