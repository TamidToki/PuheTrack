package com.puhetrack.app.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class FiEnTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.FINNISH)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    suspend fun ensureModel() {
        try {
            translator.downloadModelIfNeeded(
                DownloadConditions.Builder().requireWifi().build()
            ).await()
        } catch (_: Exception) {
            // tolerate: model may already be present or network may be slow
        }
    }

    suspend fun translate(text: String): String {
        if (text.isBlank()) return ""
        return try {
            kotlinx.coroutines.withTimeout(5000) {
                translator.translate(text).await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw com.puhetrack.app.translate.TranslationFailure("Translation timed out. Check network or model download.")
        } catch (e: Exception) {
            // Bubble up so the service's retry-and-mark logic can show
            // "translation unavailable" instead of a permanent blank.
            throw com.puhetrack.app.translate.TranslationFailure(
                "ML Kit translate failed: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    fun close() = translator.close()
}
