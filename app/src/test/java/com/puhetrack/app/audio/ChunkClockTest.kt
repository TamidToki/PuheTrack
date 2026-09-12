package com.puhetrack.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkClockTest {
    @Test
    fun fullChunkClosesAtExactlyTwentyEightMillionEightHundredThousandFrames() {
        val clock = ChunkClock()
        val slices = clock.split(ChunkClock.FRAMES_PER_CHUNK)

        assertEquals(1, slices.size)
        assertEquals(ChunkClock.FRAMES_PER_CHUNK, slices.single().frameCount)
        assertTrue(slices.single().closesChunk)
        assertEquals(0L, clock.framesInChunk)
        assertEquals(2, clock.chunkSequence)
        assertEquals(28_800_000, ChunkClock.FRAMES_PER_CHUNK)
    }

    @Test
    fun bufferCrossingBoundaryIsSplitWithoutLossOrDuplication() {
        val clock = ChunkClock(sampleRate = 10, chunkSeconds = 2)
        clock.split(17)

        val slices = clock.split(8)

        assertEquals(listOf(3, 5), slices.map { it.frameCount })
        assertEquals(listOf(0, 3), slices.map { it.sourceFrameOffset })
        assertEquals(listOf(1, 2), slices.map { it.chunkSequence })
        assertEquals(listOf(true, false), slices.map { it.closesChunk })
        assertEquals(8, slices.sumOf { it.frameCount })
        assertEquals(5L, clock.framesInChunk)
    }

    @Test
    fun noFramesDuringPauseMeansNoClockProgress() {
        val clock = ChunkClock(sampleRate = 10, chunkSeconds = 20)
        clock.split(50)
        clock.split(0)
        assertEquals(50L, clock.framesInChunk)
        assertEquals(5_000L, clock.activeMillis())
    }

    @Test
    fun oneLargeReadCanCrossSeveralBoundariesWithoutDroppingFrames() {
        val clock = ChunkClock(sampleRate = 2, chunkSeconds = 3)
        val slices = clock.split(14)

        assertEquals(listOf(6, 6, 2), slices.map { it.frameCount })
        assertEquals(listOf(0, 6, 12), slices.map { it.sourceFrameOffset })
        assertEquals(listOf(1, 2, 3), slices.map { it.chunkSequence })
        assertEquals(listOf(true, true, false), slices.map { it.closesChunk })
        assertEquals(14, slices.sumOf { it.frameCount })
        assertEquals(2L, clock.framesInChunk)
        assertEquals(3, clock.chunkSequence)
    }
}
