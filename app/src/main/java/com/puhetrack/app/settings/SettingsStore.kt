@file:Suppress("DEPRECATION")

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
        get() = prefs.getInt("bitrate", 96).coerceIn(32, 96)
        set(v) { prefs.edit().putInt("bitrate", v).apply() }

    /**
     * Effective bitrate for chunk files. The Whisper API caps uploads at
     * 25 MB and a 30-minute chunk at 96 kbps is ~21.6 MB, so every MP3
     * session is capped at 96 kbps at read time.
     * This also covers values saved before the clamp existed.
     */
    fun effectiveBitrateKbps(): Int =
        bitrateKbps.coerceAtMost(96)

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

    fun saveCloudKeys(openai: String, google: String, deepl: String): Boolean = runCatching {
        val editor = secrets.edit()
        if (openai.isNotBlank()) editor.putString("openai_key", openai.trim())
        if (google.isNotBlank()) editor.putString("google_key", google.trim())
        if (deepl.isNotBlank()) editor.putString("deepl_key", deepl.trim())
        editor.commit()
    }.getOrDefault(false)

    fun clearCloudKeys(): Boolean = runCatching { secrets.edit().clear().commit() }.getOrDefault(false)
}
