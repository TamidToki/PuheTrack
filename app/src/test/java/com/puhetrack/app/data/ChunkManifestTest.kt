package com.puhetrack.app.data

import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkManifestTest {
    @Test
    fun sessionManifestRoundTripsImmutableConfiguration() {
        val original = SessionManifest(
            sessionId = "session-1",
            baseName = "puhetrack-test",
            displayTitle = "Finnish lesson",
            createdAtEpochMs = 100,
            updatedAtEpochMs = 200,
            lifecycle = SessionLifecycle.PAUSED,
            config = SessionConfig(
                mode = RecordingMode.BATCH,
                batchEngine = BatchEngine.OPENAI,
                translation = "DEEPL",
                bitrateKbps = 128,
                createdAtEpochMs = 90
            ),
            appVersion = "test"
        )

        assertEquals(original, SessionManifest.fromJson(original.toJson()))
    }

    @Test
    fun canonicalTranscriptRoundTripsPartialTranslationState() {
        val original = CanonicalTranscript(
            sessionId = "session-1",
            chunkId = "chunk-1",
            segments = listOf(
                CanonicalSegment(
                    segmentId = "segment-1",
                    startMs = 12,
                    endMs = 450,
                    textFi = "Hyvää huomenta",
                    textEn = null,
                    translationState = TranslationState.PENDING,
                    timingProvenance = "provider_segment",
                    chunkId = "chunk-1"
                )
            ),
            updatedAtEpochMs = 500
        )

        assertEquals(original, CanonicalTranscript.fromJson(original.toJson()))
    }

    @Test
    fun interruptedOfflineInferenceCanRetryButUnknownCloudOutcomeBlocks() {
        assertEquals(
            ProcessingStage.PENDING_TRANSCRIPTION,
            stageAfterInterruptedProcessing("SHERPA", hasFinnish = false)
        )
        assertEquals(
            ProcessingStage.OUTCOME_UNKNOWN,
            stageAfterInterruptedProcessing("OPENAI", hasFinnish = false)
        )
        assertEquals(
            ProcessingStage.FINNISH_COMMITTED,
            stageAfterInterruptedProcessing("OPENAI", hasFinnish = true)
        )
    }

    @Test
    fun liveAudioClosesCompleteWithoutEnteringBatchTranscription() {
        assertEquals(ProcessingStage.COMPLETE, stageAfterAudioClose("ANDROID_LIVE"))
        assertEquals(ProcessingStage.PENDING_TRANSCRIPTION, stageAfterAudioClose("SHERPA"))
        assertEquals(ProcessingStage.PENDING_TRANSCRIPTION, stageAfterAudioClose("OPENAI"))
    }

    @Test
    fun cloudOutcomeAlwaysRequiresExplicitPaidRepeatConfirmation() {
        val base = ChunkManifest(
            chunkId = "chunk-1",
            sessionId = "session-1",
            sequence = 1,
            baseName = "test",
            audioFileName = "test.mp3",
            audioFormat = "MP3",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            transcriber = "OPENAI",
            transcriberModel = "whisper-1",
            translationProvider = "MLKIT",
            processingConfigHash = "hash"
        )

        assertEquals(
            true,
            requiresPaidRetranscriptionConfirmation(base.copy(processingStage = ProcessingStage.OUTCOME_UNKNOWN))
        )
        assertEquals(
            true,
            requiresPaidRetranscriptionConfirmation(
                base.copy(processingStage = ProcessingStage.BLOCKED, prerequisite = "confirm_retranscription")
            )
        )
        assertEquals(
            false,
            requiresPaidRetranscriptionConfirmation(base.copy(processingStage = ProcessingStage.RETRY_PENDING))
        )
    }

    @Test
    fun latePauseOrResumeCannotDowngradeATerminalSession() {
        assertEquals(
            SessionLifecycle.STOPPED,
            resolvedSessionLifecycle(SessionLifecycle.STOPPED, SessionLifecycle.PAUSED)
        )
        assertEquals(
            SessionLifecycle.FAILED,
            resolvedSessionLifecycle(SessionLifecycle.FAILED, SessionLifecycle.ACTIVE)
        )
        assertEquals(
            SessionLifecycle.PAUSED,
            resolvedSessionLifecycle(SessionLifecycle.ACTIVE, SessionLifecycle.PAUSED)
        )
    }
}
