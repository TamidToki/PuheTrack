package com.puhetrack.app.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Conservatively copies only complete, consecutive MPEG audio frames from an interrupted MP3. */
object Mp3Recovery {
    data class Result(val encodedFrames: Int, val sampleFrames: Long, val durableBytes: Long)

    fun inspect(source: File): Result {
        val scan = scan(source) ?: return Result(0, 0, 0)
        return Result(scan.frames, scan.sampleFrames, scan.copyBytes)
    }

    fun recoverCopy(source: File, destination: File): Result {
        val scan = scan(source) ?: return Result(0, 0, 0)
        FileInputStream(source).use { input ->
            if (scan.copyStart > 0) input.channel.position(scan.copyStart)
            FileOutputStream(destination, false).use { output ->
                var remaining = scan.copyBytes
                val buffer = ByteArray(128 * 1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                check(remaining == 0L) { "Interrupted MP3 changed during recovery" }
                output.fd.sync()
            }
        }
        return Result(scan.frames, scan.sampleFrames, destination.length())
    }

    private data class Scan(
        val copyStart: Long,
        val copyBytes: Long,
        val frames: Int,
        val sampleFrames: Long
    )

    private data class Frame(val bytes: Int, val samples: Int)

    private fun scan(file: File): Scan? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4) return null
        val id3Bytes = id3PrefixBytes(input)
        val searchStart = id3Bytes.coerceAtMost(input.length())
        val first = findFirstFrame(input, searchStart, minOf(input.length() - 4, searchStart + 64 * 1024))
            ?: return null
        var position = first
        var lastComplete = first
        var frames = 0
        var samples = 0L
        while (position + 4 <= input.length()) {
            val frame = readFrame(input, position) ?: break
            if (position + frame.bytes > input.length()) break
            position += frame.bytes
            lastComplete = position
            frames++
            samples += frame.samples
        }
        if (frames == 0) return null
        val copyStart = if (first == searchStart && id3Bytes > 0) 0 else first
        Scan(copyStart, lastComplete - copyStart, frames, samples)
    }

    private fun id3PrefixBytes(input: RandomAccessFile): Long {
        if (input.length() < 10) return 0
        input.seek(0)
        val header = ByteArray(10)
        input.readFully(header)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
        val size = ((header[6].toInt() and 0x7f) shl 21) or
            ((header[7].toInt() and 0x7f) shl 14) or
            ((header[8].toInt() and 0x7f) shl 7) or
            (header[9].toInt() and 0x7f)
        return 10L + size
    }

    private fun findFirstFrame(input: RandomAccessFile, start: Long, endInclusive: Long): Long? {
        var position = start
        while (position <= endInclusive) {
            val first = readFrame(input, position)
            if (first != null && position + first.bytes + 4 <= input.length()) {
                // Two consecutive headers avoid treating random payload bytes as a frame.
                if (readFrame(input, position + first.bytes) != null) return position
            }
            position++
        }
        return null
    }

    private fun readFrame(input: RandomAccessFile, position: Long): Frame? {
        input.seek(position)
        val header = input.readInt()
        if ((header and 0xffe00000.toInt()) != 0xffe00000.toInt()) return null
        val versionBits = (header ushr 19) and 0x3
        val layerBits = (header ushr 17) and 0x3
        val bitrateIndex = (header ushr 12) and 0xf
        val sampleRateIndex = (header ushr 10) and 0x3
        val padding = (header ushr 9) and 0x1
        if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null

        val mpeg1 = versionBits == 3
        val bitrate = if (mpeg1) MPEG1_LAYER3_KBPS[bitrateIndex] else MPEG2_LAYER3_KBPS[bitrateIndex]
        val baseRate = SAMPLE_RATES[sampleRateIndex]
        val sampleRate = when (versionBits) {
            3 -> baseRate
            2 -> baseRate / 2
            0 -> baseRate / 4
            else -> return null
        }
        val bytes = ((if (mpeg1) 144_000L else 72_000L) * bitrate / sampleRate + padding).toInt()
        if (bytes < 24) return null
        return Frame(bytes, if (mpeg1) 1152 else 576)
    }

    private val SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)
    private val MPEG1_LAYER3_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_LAYER3_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
