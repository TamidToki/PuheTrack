package com.puhetrack.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.puhetrack.app.stt.TranslationKind

class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("puhe_settings", Context.MODE_PRIVATE)

    private val secrets: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "puhe_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var bitrateKbps: Int
        get() = prefs.getInt("bitrate", 96).coerceIn(32, 320)
        set(v) { prefs.edit().putInt("bitrate", v).apply() }

    // Silence skip (voice activity detection)
    var skipSilence: Boolean
        get() = prefs.getBoolean("skip_silence", false)
        set(v) { prefs.edit().putBoolean("skip_silence", v).apply() }

    // Minimum silence before skipping starts (ms)
    var silenceThresholdMs: Int
        get() = prefs.getInt("silence_ms", 1000).coerceIn(300, 5000)
        set(v) { prefs.edit().putInt("silence_ms", v).apply() }

    // 0 = sensitive, 1 = normal, 2 = strict
    var vadLevel: Int
        get() = prefs.getInt("vad_level", 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt("vad_level", v).apply() }

    fun vadThreshold(): Float = when (vadLevel) {
        0 -> 0.002f
        2 -> 0.010f
        else -> 0.005f
    }

    var translation: TranslationKind
        get() = runCatching { TranslationKind.valueOf(prefs.getString("translation", "MLKIT")!!) }
            .getOrDefault(TranslationKind.MLKIT)
        set(v) { prefs.edit().putString("translation", v.name).apply() }

    var batchEngine: String
        get() = prefs.getString("batch_engine", "OFFLINE") ?: "OFFLINE"
        set(v) { prefs.edit().putString("batch_engine", v).apply() }

    var recordingMode: String
        get() = prefs.getString("recording_mode", "BATCH") ?: "BATCH"
        set(v) { prefs.edit().putString("recording_mode", v).apply() }

    var openaiKey: String
        get() = runCatching { secrets.getString("openai_key", "") ?: "" }.getOrDefault("")
        set(v) { runCatching { secrets.edit().putString("openai_key", v.trim()).apply() } }

    var googleKey: String
        get() = runCatching { secrets.getString("google_key", "") ?: "" }.getOrDefault("")
        set(v) { runCatching { secrets.edit().putString("google_key", v.trim()).apply() } }

    var deeplKey: String
        get() = runCatching { secrets.getString("deepl_key", "") ?: "" }.getOrDefault("")
        set(v) { runCatching { secrets.edit().putString("deepl_key", v.trim()).apply() } }

}
