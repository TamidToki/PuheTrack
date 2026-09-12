package com.puhetrack.app.translate

import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.TranslationKind

object TranslatorFactory {
    fun create(provider: String, settings: SettingsStore): EngineTranslator = when (
        runCatching { TranslationKind.valueOf(provider) }.getOrDefault(TranslationKind.MLKIT)
    ) {
        TranslationKind.MLKIT -> MlKitEngineTranslator(FiEnTranslator())
        TranslationKind.GOOGLE -> GoogleEngineTranslator { settings.googleKey }
        TranslationKind.DEEPL -> DeepLEngineTranslator { settings.deeplKey }
    }

    fun missingPrerequisite(provider: String, settings: SettingsStore): String? = when (
        runCatching { TranslationKind.valueOf(provider) }.getOrDefault(TranslationKind.MLKIT)
    ) {
        TranslationKind.GOOGLE -> if (settings.googleKey.isBlank()) "google_translate_key" else null
        TranslationKind.DEEPL -> if (settings.deeplKey.isBlank()) "deepl_key" else null
        TranslationKind.MLKIT -> null
    }
}
