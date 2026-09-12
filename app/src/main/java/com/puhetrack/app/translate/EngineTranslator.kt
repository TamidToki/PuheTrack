package com.puhetrack.app.translate

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface EngineTranslator {
    val label: String
    suspend fun translate(fi: String): String
    suspend fun prepare() {}
    fun close() {}
}

class MlKitEngineTranslator(private val mlKit: FiEnTranslator) : EngineTranslator {
    override val label = "ML Kit (free, offline)"
    override suspend fun translate(fi: String): String = withRetrying {
        mlKit.translate(fi)
    }
    override suspend fun prepare() = withRetrying { mlKit.ensureModel() }
    override fun close() = mlKit.close()
}

internal val cloudHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

class GoogleEngineTranslator(private val apiKey: () -> String) : EngineTranslator {
    override val label = "Google Translate (paid)"
    override suspend fun translate(fi: String): String = withRetrying {
        doGoogleTranslate(apiKey(), fi)
    }
}

class DeepLEngineTranslator(
    private val apiKey: () -> String
) : EngineTranslator {
    override val label = "DeepL (500k chars/mo free)"
    override suspend fun translate(fi: String): String = withRetrying {
        doDeepL(apiKey(), fi)
    }
}

/**
 * Translation used to silently fail to "" and the UI would just show
 * the Finnish line forever. Now: log the real error, retry once on
 * transient failures, and throw TranslationFailure for the service
 * to mark the segment "translation unavailable" (not blank).
 */
private const val TAG = "PuheTrack"

class TranslationFailure(msg: String) : RuntimeException(msg)

private suspend inline fun <T> withRetrying(crossinline block: suspend () -> T): T {
    var last: Throwable? = null
    for (attempt in 1..2) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationFailure) {
            throw e
        } catch (e: Throwable) {
            last = e
            Log.w(TAG, "translate attempt $attempt failed", e)
            if (attempt == 1) delay(400)
        }
    }
    Log.e(TAG, "translate failed after 2 attempts: ${last?.message}")
    throw TranslationFailure(last?.message ?: "unknown")
}

private suspend fun doGoogleTranslate(key: String, fi: String): String {
    if (fi.isBlank()) return ""
    if (key.isBlank()) throw TranslationFailure("Google Translate key missing")
    val body = JSONObject()
        .put("q", fi)
        .put("source", "fi")
        .put("target", "en")
        .put("format", "text")
        .toString()
        .toRequestBody("application/json".toMediaType())
    val req = Request.Builder()
        .url("https://translation.googleapis.com/language/translate/v2")
        .header("X-goog-api-key", key)
        .post(body)
        .build()
    return callCloud(req, "Google Translate") {
        JSONObject(it)
            .getJSONObject("data")
            .getJSONArray("translations")
            .getJSONObject(0)
            .getString("translatedText")
    }
}

private suspend fun doDeepL(key: String, fi: String): String {
    if (fi.isBlank()) return ""
    if (key.isBlank()) throw TranslationFailure("DeepL key missing")
    val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
    val body = JSONObject()
        .put("text", org.json.JSONArray().put(fi))
        .put("source_lang", "FI")
        .put("target_lang", "EN")
        .toString()
        .toRequestBody("application/json".toMediaType())
    val req = Request.Builder()
        .url("https://$host/v2/translate")
        .header("Authorization", "DeepL-Auth-Key $key")
        .post(body)
        .build()
    return callCloud(req, "DeepL") {
        JSONObject(it)
            .getJSONArray("translations")
            .getJSONObject(0)
            .getString("text")
    }
}

/** Cancellable asynchronous HTTP: stopping a worker also cancels its socket. */
private suspend fun callCloud(req: Request, label: String, parse: (String) -> String): String =
    executeCloud(req).use { res ->
        if (!res.isSuccessful) throw RuntimeException("$label HTTP ${res.code}")
        val body = res.body?.string() ?: throw RuntimeException("$label empty body")
        try {
            parse(body)
        } catch (e: Exception) {
            throw RuntimeException("$label parse error: ${e.message}", e)
        }
    }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun executeCloud(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = cloudHttp.newCall(request)
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
