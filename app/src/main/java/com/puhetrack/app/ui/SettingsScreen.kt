package com.puhetrack.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.puhetrack.app.BuildConfig
import com.puhetrack.app.models.ModelRepository
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.TranslationKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(store: SettingsStore, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var translation by remember { mutableStateOf(store.translation) }
    var batchEngine by remember { mutableStateOf(store.batchEngine) }
    var bitrate by remember { mutableStateOf(store.bitrateKbps) }
    var openaiKey by remember { mutableStateOf("") }
    var googleKey by remember { mutableStateOf("") }
    var deeplKey by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf(ModelRepository.status(context)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var modelMessage by remember { mutableStateOf("") }
    var allowMetered by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Recording mode now lives on the Record screen, where it is visible before every session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingsSection("English translation", "Applied to new sessions and frozen when recording starts.") {
            ChoiceRow("ML Kit", "On-device after its language model is available", translation == TranslationKind.MLKIT) {
                translation = TranslationKind.MLKIT
                store.translation = translation
            }
            ChoiceRow("Google Translate", "Cloud service · requires a Google API key", translation == TranslationKind.GOOGLE) {
                translation = TranslationKind.GOOGLE
                store.translation = translation
            }
            ChoiceRow("DeepL", "Cloud service · requires a DeepL key", translation == TranslationKind.DEEPL) {
                translation = TranslationKind.DEEPL
                store.translation = translation
            }
        }

        SettingsSection("30-minute transcription", "Choose how saved audio chunks become Finnish text.") {
            ChoiceRow("Offline AI", "Free and private · requires a one-time ${ModelRepository.DISPLAY_SIZE} download", batchEngine == "OFFLINE") {
                batchEngine = "OFFLINE"
                store.batchEngine = batchEngine
            }
            ChoiceRow("OpenAI Whisper API", "Cloud processing · requires an OpenAI key", batchEngine == "OPENAI") {
                batchEngine = "OPENAI"
                store.batchEngine = batchEngine
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            when (val status = modelStatus) {
                is ModelRepository.Status.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Offline model ready", fontWeight = FontWeight.SemiBold)
                            Text("Pinned and checksum-verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is ModelRepository.Status.Missing -> ModelDownloadControls(
                    availableBytes = status.availableBytes,
                    downloading = downloading,
                    progress = progress,
                    allowMetered = allowMetered,
                    message = modelMessage,
                    onMeteredChange = { allowMetered = it },
                    onDownload = {
                        downloading = true
                        modelMessage = "Downloading and verifying…"
                        scope.launch {
                            runCatching {
                                ModelRepository.download(context, allowMetered) { downloaded, total ->
                                    progress = if (total == 0L) 0f else downloaded.toFloat() / total.toFloat()
                                }
                            }.onSuccess {
                                modelStatus = ModelRepository.status(context)
                                modelMessage = "Offline model installed"
                                onSaved("Offline model ready")
                            }.onFailure { failure ->
                                modelStatus = ModelRepository.status(context)
                                modelMessage = failure.message ?: "Model download failed"
                            }
                            downloading = false
                        }
                    }
                )
                is ModelRepository.Status.Invalid -> {
                    Text("Offline model needs repair", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(status.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !downloading,
                        onClick = {
                            val removed = ModelRepository.removeInvalidInstallation(context)
                            modelStatus = ModelRepository.status(context)
                            modelMessage = "Removed $removed invalid files. Download again to repair."
                        }
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Remove invalid model")
                    }
                }
            }
        }

        SettingsSection("Cloud keys", "Stored in Android encrypted preferences. Existing keys stay unchanged if a field is blank.") {
            SecretField(openaiKey, { openaiKey = it }, "OpenAI key", store.openaiKey.isNotBlank())
            SecretField(googleKey, { googleKey = it }, "Google key", store.googleKey.isNotBlank())
            SecretField(deeplKey, { deeplKey = it }, "DeepL key", store.deeplKey.isNotBlank())
            FilledTonalButton(
                onClick = {
                    if (store.saveCloudKeys(openaiKey, googleKey, deeplKey)) {
                        openaiKey = ""
                        googleKey = ""
                        deeplKey = ""
                        onSaved("Cloud keys saved")
                    } else {
                        onSaved("Could not save cloud keys")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save entered keys")
            }
            if (store.openaiKey.isNotBlank() || store.googleKey.isNotBlank() || store.deeplKey.isNotBlank()) {
                TextButton(
                    onClick = {
                        onSaved(if (store.clearCloudKeys()) "All cloud keys removed" else "Could not remove cloud keys")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remove all saved cloud keys", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        SettingsSection("Cloud recording quality", "Only used for OpenAI batch chunks; offline recording uses lossless WAV.") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(64, 96).forEach { option ->
                    ChoicePill("$option kbps", bitrate == option, Modifier.weight(1f)) {
                        bitrate = option
                        store.bitrateKbps = option
                    }
                }
            }
            Text(
                "96 kbps is the maximum so a full 30-minute chunk remains below the 24 MB application upload safety limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsSection("Privacy and version", "PuheTrack ${BuildConfig.VERSION_NAME}") {
            Text(
                "Audio and transcripts stay in the app's private storage. Data leaves the device only when you select a cloud transcription or translation provider. Live Translate archives microphone audio while producing finished text.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsSection(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp))
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String, saved: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("$label · ${if (saved) "saved" else "not set"}") },
        placeholder = { Text(if (saved) "Enter only to replace" else "Enter key") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ModelDownloadControls(
    availableBytes: Long,
    downloading: Boolean,
    progress: Float,
    allowMetered: Boolean,
    message: String,
    onMeteredChange: (Boolean) -> Unit,
    onDownload: () -> Unit
) {
    Text("Offline model not installed", fontWeight = FontWeight.SemiBold)
    Text(
        "Download ${ModelRepository.DISPLAY_SIZE}; about ${formatStorage(availableBytes)} is currently free. Recording can still start, and saved chunks will wait safely for installation.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(enabled = !downloading, checked = allowMetered, onCheckedChange = onMeteredChange)
        Spacer(Modifier.width(10.dp))
        Text("Allow metered or mobile data", style = MaterialTheme.typography.bodyMedium)
    }
    if (downloading) {
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${(progress * 100).toInt()}% · $message", style = MaterialTheme.typography.bodySmall)
    } else {
        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Download offline model")
        }
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatStorage(bytes: Long): String = "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
