package com.puhetrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.TranslationKind

@Composable
fun SettingsScreen(store: SettingsStore, onSaved: (String) -> Unit) {
    var translation by remember { mutableStateOf(store.translation) }
    var openaiKey by remember { mutableStateOf(store.openaiKey) }
    var deeplKey by remember { mutableStateOf(store.deeplKey) }
    var bitrate by remember { mutableStateOf(store.bitrateKbps) }
    var skipSilence by remember { mutableStateOf(store.skipSilence) }
    var silenceMs by remember { mutableStateOf(store.silenceThresholdMs) }
    var vadLevel by remember { mutableStateOf(store.vadLevel) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    "The defaults work out of the box! Free offline translation is ready. Set your OpenAI key for 10-minute chunk transcriptions.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("English Translation", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(
                    "Used to translate the Finnish text.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                listOf(
                    TranslationKind.MLKIT to ("ML Kit ✓ recommended" to "Free, offline"),
                    TranslationKind.GOOGLE to ("Google Translate" to "Paid, needs Google key"),
                    TranslationKind.DEEPL to ("DeepL" to "500k chars/mo free, needs key")
                ).forEach { (kind, labelInfo) ->
                    val (title, subtitle) = labelInfo
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { translation = kind; store.translation = kind }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = translation == kind,
                            onClick = { translation = kind; store.translation = kind }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Recording Mode", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(
                    "Choose how you want to transcribe.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                var recordingMode by remember { mutableStateOf(store.recordingMode) }
                
                listOf(
                    "BATCH" to ("Background Chunks (15m)" to "Records silently and processes exactly every 15 mins. Highly accurate."),
                    "LIVE" to ("Live Real-Time" to "Translates word-by-word instantly on screen. Uses Google Speech.")
                ).forEach { (kind, labelInfo) ->
                    val (title, subtitle) = labelInfo
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { recordingMode = kind; store.recordingMode = kind }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = recordingMode == kind,
                            onClick = { recordingMode = kind; store.recordingMode = kind }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Transcription Engine", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(
                    "Used to convert spoken Finnish to text in 15m chunks.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                var batchEngine by remember { mutableStateOf(store.batchEngine) }
                
                listOf(
                    "OFFLINE" to ("Offline AI (Free)" to "Runs completely on your device, no API key required."),
                    "OPENAI" to ("OpenAI Whisper API" to "Most accurate, requires an OpenAI API key.")
                ).forEach { (kind, labelInfo) ->
                    val (title, subtitle) = labelInfo
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { batchEngine = kind; store.batchEngine = kind }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = batchEngine == kind,
                            onClick = { batchEngine = kind; store.batchEngine = kind }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Cloud Keys (stored encrypted)", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                OutlinedTextField(
                    value = openaiKey, onValueChange = { openaiKey = it },
                    label = { Text("OpenAI key ${if (store.openaiKey.isNotBlank()) "(saved ✓)" else "(not set)"}") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = deeplKey, onValueChange = { deeplKey = it },
                    label = { Text("DeepL key ${if (store.deeplKey.isNotBlank()) "(saved ✓)" else "(not set)"}") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                Text(
                    "DeepL free keys end in :fx and use the free API automatically.",
                    style = MaterialTheme.typography.bodySmall
                )
                
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        store.openaiKey = openaiKey
                        store.deeplKey = deeplKey
                        onSaved("keys saved ✓")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Keys", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("MP3 Quality", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text("Applies to the next recording.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(64, 96, 128).forEach { br ->
                        Row(
                            Modifier.clickable { bitrate = br; store.bitrateKbps = br },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = bitrate == br, onClick = {
                                bitrate = br
                                store.bitrateKbps = br
                            })
                            Text("$br kbps")
                        }
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Silence Auto-Cut", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Text(
                    "Cuts parts with no talking from the MP3 so files stay short. Transcript times stay matched to the shortened audio.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { skipSilence = !skipSilence; store.skipSilence = skipSilence }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(checked = skipSilence, onCheckedChange = {
                        skipSilence = it
                        store.skipSilence = it
                    })
                    Spacer(Modifier.width(16.dp))
                    Text(if (skipSilence) "On — cut silence" else "Off — keep everything", style = MaterialTheme.typography.bodyLarge)
                }
                if (skipSilence) {
                    Text("Cut when quiet for at least:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500 to "0.5 s", 1000 to "1 s", 2000 to "2 s").forEach { (ms, label) ->
                            Row(
                                Modifier.clickable { silenceMs = ms; store.silenceThresholdMs = ms },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = silenceMs == ms, onClick = {
                                    silenceMs = ms
                                    store.silenceThresholdMs = ms
                                    })
                                Text(label)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Mic sensitivity:", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Sensitive", 1 to "Normal", 2 to "Strict").forEach { (lv, label) ->
                            Row(
                                Modifier.clickable { vadLevel = lv; store.vadLevel = lv },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = vadLevel == lv, onClick = {
                                    vadLevel = lv
                                    store.vadLevel = lv
                                })
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
        
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text("PuheTrack v${com.puhetrack.app.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "PuheTrack is a powerful tool to record, transcribe, and translate your Finnish lessons. Built with Kotlin and Jetpack Compose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
