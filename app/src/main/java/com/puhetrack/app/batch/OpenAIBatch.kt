package com.puhetrack.app.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OpenAIBatch {
    private const val MAX_UPLOAD_BYTES = 24_000_000L

    class PermanentFailure(val errorCode: String, message: String) : RuntimeException(message)
    class TransientFailure(val errorCode: String, message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
    class OutcomeUnknown(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(audioFile: File, apiKey: String, lang: String = "fi"): BatchTranscript {
            if (apiKey.isBlank()) throw PermanentFailure("missing_key", "OpenAI API key is missing")
            if (!audioFile.isFile) throw PermanentFailure("audio_missing", "Audio file is unavailable")
            if (audioFile.length() <= 0) throw PermanentFailure("audio_empty", "Audio file is empty")
            if (audioFile.length() > MAX_UPLOAD_BYTES) {
                throw PermanentFailure("payload_too_large", "Audio exceeds the 24 MB application safety limit")
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-1")
                .addFormDataPart("language", lang)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(mediaType(audioFile).toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            return try {
                execute(request).use { response ->
                    val requestId = response.header("x-request-id")
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val code = classifyStatus(response.code, raw)
                        if (response.code == 408 || response.code == 429 || response.code in 500..599) {
                            throw TransientFailure(code, "OpenAI transcription is temporarily unavailable")
                        }
                        throw PermanentFailure(code, "OpenAI transcription request was rejected")
                    }
                    val json = runCatching { JSONObject(raw) }.getOrElse {
                        throw TransientFailure("invalid_response", "OpenAI returned an unreadable response", it)
                    }
                    val segmentArray = json.optJSONArray("segments")
                    val segments = if (segmentArray != null && segmentArray.length() > 0) {
                        List(segmentArray.length()) { index ->
                            val segment = segmentArray.getJSONObject(index)
                            BatchTranscriptSegment(
                                text = segment.optString("text").trim(),
                                startMs = (segment.optDouble("start", 0.0) * 1000).toLong(),
                                endMs = (segment.optDouble("end", 0.0) * 1000).toLong(),
                                timingProvenance = "provider_segment"
                            )
                        }.filter { it.text.isNotBlank() }
                    } else {
                        val transcript = json.optString("text").trim()
                        if (transcript.isBlank()) emptyList()
                        else listOf(
                            BatchTranscriptSegment(
                                text = transcript,
                                startMs = 0,
                                endMs = (json.optDouble("duration", 0.0) * 1000).toLong(),
                                timingProvenance = "provider_chunk"
                            )
                        )
                    }
                    BatchTranscript(segments = segments, providerRequestId = requestId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentFailure) {
                throw e
            } catch (e: TransientFailure) {
                throw e
            } catch (e: IOException) {
                // A disconnect may occur after the provider accepted the upload.
                // Preserve uncertainty instead of issuing a billable duplicate.
                throw OutcomeUnknown("OpenAI response was not received", e)
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { response.close() }
            }
        })
    }

    private fun mediaType(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        else -> "audio/mpeg"
    }

    private fun classifyStatus(status: Int, body: String): String {
        val providerType = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("type").orEmpty()
        }.getOrDefault("")
        return when {
            status == 400 -> "bad_request"
            status == 401 -> "invalid_key"
            status == 403 -> "forbidden"
            status == 408 -> "timeout"
            status == 413 -> "payload_too_large"
            status == 429 && providerType.contains("billing", ignoreCase = true) -> "billing_limit"
            status == 429 -> "rate_limit"
            status in 500..599 -> "server_error"
            else -> "http_$status"
        }
    }
}
