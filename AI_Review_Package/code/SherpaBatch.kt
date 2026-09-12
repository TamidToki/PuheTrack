package com.puhetrack.app.batch

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SherpaBatch {
    private const val TAG = "SherpaBatch"
    private const val MODEL_DIR_NAME = "sherpa-parakeet-fi"

    suspend fun transcribe(context: Context, wavFile: File): String = withContext(Dispatchers.IO) {
        if (!wavFile.exists()) throw IllegalArgumentException("WAV file not found")
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists() || !File(dir, "encoder.int8.onnx").exists()) {
            throw IllegalStateException("Offline AI model not installed/found. Please ensure it was extracted.")
        }

        val config = OfflineRecognizerConfig(
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 4,
                debug = false,
                modelType = "nemo_transducer"
            ),
            decodingMethod = "greedy_search"
        )
        
        Log.i(TAG, "Initializing Sherpa OfflineRecognizer...")
        var recognizer: OfflineRecognizer? = null
        try {
            recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
            
            // Read 16kHz mono WAV file into float array
            val bytes = wavFile.readBytes()
            // Skip 44-byte WAV header
            val headerSize = 44
            if (bytes.size <= headerSize) return@withContext ""
            
            val pcmBytes = bytes.size - headerSize
            val samples = FloatArray(pcmBytes / 2)
            val bb = ByteBuffer.wrap(bytes, headerSize, pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.indices) {
                samples[i] = bb.short.toFloat() / 32768.0f
            }
            
            Log.i(TAG, "Decoding ${samples.size} samples...")
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()
            
            return@withContext result.text.trim()
        } finally {
            recognizer?.release()
        }
    }
}
