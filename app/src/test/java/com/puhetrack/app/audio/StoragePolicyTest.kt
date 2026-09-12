package com.puhetrack.app.audio

import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    @Test
    fun offlineBatchRequiresRoomForAWholeWavChunkAndReserve() {
        val config = SessionConfig(RecordingMode.BATCH, BatchEngine.OFFLINE, "MLKIT", 96)
        val required = StoragePolicy.requiredFreeBytes(config)

        assertTrue(required > ChunkClock.FRAMES_PER_CHUNK.toLong() * 2)
        assertTrue(StoragePolicy.canStart(config, required))
        assertFalse(StoragePolicy.canStart(config, required - 1))
    }

    @Test
    fun recordedLiveReservesSpaceForAFullThirtyMinuteMp3AndMetadata() {
        val config = SessionConfig(RecordingMode.LIVE, BatchEngine.OFFLINE, "MLKIT", 96)
        val reserve = 64L * 1024 * 1024
        val idealMp3Bytes = 96_000L / 8L * ChunkClock.CHUNK_SECONDS
        assertTrue(StoragePolicy.requiredFreeBytes(config) > reserve + idealMp3Bytes)
    }

    @Test
    fun cloudThirtyMinuteMp3StaysBelowApplicationUploadLimit() {
        val config = SessionConfig(RecordingMode.BATCH, BatchEngine.OPENAI, "MLKIT", 96)
        val reserve = 64L * 1024 * 1024
        val chunkBudget = StoragePolicy.requiredFreeBytes(config) - reserve

        assertTrue(chunkBudget > 21_600_000L)
        assertTrue(chunkBudget < 24_000_000L)
    }
}
