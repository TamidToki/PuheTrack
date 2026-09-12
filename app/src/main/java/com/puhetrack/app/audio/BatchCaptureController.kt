package com.puhetrack.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.puhetrack.app.data.ChunkManifest
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import kotlinx.coroutines.delay
import java.io.IOException

/** Owns the single batch-mode microphone stream and exact frame rotation. */
class BatchCaptureController(
    private val context: Context,
    private val repository: RecordingRepository,
    private val session: SessionManifest,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCaptureStarted(formatLabel: String)
        fun onLevel(level: Float)
        fun onProgress(totalFrames: Long, chunkSequence: Int, chunkFrames: Long)
        fun onChunkClosed(manifestFile: java.io.File, manifest: ChunkManifest)
        fun onPauseAcknowledged(paused: Boolean)
        /** A non-blocking tap for consumers such as Live speech recognition. */
        fun onPcm(buffer: ByteArray, byteCount: Int) {}
    }

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var recorder: AudioRecord? = null
    private var stopReason = "user_stop"

    fun requestPause() { pauseRequested = true }
    fun requestResume() { pauseRequested = false }
    fun requestStop(reason: String = "user_stop") {
        stopReason = reason
        stopRequested = true
        pauseRequested = false
    }

    @SuppressLint("MissingPermission")
    suspend fun run() {
        val sampleRate = ChunkClock.SAMPLE_RATE
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        require(minBuffer > 0) { "No supported 16 kHz microphone configuration" }
        val bufferSize = maxOf(minBuffer * 4, 16_384)
        val buffer = ByteArray(bufferSize)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            encoding,
            bufferSize
        )
        recorder = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            recorder = null
            throw IOException("Microphone initialization failed")
        }

        // Live mode always archives compact MP3. Batch mode keeps WAV when the
        // offline model needs PCM input and MP3 for the cloud workflow.
        val useWav = session.config.mode == RecordingMode.BATCH &&
            session.config.batchEngine == BatchEngine.OFFLINE
        val clock = ChunkClock()
        var totalFrames = 0L
        var handle: RecordingRepository.ChunkHandle? = null
        var writer: PcmSink? = null
        var framesInOpenChunk = 0L
        var captureFailure: Throwable? = null

        fun openChunkIfNeeded() {
            if (handle != null) return
            if (!StoragePolicy.canStart(session.config, repository.root.usableSpace)) {
                throw IOException("Not enough free storage for the next complete chunk")
            }
            val opened = repository.openChunk(
                session = session,
                sequence = clock.chunkSequence,
                offsetMs = totalFrames * 1000L / sampleRate
            )
            val openedWriter: PcmSink = if (useWav) {
                WavWriter(opened.partFile, sampleRate = sampleRate)
            } else {
                Mp3Writer(
                    opened.partFile,
                    sampleRate = sampleRate,
                    bitrateKbps = session.config.bitrateKbps
                )
            }
            try {
                openedWriter.start()
            } catch (failure: Throwable) {
                repository.markOpenChunkInterrupted(opened, 0, "audio_writer_start_failed")
                throw failure
            }
            handle = opened
            writer = openedWriter
            framesInOpenChunk = 0
        }

        fun closeChunk(reason: String) {
            val closingHandle = handle ?: return
            val closingWriter = writer ?: return
            if (framesInOpenChunk <= 0) {
                runCatching { closingWriter.stop() }
                repository.markOpenChunkInterrupted(closingHandle, 0, "empty_chunk")
                handle = null
                writer = null
                return
            }
            try {
                closingWriter.stop()
                val closed = repository.closeChunk(closingHandle, framesInOpenChunk, reason)
                callbacks.onChunkClosed(closingHandle.manifestFile, closed)
            } catch (failure: Throwable) {
                repository.markOpenChunkInterrupted(closingHandle, framesInOpenChunk, "audio_close_failed")
                throw failure
            } finally {
                handle = null
                writer = null
                framesInOpenChunk = 0
            }
        }

        try {
            record.startRecording()
            callbacks.onCaptureStarted(
                if (useWav) "WAV · 16 kHz · mono" else "MP3 · ${session.config.bitrateKbps} kbps"
            )
            while (!stopRequested) {
                if (pauseRequested) {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    callbacks.onPauseAcknowledged(true)
                    while (pauseRequested && !stopRequested) delay(100)
                    if (stopRequested) break
                    record.startRecording()
                    callbacks.onPauseAcknowledged(false)
                }

                val count = record.read(buffer, 0, buffer.size)
                if (count < 0) throw IOException("Microphone read failed ($count)")
                val alignedBytes = count and -2
                if (alignedBytes == 0) continue
                callbacks.onLevel(computePeak(buffer, alignedBytes))

                clock.split(alignedBytes / 2).forEach { slice ->
                    openChunkIfNeeded()
                    val byteOffset = slice.sourceFrameOffset * 2
                    val byteCount = slice.frameCount * 2
                    if (byteOffset == 0 && byteCount == alignedBytes) {
                        writer!!.writeSamples(buffer, byteCount)
                    } else {
                        writer!!.writeSamples(buffer.copyOfRange(byteOffset, byteOffset + byteCount), byteCount)
                    }
                    framesInOpenChunk += slice.frameCount
                    totalFrames += slice.frameCount
                    callbacks.onProgress(totalFrames, slice.chunkSequence, framesInOpenChunk)
                    if (slice.closesChunk) closeChunk("thirty_minute_boundary")
                }
                // Local audio is committed to its encoder first. A slow or failed
                // recognition consumer must never prevent the recording archive.
                callbacks.onPcm(buffer, alignedBytes)
            }
        } catch (failure: Throwable) {
            captureFailure = failure
            throw failure
        } finally {
            val openHandle = handle
            val openWriter = writer
            if (openHandle != null && openWriter != null) {
                if (framesInOpenChunk > 0) {
                    try {
                        openWriter.stop()
                        val closed = repository.closeChunk(
                            openHandle,
                            framesInOpenChunk,
                            if (captureFailure == null) stopReason else "capture_error"
                        )
                        callbacks.onChunkClosed(openHandle.manifestFile, closed)
                    } catch (_: Throwable) {
                        runCatching {
                            repository.markOpenChunkInterrupted(openHandle, framesInOpenChunk, "capture_teardown_failed")
                        }
                    }
                } else {
                    runCatching { openWriter.stop() }
                    runCatching { repository.markOpenChunkInterrupted(openHandle, 0, "empty_chunk") }
                }
            }
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            record.release()
            recorder = null
            callbacks.onLevel(0f)
        }
    }

    private fun computePeak(buffer: ByteArray, count: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < count) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }
}
