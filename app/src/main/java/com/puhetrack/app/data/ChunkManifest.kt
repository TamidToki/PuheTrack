package com.puhetrack.app.data

import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig
import org.json.JSONArray
import org.json.JSONObject

enum class SessionLifecycle { ACTIVE, PAUSED, STOPPED, INTERRUPTED, FAILED }

/** Terminal lifecycle records must never be downgraded by a late pause/resume write. */
fun resolvedSessionLifecycle(current: SessionLifecycle, requested: SessionLifecycle): SessionLifecycle {
    val terminal = setOf(SessionLifecycle.STOPPED, SessionLifecycle.INTERRUPTED, SessionLifecycle.FAILED)
    return if (current in terminal && requested !in terminal) current else requested
}
enum class ChunkLifecycle { OPEN, CLOSED, INTERRUPTED, FAILED }
enum class IntegrityState { OPEN, VERIFIED, RECOVERED_PARTIAL, UNKNOWN, INVALID }
enum class ProcessingStage {
    CAPTURING,
    PENDING_TRANSCRIPTION,
    TRANSCRIBING,
    FINNISH_COMMITTED,
    TRANSLATING,
    COMPLETE,
    BLOCKED,
    RETRY_PENDING,
    OUTCOME_UNKNOWN
}

/** True when retrying may repeat a cloud request whose billing outcome is unknown. */
fun requiresPaidRetranscriptionConfirmation(manifest: ChunkManifest): Boolean =
    manifest.processingStage == ProcessingStage.OUTCOME_UNKNOWN ||
        manifest.prerequisite == "confirm_retranscription" ||
        manifest.errorCode == "openai_outcome_unknown"

data class SessionManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val baseName: String,
    val displayTitle: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lifecycle: SessionLifecycle,
    val config: SessionConfig,
    val appVersion: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("sessionId", sessionId)
        .put("baseName", baseName)
        .put("displayTitle", displayTitle)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("lifecycle", lifecycle.name)
        .put("appVersion", appVersion)
        .put("config", config.toJson())

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(o: JSONObject): SessionManifest = SessionManifest(
            schemaVersion = o.optInt("schemaVersion", 1),
            sessionId = o.getString("sessionId"),
            baseName = o.getString("baseName"),
            displayTitle = o.optString("displayTitle", o.getString("baseName")),
            createdAtEpochMs = o.getLong("createdAtEpochMs"),
            updatedAtEpochMs = o.optLong("updatedAtEpochMs", o.getLong("createdAtEpochMs")),
            lifecycle = enumValueOr(o.optString("lifecycle"), SessionLifecycle.INTERRUPTED),
            config = sessionConfigFromJson(o.getJSONObject("config")),
            appVersion = o.optString("appVersion", "unknown")
        )
    }
}

data class ChunkManifest(
    val schemaVersion: Int = SCHEMA_VERSION,
    val chunkId: String,
    val sessionId: String,
    val sequence: Int,
    val baseName: String,
    val audioFileName: String,
    val audioFormat: String,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val capturedFrames: Long = 0,
    val durableBytes: Long = 0,
    val sessionActiveOffsetMs: Long = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null,
    val lifecycle: ChunkLifecycle = ChunkLifecycle.OPEN,
    val integrity: IntegrityState = IntegrityState.OPEN,
    val processingStage: ProcessingStage = ProcessingStage.CAPTURING,
    val closeReason: String? = null,
    val transcriber: String,
    val transcriberModel: String,
    val translationProvider: String,
    val attemptId: String? = null,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val prerequisite: String? = null,
    val nextRetryEpochMs: Long? = null,
    val audioSha256: String? = null,
    val processingConfigHash: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("chunkId", chunkId)
        .put("sessionId", sessionId)
        .put("sequence", sequence)
        .put("baseName", baseName)
        .put("audioFileName", audioFileName)
        .put("audioFormat", audioFormat)
        .put("sampleRate", sampleRate)
        .put("channels", channels)
        .put("capturedFrames", capturedFrames)
        .put("durableBytes", durableBytes)
        .put("sessionActiveOffsetMs", sessionActiveOffsetMs)
        .put("createdAtEpochMs", createdAtEpochMs)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .putNullable("closedAtEpochMs", closedAtEpochMs)
        .put("lifecycle", lifecycle.name)
        .put("integrity", integrity.name)
        .put("processingStage", processingStage.name)
        .putNullable("closeReason", closeReason)
        .put("transcriber", transcriber)
        .put("transcriberModel", transcriberModel)
        .put("translationProvider", translationProvider)
        .putNullable("attemptId", attemptId)
        .put("retryCount", retryCount)
        .putNullable("errorCode", errorCode)
        .putNullable("prerequisite", prerequisite)
        .putNullable("nextRetryEpochMs", nextRetryEpochMs)
        .putNullable("audioSha256", audioSha256)
        .put("processingConfigHash", processingConfigHash)

    companion object {
        const val SCHEMA_VERSION = 1

        fun fromJson(o: JSONObject): ChunkManifest = ChunkManifest(
            schemaVersion = o.optInt("schemaVersion", 1),
            chunkId = o.getString("chunkId"),
            sessionId = o.getString("sessionId"),
            sequence = o.getInt("sequence"),
            baseName = o.getString("baseName"),
            audioFileName = o.getString("audioFileName"),
            audioFormat = o.getString("audioFormat"),
            sampleRate = o.optInt("sampleRate", 16_000),
            channels = o.optInt("channels", 1),
            capturedFrames = o.optLong("capturedFrames", 0),
            durableBytes = o.optLong("durableBytes", 0),
            sessionActiveOffsetMs = o.optLong("sessionActiveOffsetMs", 0),
            createdAtEpochMs = o.getLong("createdAtEpochMs"),
            updatedAtEpochMs = o.optLong("updatedAtEpochMs", o.getLong("createdAtEpochMs")),
            closedAtEpochMs = o.optNullableLong("closedAtEpochMs"),
            lifecycle = enumValueOr(o.optString("lifecycle"), ChunkLifecycle.INTERRUPTED),
            integrity = enumValueOr(o.optString("integrity"), IntegrityState.UNKNOWN),
            processingStage = enumValueOr(o.optString("processingStage"), ProcessingStage.BLOCKED),
            closeReason = o.optNullableString("closeReason"),
            transcriber = o.optString("transcriber", "UNKNOWN"),
            transcriberModel = o.optString("transcriberModel", "unknown"),
            translationProvider = o.optString("translationProvider", "MLKIT"),
            attemptId = o.optNullableString("attemptId"),
            retryCount = o.optInt("retryCount", 0),
            errorCode = o.optNullableString("errorCode"),
            prerequisite = o.optNullableString("prerequisite"),
            nextRetryEpochMs = o.optNullableLong("nextRetryEpochMs"),
            audioSha256 = o.optNullableString("audioSha256"),
            processingConfigHash = o.optString("processingConfigHash", "legacy")
        )
    }
}

enum class TranslationState { PENDING, COMPLETE, UNAVAILABLE }

data class CanonicalSegment(
    val segmentId: String,
    val startMs: Long,
    val endMs: Long,
    val textFi: String,
    val textEn: String? = null,
    val translationState: TranslationState = TranslationState.PENDING,
    val timingProvenance: String,
    val chunkId: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("segmentId", segmentId)
        .put("startMs", startMs)
        .put("endMs", endMs)
        .put("textFi", textFi)
        .putNullable("textEn", textEn)
        .put("translationState", translationState.name)
        .put("timingProvenance", timingProvenance)
        .putNullable("chunkId", chunkId)

    companion object {
        fun fromJson(o: JSONObject): CanonicalSegment = CanonicalSegment(
            segmentId = o.getString("segmentId"),
            startMs = o.optLong("startMs", 0),
            endMs = o.optLong("endMs", o.optLong("startMs", 0)),
            textFi = o.optString("textFi"),
            textEn = o.optNullableString("textEn"),
            translationState = enumValueOr(o.optString("translationState"), TranslationState.PENDING),
            timingProvenance = o.optString("timingProvenance", "unknown"),
            chunkId = o.optNullableString("chunkId")
        )
    }
}

data class CanonicalTranscript(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val chunkId: String? = null,
    val segments: List<CanonicalSegment>,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("sessionId", sessionId)
        .putNullable("chunkId", chunkId)
        .put("updatedAtEpochMs", updatedAtEpochMs)
        .put("segments", JSONArray().apply { segments.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): CanonicalTranscript {
            val arr = o.optJSONArray("segments") ?: JSONArray()
            return CanonicalTranscript(
                schemaVersion = o.optInt("schemaVersion", 1),
                sessionId = o.getString("sessionId"),
                chunkId = o.optNullableString("chunkId"),
                segments = List(arr.length()) { CanonicalSegment.fromJson(arr.getJSONObject(it)) },
                updatedAtEpochMs = o.optLong("updatedAtEpochMs", 0)
            )
        }
    }
}

private fun SessionConfig.toJson(): JSONObject = JSONObject()
    .put("mode", mode.name)
    .put("batchEngine", batchEngine.name)
    .put("translation", translation)
    .put("bitrateKbps", bitrateKbps)
    .put("createdAtEpochMs", createdAtEpochMs)

private fun sessionConfigFromJson(o: JSONObject): SessionConfig = SessionConfig(
    mode = enumValueOr(o.optString("mode"), RecordingMode.BATCH),
    batchEngine = enumValueOr(o.optString("batchEngine"), BatchEngine.OFFLINE),
    translation = o.optString("translation", "MLKIT"),
    bitrateKbps = o.optInt("bitrateKbps", 96),
    createdAtEpochMs = o.optLong("createdAtEpochMs", 0)
)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
