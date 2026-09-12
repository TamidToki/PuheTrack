package com.puhetrack.app.data

import android.content.Context
import com.puhetrack.app.BuildConfig
import com.puhetrack.app.audio.WavWriter
import com.puhetrack.app.audio.Mp3Recovery
import com.puhetrack.app.session.BatchEngine
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.Closeable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only writer for session, chunk and canonical transcript metadata.
 * Every metadata commit is synced and replaced in the same directory.
 */
class RecordingRepository(context: Context) {
    val root: File = context.getExternalFilesDir("recordings")
        ?: File(context.filesDir, "recordings")

    init { root.mkdirs() }

    data class ChunkHandle(
        val manifestFile: File,
        val partFile: File,
        val finalFile: File,
        val manifest: ChunkManifest
    )

    data class RecoveryReport(
        val recoveredWav: Int,
        val recoveredMp3: Int,
        val retainedInterrupted: Int,
        val resetProcessing: Int,
        val importedLegacy: Int,
        val interruptedSessions: Int
    )

    fun createSession(config: SessionConfig): SessionManifest = synchronized(ioLock) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val base = "puhetrack-${utcStamp(now)}-$id"
        val session = SessionManifest(
            sessionId = id,
            baseName = base,
            displayTitle = "PuheTrack ${displayStamp(now)}",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            lifecycle = SessionLifecycle.ACTIVE,
            config = config,
            appVersion = BuildConfig.VERSION_NAME
        )
        writeSession(session)
        if (config.mode == RecordingMode.LIVE) {
            writeTranscript(
                File(root, "$base.transcript.json"),
                CanonicalTranscript(sessionId = id, segments = emptyList())
            )
        }
        session
    }

    fun updateSession(session: SessionManifest, lifecycle: SessionLifecycle): SessionManifest =
        synchronized(ioLock) {
            val file = File(root, "${session.baseName}.session.json")
            val latest = runCatching { readSession(file) }.getOrElse { session }
            require(latest.sessionId == session.sessionId) { "Session identity changed" }
            val updated = latest.copy(
                lifecycle = resolvedSessionLifecycle(latest.lifecycle, lifecycle),
                updatedAtEpochMs = System.currentTimeMillis()
            )
            writeSession(updated)
            updated
        }

    fun openChunk(session: SessionManifest, sequence: Int, offsetMs: Long): ChunkHandle =
        synchronized(ioLock) {
            val chunkId = UUID.randomUUID().toString()
            val base = "${session.baseName}-c${sequence.toString().padStart(6, '0')}"
            val isLive = session.config.mode == RecordingMode.LIVE
            val isOffline = !isLive && session.config.batchEngine == BatchEngine.OFFLINE
            val ext = if (isOffline) "wav" else "mp3"
            val finalFile = File(root, "$base.$ext")
            val partFile = File(root, "${finalFile.name}.part")
            val manifestFile = File(root, "$base.state.json")
            val transcriber = when {
                isLive -> "ANDROID_LIVE"
                isOffline -> "SHERPA"
                else -> "OPENAI"
            }
            val model = when {
                isLive -> "speech-recognizer-fi-FI"
                isOffline -> OFFLINE_MODEL_REVISION
                else -> "whisper-1"
            }
            val now = System.currentTimeMillis()
            val manifest = ChunkManifest(
                chunkId = chunkId,
                sessionId = session.sessionId,
                sequence = sequence,
                baseName = base,
                audioFileName = finalFile.name,
                audioFormat = ext.uppercase(Locale.US),
                sessionActiveOffsetMs = offsetMs,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                transcriber = transcriber,
                transcriberModel = model,
                translationProvider = session.config.translation,
                processingConfigHash = sha256Text(
                    "$transcriber|$model|${session.config.translation}|${session.config.bitrateKbps}|16000|1"
                )
            )
            writeManifest(manifestFile, manifest)
            ChunkHandle(manifestFile, partFile, finalFile, manifest)
        }

    fun closeChunk(handle: ChunkHandle, frames: Long, reason: String): ChunkManifest =
        synchronized(ioLock) {
            require(frames > 0) { "Cannot close an empty chunk" }
            require(handle.partFile.exists()) { "Open audio file disappeared" }
            if (handle.finalFile.exists() && !handle.finalFile.delete()) {
                throw IOException("Could not replace closed audio")
            }
            if (!handle.partFile.renameTo(handle.finalFile)) {
                copyAndSync(handle.partFile, handle.finalFile)
                if (!handle.partFile.delete()) {
                    throw IOException("Audio closed but temporary file could not be retired")
                }
            }
            val now = System.currentTimeMillis()
            val current = readManifest(handle.manifestFile)
            val updated = current.copy(
                capturedFrames = frames,
                durableBytes = handle.finalFile.length(),
                closedAtEpochMs = now,
                updatedAtEpochMs = now,
                lifecycle = ChunkLifecycle.CLOSED,
                integrity = IntegrityState.VERIFIED,
                // Live text is generated from the same PCM stream while this MP3
                // is captured, so the audio chunk itself has no batch work to run.
                processingStage = stageAfterAudioClose(current.transcriber),
                closeReason = reason,
                errorCode = null,
                prerequisite = null,
                // Hashing a 30-minute file here would stall the capture thread
                // before it can open the next chunk. The worker checkpoints the
                // hash before inference instead.
                audioSha256 = null
            )
            writeManifest(handle.manifestFile, updated)
            updated
        }

    fun markOpenChunkInterrupted(handle: ChunkHandle, frames: Long, errorCode: String): ChunkManifest =
        synchronized(ioLock) {
            val current = readManifest(handle.manifestFile)
            val updated = current.copy(
                capturedFrames = frames,
                durableBytes = handle.partFile.takeIf { it.exists() }?.length() ?: 0,
                updatedAtEpochMs = System.currentTimeMillis(),
                lifecycle = ChunkLifecycle.INTERRUPTED,
                integrity = IntegrityState.UNKNOWN,
                processingStage = ProcessingStage.BLOCKED,
                closeReason = "capture_interrupted",
                errorCode = errorCode,
                prerequisite = "review_recovered_audio"
            )
            writeManifest(handle.manifestFile, updated)
            updated
        }

    fun readManifest(file: File): ChunkManifest = readJsonWithBackup(file) { ChunkManifest.fromJson(it) }

    fun readSession(file: File): SessionManifest = readJsonWithBackup(file) { SessionManifest.fromJson(it) }

    fun sessionFiles(): List<File> = root.listFiles { f -> f.name.endsWith(".session.json") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    fun findSession(sessionId: String): SessionManifest? = sessionFiles().firstNotNullOfOrNull { file ->
        runCatching { readSession(file) }.getOrNull()?.takeIf { it.sessionId == sessionId }
    }

    fun pendingLiveTranslations(): List<Pair<SessionManifest, CanonicalSegment>> = buildList {
        sessionFiles().forEach { file ->
            val session = runCatching { readSession(file) }.getOrNull() ?: return@forEach
            if (session.config.mode != RecordingMode.LIVE) return@forEach
            val transcript = readTranscript(sessionTranscriptFile(session)) ?: return@forEach
            transcript.segments.filter { it.translationState == TranslationState.PENDING }
                .forEach { add(session to it) }
        }
    }

    fun liveSessions(): List<SessionManifest> = sessionFiles().mapNotNull { file ->
        runCatching { readSession(file) }.getOrNull()
            ?.takeIf { it.config.mode == RecordingMode.LIVE }
    }

    fun audioFilesForSession(session: SessionManifest): List<File> =
        root.listFiles { file ->
            file.isFile &&
                file.name.startsWith("${session.baseName}-c") &&
                file.extension.lowercase(Locale.US) in setOf("mp3", "wav")
        }?.sortedBy { it.name }.orEmpty()

    /** Audio shown as its own library item; Live audio is grouped under its session. */
    fun libraryAudioFiles(): List<File> = listAudioFiles(root).filterNot { audio ->
        manifestForAudio(audio)?.second?.transcriber == "ANDROID_LIVE"
    }

    /** Delete one audio chunk and its exact-name sidecars, never another chunk in the session. */
    fun deleteAudioFamily(audio: File): Boolean = deleteFilesAtomically(
        root.listFiles { file ->
            file.isFile && (file.name == audio.name || file.name.startsWith("${audio.nameWithoutExtension}."))
        }?.toList().orEmpty()
    )

    /** Delete a stopped live session, including its transcript and every audio chunk. */
    fun deleteLiveSession(session: SessionManifest): Boolean {
        require(session.config.mode == RecordingMode.LIVE)
        if (session.lifecycle in setOf(SessionLifecycle.ACTIVE, SessionLifecycle.PAUSED)) return false
        return deleteFilesAtomically(
            root.listFiles { file ->
                file.isFile && (
                    file.name.startsWith("${session.baseName}.") ||
                        file.name.startsWith("${session.baseName}-c")
                    )
            }?.toList().orEmpty()
        )
    }

    fun updateManifest(file: File, transform: (ChunkManifest) -> ChunkManifest): ChunkManifest =
        synchronized(ioLock) {
            val updated = transform(readManifest(file)).copy(updatedAtEpochMs = System.currentTimeMillis())
            writeManifest(file, updated)
            updated
        }

    fun findManifest(chunkId: String): File? = root.listFiles { f -> f.name.endsWith(".state.json") }
        ?.firstOrNull { runCatching { readManifest(it).chunkId == chunkId }.getOrDefault(false) }

    fun manifestForAudio(audio: File): Pair<File, ChunkManifest>? {
        val file = File(root, "${audio.nameWithoutExtension}.state.json")
        val manifest = runCatching { readManifest(file) }.getOrNull() ?: return null
        return file to manifest
    }

    fun sessionForAudio(audio: File): SessionManifest? {
        val manifest = manifestForAudio(audio)?.second ?: return null
        return findSession(manifest.sessionId)
    }

    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val title = newTitle.trim()
        require(title.isNotEmpty()) { "Lesson name is empty" }
        require(title.length <= 120) { "Lesson name is too long" }
        val file = sessionFiles().firstOrNull { candidate ->
            runCatching { readSession(candidate).sessionId == sessionId }.getOrDefault(false)
        } ?: return false
        return withFileLease(file, "rename") {
            synchronized(ioLock) {
                val session = readSession(file)
                require(session.lifecycle !in setOf(SessionLifecycle.ACTIVE, SessionLifecycle.PAUSED)) {
                    "Stop the session before renaming it"
                }
                writeSession(
                    session.copy(displayTitle = title, updatedAtEpochMs = System.currentTimeMillis())
                )
                true
            }
        }
    }

    fun processableManifests(): List<File> = root.listFiles { f -> f.name.endsWith(".state.json") }
        ?.filter { file ->
            runCatching {
                readManifest(file).processingStage in setOf(
                    ProcessingStage.PENDING_TRANSCRIPTION,
                    ProcessingStage.FINNISH_COMMITTED,
                    ProcessingStage.RETRY_PENDING
                )
            }.getOrDefault(false)
        }
        ?.sortedBy { runCatching { readManifest(it).createdAtEpochMs }.getOrDefault(Long.MAX_VALUE) }
        ?: emptyList()

    fun transcriptFile(manifest: ChunkManifest): File = File(root, "${manifest.baseName}.transcript.json")

    fun sessionTranscriptFile(session: SessionManifest): File = File(root, "${session.baseName}.transcript.json")

    fun readTranscript(file: File): CanonicalTranscript? = runCatching {
        readJsonWithBackup(file) { CanonicalTranscript.fromJson(it) }
    }.getOrNull()

    fun commitFinnish(manifestFile: File, segments: List<CanonicalSegment>): ChunkManifest =
        synchronized(ioLock) {
            val current = readManifest(manifestFile)
            writeTranscript(
                transcriptFile(current),
                CanonicalTranscript(
                    sessionId = current.sessionId,
                    chunkId = current.chunkId,
                    segments = segments
                )
            )
            val updated = current.copy(
                processingStage = ProcessingStage.FINNISH_COMMITTED,
                attemptId = null,
                errorCode = null,
                prerequisite = null,
                nextRetryEpochMs = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            writeManifest(manifestFile, updated)
            regenerateExports(updated, segments)
            updated
        }

    fun commitEnglish(manifestFile: File, segments: List<CanonicalSegment>): ChunkManifest =
        synchronized(ioLock) {
            val current = readManifest(manifestFile)
            writeTranscript(
                transcriptFile(current),
                CanonicalTranscript(
                    sessionId = current.sessionId,
                    chunkId = current.chunkId,
                    segments = segments
                )
            )
            val updated = current.copy(
                processingStage = ProcessingStage.COMPLETE,
                attemptId = null,
                errorCode = null,
                prerequisite = null,
                nextRetryEpochMs = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            writeManifest(manifestFile, updated)
            regenerateExports(updated, segments)
            updated
        }

    fun checkpointTranslation(manifestFile: File, segments: List<CanonicalSegment>): ChunkManifest =
        synchronized(ioLock) {
            val current = readManifest(manifestFile)
            writeTranscript(
                transcriptFile(current),
                CanonicalTranscript(
                    sessionId = current.sessionId,
                    chunkId = current.chunkId,
                    segments = segments
                )
            )
            val updated = current.copy(
                processingStage = ProcessingStage.TRANSLATING,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            writeManifest(manifestFile, updated)
            regenerateExports(updated, segments)
            updated
        }

    fun appendLiveFinnish(session: SessionManifest, segment: CanonicalSegment) = synchronized(ioLock) {
        val file = sessionTranscriptFile(session)
        val current = readTranscript(file) ?: CanonicalTranscript(sessionId = session.sessionId, segments = emptyList())
        val withoutOld = current.segments.filterNot { it.segmentId == segment.segmentId }
        writeTranscript(file, current.copy(segments = withoutOld + segment, updatedAtEpochMs = System.currentTimeMillis()))
        regenerateLiveExports(session, withoutOld + segment)
    }

    fun updateLiveEnglish(
        session: SessionManifest,
        segmentId: String,
        english: String?,
        state: TranslationState
    ): CanonicalSegment? = synchronized(ioLock) {
        val file = sessionTranscriptFile(session)
        val current = readTranscript(file) ?: return@synchronized null
        var changed: CanonicalSegment? = null
        val updated = current.segments.map {
            if (it.segmentId == segmentId) it.copy(textEn = english, translationState = state)
                .also { replacement -> changed = replacement }
            else it
        }
        writeTranscript(file, current.copy(segments = updated, updatedAtEpochMs = System.currentTimeMillis()))
        regenerateLiveExports(session, updated)
        changed
    }

    fun recover(): RecoveryReport = synchronized(ioLock) {
        var wav = 0
        var mp3 = 0
        var retained = 0
        var reset = 0
        var imported = 0
        var interruptedSessions = 0
        sessionFiles().forEach { sessionFile ->
            runCatching {
                val session = readSession(sessionFile)
                if (session.lifecycle in setOf(SessionLifecycle.ACTIVE, SessionLifecycle.PAUSED)) {
                    writeSession(
                        session.copy(
                            lifecycle = SessionLifecycle.INTERRUPTED,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    interruptedSessions++
                }
            }
        }
        root.listFiles { f -> f.name.endsWith(".state.json") }?.forEach { manifestFile ->
            runCatching {
                var manifest = readManifest(manifestFile)
                val final = File(root, manifest.audioFileName)
                val part = File(root, "${manifest.audioFileName}.part")
                if (manifest.lifecycle == ChunkLifecycle.OPEN || manifest.lifecycle == ChunkLifecycle.INTERRUPTED) {
                    val promotedFrames = if (final.exists() && final.length() > 0) {
                        if (manifest.audioFormat == "WAV") {
                            ((final.length() - 44L).coerceAtLeast(0L) / 2L)
                        } else {
                            Mp3Recovery.inspect(final).sampleFrames
                        }
                    } else 0L
                    if (promotedFrames > 0) {
                        manifest = manifest.copy(
                            capturedFrames = promotedFrames,
                            durableBytes = final.length(),
                            closedAtEpochMs = System.currentTimeMillis(),
                            updatedAtEpochMs = System.currentTimeMillis(),
                            lifecycle = ChunkLifecycle.CLOSED,
                            integrity = IntegrityState.RECOVERED_PARTIAL,
                            processingStage = recoveredAudioStage(manifest),
                            closeReason = "rename_reconciled",
                            errorCode = null,
                            prerequisite = null,
                            audioSha256 = sha256File(final)
                        )
                        writeManifest(manifestFile, manifest)
                        part.delete()
                    } else if (part.exists() && part.length() > 44 && manifest.audioFormat == "WAV") {
                        val recovery = File(root, "${final.name}.recovery")
                        val frames = WavWriter.recoverCopy(part, recovery)
                        if (frames > 0 && promoteRecoveryCopy(recovery, final)) {
                            part.delete()
                            manifest = manifest.copy(
                                capturedFrames = frames,
                                durableBytes = final.length(),
                                closedAtEpochMs = System.currentTimeMillis(),
                                updatedAtEpochMs = System.currentTimeMillis(),
                                lifecycle = ChunkLifecycle.CLOSED,
                                integrity = IntegrityState.RECOVERED_PARTIAL,
                                processingStage = recoveredAudioStage(manifest),
                                closeReason = "process_death_recovery",
                                errorCode = null,
                                prerequisite = null,
                                audioSha256 = sha256File(final)
                            )
                            writeManifest(manifestFile, manifest)
                            wav++
                        } else {
                            recovery.delete()
                            retained++
                        }
                    } else if (part.exists() && part.length() > 4 && manifest.audioFormat == "MP3") {
                        val recovery = File(root, "${final.name}.recovery")
                        val recovered = Mp3Recovery.recoverCopy(part, recovery)
                        if (recovered.encodedFrames > 0 && promoteRecoveryCopy(recovery, final)) {
                            part.delete()
                            manifest = manifest.copy(
                                capturedFrames = recovered.sampleFrames,
                                durableBytes = recovered.durableBytes,
                                closedAtEpochMs = System.currentTimeMillis(),
                                updatedAtEpochMs = System.currentTimeMillis(),
                                lifecycle = ChunkLifecycle.CLOSED,
                                integrity = IntegrityState.RECOVERED_PARTIAL,
                                processingStage = recoveredAudioStage(manifest),
                                closeReason = "process_death_recovery",
                                errorCode = null,
                                prerequisite = null,
                                audioSha256 = sha256File(final)
                            )
                            writeManifest(manifestFile, manifest)
                            mp3++
                        } else {
                            recovery.delete()
                            retained++
                        }
                    } else {
                        retained++
                    }
                }
                if (manifest.processingStage in setOf(ProcessingStage.TRANSCRIBING, ProcessingStage.TRANSLATING)) {
                    val transcript = readTranscript(transcriptFile(manifest))
                    val next = stageAfterInterruptedProcessing(
                        manifest.transcriber,
                        transcript?.segments?.any { it.textFi.isNotBlank() } == true
                    )
                    writeManifest(
                        manifestFile,
                        manifest.copy(
                            processingStage = next,
                            attemptId = null,
                            errorCode = "process_interrupted",
                            prerequisite = if (next == ProcessingStage.OUTCOME_UNKNOWN) {
                                "confirm_retranscription"
                            } else {
                                null
                            },
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    reset++
                }
            }
        }
        root.listFiles { f -> f.name.endsWith(".pending") || f.name.endsWith(".processing") }
            ?.forEach { marker ->
                val audioName = marker.name.removeSuffix(".pending").removeSuffix(".processing")
                val audio = File(root, audioName)
                val base = audio.nameWithoutExtension
                val mf = File(root, "$base.state.json")
                if (audio.exists() && !mf.exists()) {
                    val now = System.currentTimeMillis()
                    val m = ChunkManifest(
                        chunkId = UUID.randomUUID().toString(),
                        sessionId = "legacy-${UUID.randomUUID()}",
                        sequence = 1,
                        baseName = base,
                        audioFileName = audio.name,
                        audioFormat = audio.extension.uppercase(Locale.US),
                        durableBytes = audio.length(),
                        createdAtEpochMs = audio.lastModified().takeIf { it > 0 } ?: now,
                        updatedAtEpochMs = now,
                        closedAtEpochMs = now,
                        lifecycle = ChunkLifecycle.CLOSED,
                        integrity = IntegrityState.UNKNOWN,
                        processingStage = ProcessingStage.BLOCKED,
                        closeReason = "legacy_import",
                        transcriber = "UNKNOWN",
                        transcriberModel = "unknown",
                        translationProvider = "MLKIT",
                        prerequisite = "manual_legacy_processing_consent",
                        processingConfigHash = "legacy"
                    )
                    writeManifest(mf, m)
                    imported++
                }
            }
        RecoveryReport(wav, mp3, retained, reset, imported, interruptedSessions)
    }

    private fun recoveredAudioStage(manifest: ChunkManifest): ProcessingStage =
        stageAfterAudioClose(manifest.transcriber)

    fun <T> withFileLease(file: File, operation: String, block: () -> T): T {
        val key = file.canonicalPath
        check(leases.putIfAbsent(key, operation) == null) { "File is busy: ${leases[key]}" }
        return try { block() } finally { leases.remove(key, operation) }
    }

    class FileLease internal constructor(
        private val key: String,
        private val operation: String
    ) : Closeable {
        override fun close() {
            leases.remove(key, operation)
        }
    }

    fun acquireFileLease(file: File, operation: String): FileLease {
        val key = file.canonicalPath
        check(leases.putIfAbsent(key, operation) == null) { "File is busy: ${leases[key]}" }
        return FileLease(key, operation)
    }

    fun isLeased(file: File): Boolean = runCatching { leases.containsKey(file.canonicalPath) }.getOrDefault(true)

    private fun deleteFilesAtomically(files: List<File>): Boolean {
        if (files.isEmpty()) return true
        val acquired = mutableListOf<String>()
        return synchronized(ioLock) {
            try {
                files.sortedBy { it.canonicalPath }.forEach { file ->
                    val key = file.canonicalPath
                    if (leases.putIfAbsent(key, "delete") != null) return@synchronized false
                    acquired += key
                }
                files.all { !it.exists() || it.delete() }
            } finally {
                acquired.forEach { leases.remove(it, "delete") }
            }
        }
    }

    private fun writeSession(session: SessionManifest) =
        atomicWrite(File(root, "${session.baseName}.session.json"), session.toJson().toString())

    private fun writeManifest(file: File, manifest: ChunkManifest) =
        atomicWrite(file, manifest.toJson().toString())

    private fun writeTranscript(file: File, transcript: CanonicalTranscript) =
        atomicWrite(file, transcript.toJson().toString())

    private fun regenerateExports(manifest: ChunkManifest, segments: List<CanonicalSegment>) {
        val audio = File(root, manifest.audioFileName)
        val legacy = segments.map { Seg(it.textFi, it.textEn.orEmpty(), it.startMs, it.endMs) }
        runCatching { writeSegmentsJson(sidecar(audio, "json"), legacy) }
        runCatching { writeTxt(sidecar(audio, "txt"), legacy) }
        runCatching { writeSrt(sidecar(audio, "srt"), legacy) }
    }

    private fun regenerateLiveExports(session: SessionManifest, segments: List<CanonicalSegment>) {
        val legacy = segments.map { Seg(it.textFi, it.textEn.orEmpty(), it.startMs, it.endMs) }
        runCatching { writeSegmentsJson(File(root, "${session.baseName}.json"), legacy) }
        runCatching { writeTxt(File(root, "${session.baseName}.txt"), legacy) }
        // Live utterance boundaries are recognizer estimates; the session audio is
        // durably archived in separate exact 30-minute MP3 chunks.
        runCatching { writeSrt(File(root, "${session.baseName}.srt"), legacy) }
    }

    private fun <T> readJsonWithBackup(file: File, parse: (JSONObject) -> T): T {
        return runCatching { parse(JSONObject(file.readText())) }.getOrElse { primaryFailure ->
            val backup = File(file.parentFile, "${file.name}.bak")
            if (backup.exists()) parse(JSONObject(backup.readText())) else throw primaryFailure
        }
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val next = File(target.parentFile, "${target.name}.new")
        val backup = File(target.parentFile, "${target.name}.bak")
        FileOutputStream(next, false).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (target.exists()) {
            if (backup.exists()) backup.delete()
            if (!target.renameTo(backup)) {
                next.delete()
                throw IOException("Could not stage metadata replacement")
            }
        }
        if (!next.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            throw IOException("Could not commit metadata")
        }
        // Keep the previous committed generation as a last-known-good copy.
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    /** Preserve an unexpected invalid final while atomically promoting a verified recovery copy. */
    private fun promoteRecoveryCopy(recovery: File, final: File): Boolean {
        var displaced: File? = null
        if (final.exists()) {
            val candidate = File(final.parentFile, "${final.name}.invalid-${System.currentTimeMillis()}")
            if (!final.renameTo(candidate)) return false
            displaced = candidate
        }
        if (recovery.renameTo(final)) return true
        displaced?.renameTo(final)
        return false
    }

    companion object {
        const val OFFLINE_MODEL_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
        private val ioLock = Any()
        private val leases = ConcurrentHashMap<String, String>()

        private fun utcStamp(epochMs: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))

        private fun displayStamp(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

        fun sha256Text(text: String): String = sha256(text.byteInputStream())

        fun sha256File(file: File): String = FileInputStream(file).use { sha256(it) }

        private fun sha256(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun stageAfterInterruptedProcessing(transcriber: String, hasFinnish: Boolean): ProcessingStage = when {
    hasFinnish -> ProcessingStage.FINNISH_COMMITTED
    transcriber == "OPENAI" -> ProcessingStage.OUTCOME_UNKNOWN
    else -> ProcessingStage.PENDING_TRANSCRIPTION
}

internal fun stageAfterAudioClose(transcriber: String): ProcessingStage =
    if (transcriber == "ANDROID_LIVE") ProcessingStage.COMPLETE
    else ProcessingStage.PENDING_TRANSCRIPTION
