package com.puhetrack.app.processing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.puhetrack.app.data.CanonicalSegment
import com.puhetrack.app.data.ProcessingStage
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.data.TranslationState
import com.puhetrack.app.data.requiresPaidRetranscriptionConfirmation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.TimeUnit

object ProcessingCoordinator {
    data class CompletedChunk(val chunkId: String, val segments: List<CanonicalSegment>)

    private val mutableEvents = MutableSharedFlow<CompletedChunk>(extraBufferCapacity = 32)
    val events = mutableEvents.asSharedFlow()
    data class CompletedLiveSegment(val sessionId: String, val segment: CanonicalSegment)
    private val mutableLiveEvents = MutableSharedFlow<CompletedLiveSegment>(extraBufferCapacity = 32)
    val liveEvents = mutableLiveEvents.asSharedFlow()

    fun enqueue(context: Context, manifestFile: File, replace: Boolean = false) {
        val repository = RecordingRepository(context)
        val manifest = runCatching { repository.readManifest(manifestFile) }.getOrNull() ?: return
        val needsNetwork = manifest.transcriber == "OPENAI" || manifest.translationProvider != "MLKIT"
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<ChunkProcessingWorker>()
            .setInputData(Data.Builder().putString(ChunkProcessingWorker.KEY_MANIFEST_PATH, manifestFile.absolutePath).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("puhetrack-processing")
            .addTag("puhetrack-chunk-${manifest.chunkId}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "puhetrack-chunk-${manifest.chunkId}",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun reconcileAndEnqueue(context: Context) {
        val repository = RecordingRepository(context)
        repository.processableManifests().forEach { enqueue(context, it) }
        repository.pendingLiveTranslations().forEach { (session, segment) ->
            enqueueLiveTranslation(context, session, segment.segmentId)
        }
    }

    fun enqueueLiveTranslation(
        context: Context,
        session: SessionManifest,
        segmentId: String,
        replace: Boolean = false
    ) {
        val needsNetwork = session.config.translation != "MLKIT"
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<LiveTranslationWorker>()
            .setInputData(
                Data.Builder()
                    .putString(LiveTranslationWorker.KEY_SESSION_ID, session.sessionId)
                    .putString(LiveTranslationWorker.KEY_SEGMENT_ID, segmentId)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("puhetrack-processing")
            .addTag("puhetrack-live-$segmentId")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "puhetrack-live-$segmentId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun retryLiveTranslation(context: Context, session: SessionManifest, segmentId: String): Boolean {
        val repository = RecordingRepository(context)
        val changed = repository.updateLiveEnglish(session, segmentId, null, TranslationState.PENDING) ?: return false
        enqueueLiveTranslation(context, session, changed.segmentId, replace = true)
        return true
    }

    fun retryTranslation(context: Context, chunkId: String): Boolean {
        val repository = RecordingRepository(context)
        val file = repository.findManifest(chunkId) ?: return false
        val manifest = repository.readManifest(file)
        val transcript = repository.readTranscript(repository.transcriptFile(manifest)) ?: return false
        if (transcript.segments.none { it.textFi.isNotBlank() }) return false
        repository.updateManifest(file) {
            it.copy(
                processingStage = ProcessingStage.FINNISH_COMMITTED,
                errorCode = null,
                prerequisite = null,
                nextRetryEpochMs = null
            )
        }
        enqueue(context, file, replace = true)
        return true
    }

    /**
     * Manual recovery for a blocked chunk. A lost OpenAI response is never retried
     * unless the caller presents a separate, explicit confirmation action.
     */
    fun manualRetry(context: Context, chunkId: String, confirmOutcomeUnknown: Boolean): Boolean {
        val repository = RecordingRepository(context)
        val file = repository.findManifest(chunkId) ?: return false
        val manifest = repository.readManifest(file)
        if (requiresPaidRetranscriptionConfirmation(manifest) && !confirmOutcomeUnknown) return false
        val hasFinnish = repository.readTranscript(repository.transcriptFile(manifest))
            ?.segments?.any { it.textFi.isNotBlank() } == true
        repository.updateManifest(file) {
            it.copy(
                processingStage = if (hasFinnish) ProcessingStage.FINNISH_COMMITTED else ProcessingStage.PENDING_TRANSCRIPTION,
                attemptId = null,
                errorCode = null,
                prerequisite = null,
                nextRetryEpochMs = null
            )
        }
        enqueue(context, file, replace = true)
        return true
    }

    internal fun publish(event: CompletedChunk) {
        mutableEvents.tryEmit(event)
    }

    internal fun publish(event: CompletedLiveSegment) {
        mutableLiveEvents.tryEmit(event)
    }
}
