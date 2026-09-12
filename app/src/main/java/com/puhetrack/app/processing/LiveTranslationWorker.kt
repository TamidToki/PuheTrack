package com.puhetrack.app.processing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.TranslationState
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.translate.TranslatorFactory
import kotlinx.coroutines.CancellationException

class LiveTranslationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val segmentId = inputData.getString(KEY_SEGMENT_ID) ?: return Result.failure()
        val repository = RecordingRepository(applicationContext)
        val session = repository.findSession(sessionId) ?: return Result.success()
        val transcriptFile = repository.sessionTranscriptFile(session)
        val lease = runCatching { repository.acquireFileLease(transcriptFile, "live_translation") }.getOrNull()
            ?: return Result.retry()
        try {
            val segment = repository.readTranscript(transcriptFile)?.segments
                ?.firstOrNull { it.segmentId == segmentId }
                ?: return Result.success()
            if (segment.translationState == TranslationState.COMPLETE) return Result.success()

            val settings = SettingsStore(applicationContext)
            if (TranslatorFactory.missingPrerequisite(session.config.translation, settings) != null) {
                val unavailable = repository.updateLiveEnglish(
                    session,
                    segmentId,
                    null,
                    TranslationState.UNAVAILABLE
                )
                unavailable?.let {
                    ProcessingCoordinator.publish(ProcessingCoordinator.CompletedLiveSegment(sessionId, it))
                }
                return Result.success()
            }

            val translator = TranslatorFactory.create(session.config.translation, settings)
            return try {
                translator.prepare()
                val english = translator.translate(segment.textFi)
                val completed = repository.updateLiveEnglish(
                    session,
                    segmentId,
                    english,
                    TranslationState.COMPLETE
                ) ?: return Result.success()
                ProcessingCoordinator.publish(ProcessingCoordinator.CompletedLiveSegment(sessionId, completed))
                Result.success()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (runAttemptCount >= MAX_RETRIES) {
                    val unavailable = repository.updateLiveEnglish(
                        session,
                        segmentId,
                        null,
                        TranslationState.UNAVAILABLE
                    )
                    unavailable?.let {
                        ProcessingCoordinator.publish(ProcessingCoordinator.CompletedLiveSegment(sessionId, it))
                    }
                    Result.success()
                } else {
                    Result.retry()
                }
            } finally {
                translator.close()
            }
        } finally {
            lease.close()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SEGMENT_ID = "segment_id"
        private const val MAX_RETRIES = 5
    }
}
