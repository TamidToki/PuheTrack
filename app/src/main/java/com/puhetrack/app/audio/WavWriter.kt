package com.puhetrack.app.audio

import java.io.File
import java.io.IOException
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
    private var bytesSinceCheckpoint: Long = 0

    override fun start() {
        file.parentFile?.mkdirs()
        val r = RandomAccessFile(file, "rw")
        dataBytes = 0
        bytesSinceCheckpoint = 0
        r.setLength(0)
        r.write(headerBytes(dataBytes))
        raf = r
    }

    override fun writeSamples(pcm16: ByteArray, length: Int) {
        if (length <= 0) return
        require(length <= pcm16.size) { "Invalid PCM length" }
        require(length % (channels * 2) == 0) { "PCM data is not frame-aligned" }
        val r = raf ?: throw IOException("WAV writer is not started")
        r.write(pcm16, 0, length)
        dataBytes += length
        bytesSinceCheckpoint += length
        // Checkpoint about once per captured second. After a hard process kill,
        // the WAV header therefore points to all but at most the latest second.
        if (bytesSinceCheckpoint >= sampleRate * channels * 2L) {
            checkpoint(r)
            bytesSinceCheckpoint = 0
        }
    }

    override fun stop() {
        val r = raf ?: return
        try {
            checkpoint(r)
        } finally {
            try { r.close() } finally { raf = null }
        }
    }

    private fun checkpoint(r: RandomAccessFile) {
        val end = 44L + dataBytes
        r.seek(0)
        r.write(headerBytes(dataBytes))
        r.seek(end)
        r.fd.sync()
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

    companion object {
        /** Repairs a copy of a 16 kHz mono PCM16 recording after process death. */
        fun recoverCopy(source: File, destination: File): Long {
            require(source.length() >= 44L) { "WAV is too small to recover" }
            destination.parentFile?.mkdirs()
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            val recoverableBytes = ((destination.length() - 44L).coerceAtLeast(0L) / 2L) * 2L
            RandomAccessFile(destination, "rw").use { r ->
                r.setLength(44L + recoverableBytes)
                val writer = WavWriter(destination)
                r.seek(0)
                r.write(writer.headerBytes(recoverableBytes))
                r.fd.sync()
            }
            return recoverableBytes / 2L
        }
    }
}
