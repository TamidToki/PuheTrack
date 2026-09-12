package com.puhetrack.app.audio

import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig

object StoragePolicy {
    private const val SAFETY_RESERVE_BYTES = 64L * 1024 * 1024
    private const val WAV_CHUNK_BYTES = ChunkClock.FRAMES_PER_CHUNK.toLong() * 2 + 44
    private const val LIVE_METADATA_BYTES = 8L * 1024 * 1024

    fun requiredFreeBytes(config: SessionConfig): Long = SAFETY_RESERVE_BYTES + when {
        config.mode == RecordingMode.LIVE -> mp3ChunkBytes(config.bitrateKbps) + LIVE_METADATA_BYTES
        config.batchEngine == BatchEngine.OFFLINE -> WAV_CHUNK_BYTES
        else -> mp3ChunkBytes(config.bitrateKbps)
    }

    fun canStart(config: SessionConfig, availableBytes: Long): Boolean =
        availableBytes >= requiredFreeBytes(config)

    private fun mp3ChunkBytes(bitrateKbps: Int): Long {
        val encoded = bitrateKbps.coerceIn(32, 96).toLong() * 1_000L / 8L *
            ChunkClock.CHUNK_SECONDS
        // Leave room for MP3 framing/metadata variation rather than relying on
        // the ideal CBR calculation alone.
        return encoded * 102L / 100L + 1L * 1024 * 1024
    }
}
