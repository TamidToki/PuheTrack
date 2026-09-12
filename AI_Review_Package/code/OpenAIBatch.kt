package com.puhetrack.app.batch

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object OpenAIBatch {
    private const val TAG = "OpenAIBatch"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(mp3File: File, apiKey: String, lang: String = "fi"): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalArgumentException("OpenAI API key is missing in settings")
        if (!mp3File.exists()) throw IllegalArgumentException("Audio file not found")

        val reqBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", lang)
            .addFormDataPart("response_format", "json")
            .addFormDataPart(
                "file", mp3File.name,
                mp3File.asRequestBody("audio/mpeg".toMediaType())
            )
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(reqBody)
            .build()

        Log.i(TAG, "Uploading ${mp3File.name} (${mp3File.length() / 1024} KB) to Whisper API...")
        
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                val err = JSONObject(body ?: "{}").optJSONObject("error")?.optString("message") ?: resp.message
                Log.e(TAG, "Whisper API error: $err")
                throw RuntimeException("Whisper API error: $err")
            }
            
            val json = JSONObject(body ?: "{}")
            return@withContext json.optString("text", "").trim()
        }
    }
}
