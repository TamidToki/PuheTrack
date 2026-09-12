package com.puhetrack.app.models

import android.content.Context
import com.puhetrack.app.data.RecordingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

internal enum class PartialDownloadAction {
    START_FRESH,
    RESUME,
    VERIFY_COMPLETE,
    DISCARD_AND_RESTART
}

internal fun partialDownloadAction(localBytes: Long, expectedBytes: Long): PartialDownloadAction =
    when {
        localBytes == expectedBytes -> PartialDownloadAction.VERIFY_COMPLETE
        localBytes == 0L -> PartialDownloadAction.START_FRESH
        localBytes in 1 until expectedBytes -> PartialDownloadAction.RESUME
        else -> PartialDownloadAction.DISCARD_AND_RESTART
    }

internal fun contentRangeStart(header: String?): Long? =
    header
        ?.trim()
        ?.let { CONTENT_RANGE_PATTERN.matchEntire(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

private val CONTENT_RANGE_PATTERN = Regex(
    pattern = "^bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)$",
    option = RegexOption.IGNORE_CASE
)

object ModelRepository {
    const val DOWNLOAD_BYTES = 670_478_772L
    const val DISPLAY_SIZE = "~671 MB"

    private const val REPOSITORY = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val REVISION = RecordingRepository.OFFLINE_MODEL_REVISION
    private val DIR_NAME = "sherpa-parakeet-fi-${REVISION.take(8)}"

    private data class ModelFile(val name: String, val bytes: Long, val sha256: String)

    private val files = listOf(
        ModelFile(
            "encoder.int8.onnx",
            652_184_281L,
            "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"
        ),
        ModelFile(
            "decoder.int8.onnx",
            11_845_275L,
            "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"
        ),
        ModelFile(
            "joiner.int8.onnx",
            6_355_277L,
            "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"
        ),
        ModelFile(
            "tokens.txt",
            93_939L,
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"
        )
    )

    internal fun expectedSha256(name: String): String? =
        files.firstOrNull { it.name == name }?.sha256

    internal fun expectedBytes(name: String): Long? =
        files.firstOrNull { it.name == name }?.bytes

    sealed interface Status {
        data class Ready(val directory: File) : Status
        data class Missing(val requiredBytes: Long, val availableBytes: Long) : Status
        data class Invalid(val reason: String) : Status
    }

    fun modelDirectory(context: Context): File = File(context.filesDir, DIR_NAME)

    fun status(context: Context): Status {
        val dir = modelDirectory(context)
        val activation = File(dir, "activation.json")
        val available = context.filesDir.usableSpace
        if (!activation.exists()) return Status.Missing(files.sumOf { it.bytes }, available)
        return runCatching {
            val json = JSONObject(activation.readText())
            require(json.optString("revision") == REVISION) { "model revision mismatch" }
            files.forEach { spec ->
                val f = File(dir, spec.name)
                require(f.isFile && f.length() == spec.bytes) { "${spec.name} is missing or truncated" }
                require(json.optString(spec.name) == spec.sha256) { "${spec.name} was not verified" }
            }
            Status.Ready(dir)
        }.getOrElse { Status.Invalid(it.message ?: "invalid model installation") }
    }

    suspend fun download(
        context: Context,
        allowMetered: Boolean,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val total = files.sumOf { it.bytes }
        val dir = modelDirectory(context)
        dir.mkdirs()
        val remaining = files.sumOf { spec ->
            val partial = File(dir, "${spec.name}.part")
            val final = File(dir, spec.name)
            when {
                final.length() == spec.bytes -> 0L
                partial.length() in 1 until spec.bytes -> spec.bytes - partial.length()
                else -> spec.bytes
            }
        }
        require(context.filesDir.usableSpace > remaining + 128L * 1024 * 1024) {
            "Not enough free storage for the offline model"
        }
        if (!allowMetered) {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            require(caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) {
                "Connect to an unmetered network or explicitly allow mobile data"
            }
        }

        var verifiedBytes = 0L
        progress(0L, total)

        files.forEach { spec ->
            coroutineContext.ensureActive()
            val final = File(dir, spec.name)
            if (final.length() == spec.bytes && sha256(final) == spec.sha256) {
                verifiedBytes += final.length()
                progress(verifiedBytes, total)
                return@forEach
            }
            if (final.exists()) final.delete()
            val partial = File(dir, "${spec.name}.part")
            downloadOne(spec, partial) { bytesForFile ->
                progress((verifiedBytes + bytesForFile).coerceAtMost(total), total)
            }
            if (partial.length() == spec.bytes && sha256(partial) != spec.sha256) {
                // A complete-looking partial may be stale or corrupt. Retry once from
                // zero rather than trapping every future attempt at the same checksum.
                partial.delete()
                progress(verifiedBytes, total)
                downloadOne(spec, partial) { bytesForFile ->
                    progress((verifiedBytes + bytesForFile).coerceAtMost(total), total)
                }
            }
            require(partial.length() == spec.bytes) { "${spec.name} download is incomplete" }
            if (sha256(partial) != spec.sha256) {
                partial.delete()
                throw IOException("${spec.name} checksum failed; its temporary file was removed")
            }
            if (!partial.renameTo(final)) throw IOException("Could not activate ${spec.name}")
            verifiedBytes += spec.bytes
            progress(verifiedBytes, total)
        }

        val activation = JSONObject().put("revision", REVISION)
        files.forEach { activation.put(it.name, it.sha256) }
        val marker = File(dir, "activation.json.part")
        FileOutputStream(marker, false).use { out ->
            out.write(activation.toString().toByteArray())
            out.fd.sync()
        }
        val committed = File(dir, "activation.json")
        if (committed.exists()) committed.delete()
        if (!marker.renameTo(committed)) throw IOException("Could not commit model installation")
        dir
    }

    fun removeIncomplete(context: Context): Int {
        val dir = modelDirectory(context)
        var removed = 0
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach {
            if (it.delete()) removed++
        }
        return removed
    }

    fun removeInvalidInstallation(context: Context): Int {
        val dir = modelDirectory(context)
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) removed++
        }
        return removed
    }

    private suspend fun downloadOne(spec: ModelFile, partial: File, progress: (Long) -> Unit) {
        var have = partial.length()
        when (partialDownloadAction(have, spec.bytes)) {
            PartialDownloadAction.VERIFY_COMPLETE -> {
                // Never request bytes=<file-size>-. A standards-compliant host returns
                // 416 for that empty range; the caller will checksum this file locally.
                progress(have)
                return
            }
            PartialDownloadAction.DISCARD_AND_RESTART -> {
                partial.delete()
                have = 0L
            }
            PartialDownloadAction.START_FRESH,
            PartialDownloadAction.RESUME -> Unit
        }
        val url = URL("https://huggingface.co/$REPOSITORY/resolve/$REVISION/${spec.name}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 300_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && have > 0) {
                // The remote object/range state changed. Discard only the temporary
                // file and retry once without Range; verified final files are untouched.
                connection.disconnect()
                partial.delete()
                downloadOne(spec, partial, progress)
                return
            }
            if (responseCode !in 200..299) {
                throw IOException("Model host returned HTTP $responseCode")
            }
            val append = have > 0 &&
                responseCode == HttpURLConnection.HTTP_PARTIAL &&
                contentRangeStart(connection.getHeaderField("Content-Range")) == have
            if (have > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL && !append) {
                connection.disconnect()
                partial.delete()
                downloadOne(spec, partial, progress)
                return
            }
            if (!append) have = 0
            FileOutputStream(partial, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = have
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            written += count
                            progress(written)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}
