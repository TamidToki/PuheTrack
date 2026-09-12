package com.puhetrack.app.batch

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.puhetrack.app.models.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native owner used only by the isolated inference process. */
object SherpaBatch {
    private const val SAMPLE_RATE = 16_000
    private const val WINDOW_SECONDS = 30
    private const val WINDOW_BYTES = SAMPLE_RATE * WINDOW_SECONDS * 2

    private val ownerMutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    suspend fun transcribe(context: Context, wavFile: File): BatchTranscript =
        withContext(Dispatchers.IO) {
            ownerMutex.withLock {
                require(wavFile.isFile) { "WAV file is unavailable" }
                val info = parseWav(wavFile)
                val rec = recognizer ?: buildRecognizer(context).also { recognizer = it }
                val output = mutableListOf<BatchTranscriptSegment>()
                RandomAccessFile(wavFile, "r").use { input ->
                    input.seek(info.dataOffset)
                    var remaining = info.dataBytes
                    var windowStartFrames = 0L
                    val pcm = ByteArray(WINDOW_BYTES)
                    while (remaining > 0) {
                        val wanted = minOf(pcm.size.toLong(), remaining).toInt() and -2
                        if (wanted <= 0) break
                        input.readFully(pcm, 0, wanted)
                        val samples = FloatArray(wanted / 2)
                        val bb = ByteBuffer.wrap(pcm, 0, wanted).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in samples.indices) samples[i] = bb.short.toFloat() / 32768f
                        val stream = rec.createStream()
                        try {
                            stream.acceptWaveform(samples, SAMPLE_RATE)
                            rec.decode(stream)
                            val text = rec.getResult(stream).text.trim()
                            if (text.isNotBlank()) {
                                val startMs = windowStartFrames * 1000 / SAMPLE_RATE
                                val endMs = (windowStartFrames + samples.size) * 1000 / SAMPLE_RATE
                                output += BatchTranscriptSegment(text, startMs, endMs, "window_30s")
                            }
                        } finally {
                            stream.release()
                        }
                        remaining -= wanted
                        windowStartFrames += wanted / 2
                    }
                }
                BatchTranscript(output)
            }
        }

    private fun buildRecognizer(context: Context): OfflineRecognizer {
        val dir = (ModelRepository.status(context) as? ModelRepository.Status.Ready)?.directory
            ?: throw IllegalStateException("Offline model is not installed and verified")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                debug = false,
                provider = "cpu",
                modelType = "nemo_transducer"
            ),
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
        // Absolute paths must be loaded from the filesystem, not Android assets.
        return OfflineRecognizer(null, config)
    }

    private data class WavInfo(val dataOffset: Long, val dataBytes: Long)

    private fun parseWav(file: File): WavInfo = RandomAccessFile(file, "r").use { input ->
        require(input.length() >= 44) { "WAV is truncated" }
        val riff = ByteArray(12)
        input.readFully(riff)
        require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "Invalid WAV header" }
        var validFormat = false
        while (input.filePointer + 8 <= input.length()) {
            val idBytes = ByteArray(4)
            input.readFully(idBytes)
            val id = String(idBytes)
            val size = readLeInt(input).toLong() and 0xffffffffL
            val body = input.filePointer
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    val format = readLeShort(input)
                    val channels = readLeShort(input)
                    val rate = readLeInt(input)
                    input.skipBytes(6)
                    val bits = readLeShort(input)
                    validFormat = format == 1 && channels == 1 && rate == SAMPLE_RATE && bits == 16
                }
                "data" -> {
                    require(validFormat) { "WAV must be PCM16 mono at 16 kHz" }
                    return@use WavInfo(body, minOf(size, input.length() - body) / 2 * 2)
                }
            }
            input.seek(body + size + (size and 1L))
        }
        error("WAV data chunk not found")
    }

    private fun readLeShort(input: RandomAccessFile): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        return a or (b shl 8)
    }

    private fun readLeInt(input: RandomAccessFile): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        val c = input.readUnsignedByte()
        val d = input.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }

    suspend fun shutdown() = ownerMutex.withLock {
        recognizer?.release()
        recognizer = null
    }
}
