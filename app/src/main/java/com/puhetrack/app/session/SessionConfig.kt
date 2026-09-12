package com.puhetrack.app.session

import com.puhetrack.app.settings.SettingsStore

enum class RecordingMode { LIVE, BATCH }
enum class BatchEngine { OFFLINE, OPENAI }
enum class SessionState { IDLE, STARTING, RECORDING, PAUSING, PAUSED, STOPPING, SAVED, FAILED }

/** Immutable choices captured when a session starts. */
data class SessionConfig(
    val mode: RecordingMode,
    val batchEngine: BatchEngine,
    val translation: String,
    val bitrateKbps: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun from(store: SettingsStore): SessionConfig = SessionConfig(
            mode = runCatching { RecordingMode.valueOf(store.recordingMode) }
                .getOrDefault(RecordingMode.BATCH),
            batchEngine = runCatching { BatchEngine.valueOf(store.batchEngine) }
                .getOrDefault(BatchEngine.OFFLINE),
            translation = store.translation.name,
            bitrateKbps = store.effectiveBitrateKbps()
        )
    }
}
