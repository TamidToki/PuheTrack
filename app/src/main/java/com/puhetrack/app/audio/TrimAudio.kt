package com.puhetrack.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.puhetrack.app.data.Seg
import com.puhetrack.app.data.readSegments
import com.puhetrack.app.data.sidecar
import com.puhetrack.app.data.writeSegmentsJson
import com.puhetrack.app.data.writeSrt
import com.puhetrack.app.data.writeTxt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object TrimAudio {
    data class Result(val clip: File, val segments: Int)

    suspend fun trimToMp3(
        src: File,
        startSec: Double,
        endSec: Double,
        bitrateKbps: Int
    ): Result = withContext(Dispatchers.IO) {
        val startUs = (startSec.coerceAtLeast(0.0) * 1_000_000).toLong()
        val endUs = (endSec * 1_000_000).toLong()
        require(endUs > startUs) { "end must be after start" }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var writer: Mp3Writer? = null
        val base = src.nameWithoutExtension + "-clip-" + System.currentTimeMillis()
        val clip = File(src.parent, "$base.mp3")
        val part = File(src.parent, "$base.mp3.part")
        var committed = false
        try {
            extractor.setDataSource(src.absolutePath)
        var track = -1
        var mime: String? = null
        var sampleRate = 16000
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME)
            if (m?.startsWith("audio/") == true) {
                track = i
                mime = m
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                break
            }
        }
        require(track >= 0 && mime != null) { "no audio track" }
        extractor.selectTrack(track)

        val activeDecoder = MediaCodec.createDecoderByType(mime!!)
        decoder = activeDecoder
        activeDecoder.configure(extractor.getTrackFormat(track), null, null, 0)
        activeDecoder.start()

        var totalPcmBytes = 0L
        var outIsFloat = false
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        // Skip everything before startUs (seek lands on a sync frame earlier)
        while (!outputDone) {
            if (!inputDone) {
                val inIx = activeDecoder.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    val buf = activeDecoder.getInputBuffer(inIx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0 || extractor.sampleTime > endUs) {
                        activeDecoder.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        activeDecoder.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIx = activeDecoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Some decoders output float PCM or a different rate —
                    // adapt instead of writing garbage to the MP3.
                    val of = activeDecoder.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        check(writer == null || newRate == sampleRate) { "decoder sample rate changed mid-clip" }
                        sampleRate = newRate
                    }
                    outIsFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                outIx >= 0 -> {
                    if (info.size > 0 && info.presentationTimeUs >= startUs &&
                        info.presentationTimeUs <= endUs
                    ) {
                        val buf: ByteBuffer = activeDecoder.getOutputBuffer(outIx)!!
                        val raw = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(raw)
                        val bytes = if (outIsFloat) floatToPcm16(raw) else raw
                        val aligned = bytes.size and -2
                        if (aligned > 0) {
                            val sink = writer ?: Mp3Writer(
                                part,
                                sampleRate = sampleRate,
                                bitrateKbps = bitrateKbps
                            ).also {
                                it.start()
                                writer = it
                            }
                            sink.writeSamples(bytes, aligned)
                            totalPcmBytes += aligned
                        }
                    }
                    activeDecoder.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) outputDone = true
                }
            }
        }
        require(totalPcmBytes > 0 && writer != null) { "no audio in range" }
        writer!!.stop()
        writer = null
        if (!part.renameTo(clip)) {
            throw java.io.IOException("could not commit trimmed clip")
        }
        committed = true

        // Slice the transcript sidecar to the same range, if present
        val srcJson = sidecar(src, "json")
        val all = readSegments(srcJson)
        val kept = all.filter { it.startMs >= startUs / 1000 && it.startMs < endUs / 1000 }
            .map { it.copy(startMs = it.startMs - startUs / 1000) }
        if (kept.isNotEmpty()) {
            writeSegmentsJson(sidecar(clip, "json"), kept)
            writeTxt(sidecar(clip, "txt"), kept)
            writeSrt(sidecar(clip, "srt"), kept)
        }
        Result(clip, kept.size)
        } finally {
            runCatching { writer?.stop() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            if (!committed) part.delete()
        }
    }

    private fun floatToPcm16(raw: ByteArray): ByteArray {
        val n = raw.size / 4
        val out = ByteArray(n * 2)
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var o = 0
        repeat(n) {
            val v = (bb.float * 32767f).toInt().coerceIn(-32768, 32767)
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
