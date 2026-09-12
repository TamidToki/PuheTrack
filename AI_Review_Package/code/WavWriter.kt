package com.puhetrack.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriter(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1
) : PcmSink {
    private var raf: RandomAccessFile? = null
    private var dataBytes: Long = 0

    override fun start() {
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        r.setLength(0)
        r.write(headerBytes(dataBytes))
        raf = r
        dataBytes = 0
    }

    override fun writeSamples(pcm16: ByteArray, length: Int) {
        if (length <= 0) return
        raf?.write(pcm16, 0, length)
        dataBytes += length
    }

    override fun stop() {
        val r = raf ?: return
        // rewrite header with final size
        r.seek(0)
        r.write(headerBytes(dataBytes))
        r.close()
        raf = null
    }

    private fun headerBytes(dataLen: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataLen).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * 2).toShort())
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen.toInt())
        return bb.array()
    }
}
