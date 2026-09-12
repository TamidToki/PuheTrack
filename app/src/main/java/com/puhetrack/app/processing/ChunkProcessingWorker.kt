package com.puhetrack.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.puhetrack.app.RecordTranslateService
import com.puhetrack.app.batch.OfflineInferenceClient
import com.puhetrack.app.batch.OpenAIBatch
import com.puhetrack.app.data.CanonicalSegment
import com.puhetrack.app.data.ChunkLifecycle
import com.puhetrack.app.data.ProcessingStage
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.TranslationState
import com.puhetrack.app.models.ModelRepository
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.translate.TranslatorFactory
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

class ChunkProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val repository = RecordingRepository(appContext)
    private val settings = SettingsStore(appContext)

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_MANIFEST_PATH) ?: return Result.failure()
        val manifestFile = File(path)
        if (!manifestFile.isFile) return Result.success()
        val lease = runCatching { repository.acquireFileLease(manifestFile, "processing") }.getOrNull()
            ?: return Result.retry()
        lease.use {
            try {
                var manifest = repository.readManifest(manifestFile)
                if (manifest.lifecycle != ChunkLifecycle.CLOSED) return Result.success()
                if (manifest.processingStage == ProcessingStage.COMPLETE) return Result.success()
                if (manifest.processingStage == ProcessingStage.OUTCOME_UNKNOWN) {
                    // A timed-out/disconnected request may have completed and been billed remotely.
                    // Preserve this state until the user explicitly confirms a paid repeat.
                    return Result.success()
                }

                var transcript = repository.readTranscript(repository.transcriptFile(manifest))
                if (transcript == null || transcript.segments.none { it.textFi.isNotBlank() }) {
                    if (manifest.transcriber == "SHERPA" && RecordTranslateService.running) {
                        retryLater(manifestFile, "capture_in_progress")
                        return Result.retry()
                    }
                    val audio = File(repository.root, manifest.audioFileName)
                    if (manifest.audioSha256 == null) {
                        manifest = repository.updateManifest(manifestFile) {
                            it.copy(audioSha256 = RecordingRepository.sha256File(audio))
                        }
                    }
                    manifest = repository.updateManifest(manifestFile) {
                        it.copy(
                            processingStage = ProcessingStage.TRANSCRIBING,
                            attemptId = UUID.randomUUID().toString(),
                            retryCount = it.retryCount + 1,
                            errorCode = null,
                            prerequisite = null
                        )
                    }
                    val batch = if (manifest.transcriber == "SHERPA") {
                        if (ModelRepository.status(applicationContext) !is ModelRepository.Status.Ready) {
                            block(manifestFile, "offline_model_missing", "install_offline_model")
                            return Result.success()
                        }
                        OfflineInferenceClient.transcribe(applicationContext, audio)
                    } else {
                        if (settings.openaiKey.isBlank()) {
                            block(manifestFile, "openai_key_missing", "add_openai_key")
                            return Result.success()
                        }
                        OpenAIBatch.transcribe(audio, settings.openaiKey)
                    }
                    val canonical = batch.segments.mapIndexed { index, segment ->
                        CanonicalSegment(
                            segmentId = UUID.nameUUIDFromBytes("${manifest.chunkId}:$index".toByteArray()).toString(),
                            startMs = segment.startMs,
                            endMs = maxOf(segment.endMs, segment.startMs),
                            textFi = segment.text,
                            timingProvenance = segment.timingProvenance,
                            chunkId = manifest.chunkId
                        )
                    }
                    manifest = repository.commitFinnish(manifestFile, canonical)
                    transcript = repository.readTranscript(repository.transcriptFile(manifest))
                }

                val prerequisite = TranslatorFactory.missingPrerequisite(manifest.translationProvider, settings)
                if (prerequisite != null) {
                    block(manifestFile, "translation_key_missing", prerequisite)
                    return Result.success()
                }
                val translator = TranslatorFactory.create(manifest.translationProvider, settings)
                try {
                    translator.prepare()
                    var segments = transcript?.segments.orEmpty()
                    for (index in segments.indices) {
                        val segment = segments[index]
                        if (segment.translationState == TranslationState.COMPLETE) continue
                        val english = translator.translate(segment.textFi)
                        segments = segments.toMutableList().also {
                            it[index] = segment.copy(
                                textEn = english,
                                translationState = TranslationState.COMPLETE
                            )
                        }
                        repository.checkpointTranslation(manifestFile, segments)
                    }
                    repository.commitEnglish(manifestFile, segments)
                    ProcessingCoordinator.publish(ProcessingCoordinator.CompletedChunk(manifest.chunkId, segments))
                    return Result.success()
                } finally {
                    translator.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenAIBatch.OutcomeUnknown) {
                repository.updateManifest(manifestFile) {
                    it.copy(
                        processingStage = ProcessingStage.OUTCOME_UNKNOWN,
                        attemptId = null,
                        errorCode = "openai_outcome_unknown",
                        prerequisite = "confirm_retranscription"
                    )
                }
                return Result.success()
            } catch (e: OpenAIBatch.PermanentFailure) {
                block(manifestFile, e.errorCode, "review_processing_settings")
                return Result.success()
            } catch (e: OpenAIBatch.TransientFailure) {
                return retryOrBlock(manifestFile, e.errorCode)
            } catch (_: Throwable) {
                return retryOrBlock(manifestFile, "processing_failed")
            }
        }
    }

    private fun retryOrBlock(file: File, code: String): Result {
        return if (runAttemptCount >= MAX_AUTOMATIC_RETRIES) {
            block(file, code, "manual_retry")
            Result.success()
        } else {
            retryLater(file, code)
            Result.retry()
        }
    }

    private fun retryLater(file: File, code: String) {
        repository.updateManifest(file) {
            it.copy(
                processingStage = ProcessingStage.RETRY_PENDING,
                attemptId = null,
                errorCode = code,
                nextRetryEpochMs = System.currentTimeMillis() + 30_000L * (1L shl runAttemptCount.coerceAtMost(5))
            )
        }
    }

    private fun block(file: File, code: String, prerequisite: String) {
        repository.updateManifest(file) {
            it.copy(
                processingStage = ProcessingStage.BLOCKED,
                attemptId = null,
                errorCode = code,
                prerequisite = prerequisite,
                nextRetryEpochMs = null
            )
        }
    }

    companion object {
        const val KEY_MANIFEST_PATH = "manifest_path"
        private const val MAX_AUTOMATIC_RETRIES = 5
    }
}
