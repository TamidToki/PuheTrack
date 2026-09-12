package com.puhetrack.app.audio

/**
 * Counts captured PCM frames rather than wall time. Pauses simply submit no
 * frames, so they never advance a chunk boundary.
 */
class ChunkClock(
    val sampleRate: Int = SAMPLE_RATE,
    val chunkSeconds: Int = CHUNK_SECONDS
) {
    data class Slice(
        val sourceFrameOffset: Int,
        val frameCount: Int,
        val chunkSequence: Int,
        val closesChunk: Boolean
    )

    val framesPerChunk: Long = sampleRate.toLong() * chunkSeconds
    var framesInChunk: Long = 0L
        private set
    var chunkSequence: Int = 1
        private set

    fun split(frameCount: Int): List<Slice> {
        require(frameCount >= 0) { "frameCount must be non-negative" }
        if (frameCount == 0) return emptyList()

        val slices = ArrayList<Slice>(2)
        var remaining = frameCount
        var sourceOffset = 0
        while (remaining > 0) {
            val capacity = framesPerChunk - framesInChunk
            val take = minOf(remaining.toLong(), capacity).toInt()
            val closes = framesInChunk + take == framesPerChunk
            slices += Slice(sourceOffset, take, chunkSequence, closes)
            framesInChunk += take
            sourceOffset += take
            remaining -= take
            if (closes) {
                framesInChunk = 0L
                chunkSequence++
            }
        }
        return slices
    }

    fun activeMillis(): Long = framesInChunk * 1_000L / sampleRate

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SECONDS = 30 * 60
        const val FRAMES_PER_CHUNK = SAMPLE_RATE * CHUNK_SECONDS
    }
}
