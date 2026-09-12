package com.puhetrack.app.ui
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.puhetrack.app.audio.TrimAudio
import com.puhetrack.app.data.readSegments
import com.puhetrack.app.data.relTime
import com.puhetrack.app.data.sidecar
import com.puhetrack.app.export.PdfExport
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.TranslationKind
import com.puhetrack.app.study.VocabItem
import com.puhetrack.app.study.keySentences
import com.puhetrack.app.study.topVocabulary
import com.puhetrack.app.translate.DeepLEngineTranslator
import com.puhetrack.app.translate.EngineTranslator
import com.puhetrack.app.translate.FiEnTranslator
import com.puhetrack.app.translate.FinnishTranslit
import com.puhetrack.app.translate.GoogleEngineTranslator
import com.puhetrack.app.translate.MlKitEngineTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun audioDurationSec(f: File): Double = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(f.absolutePath)
    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    r.release()
    ms / 1000.0
} catch (_: Exception) { 0.0 }

private fun formatTime(ms: Int): String {
    val s = ms / 1000
    val m = s / 60
    return String.format(Locale.US, "%d:%02d", m, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingDetailScreen(
    audio: File,
    displayTitle: String,
    settings: SettingsStore,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onDelete: (File) -> Unit,
    onRename: suspend (String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val segs = remember(audio) { readSegments(sidecar(audio, "json")) }
    val translator = remember(settings.translation) {
        val t: EngineTranslator = when (settings.translation) {
            TranslationKind.GOOGLE -> GoogleEngineTranslator { settings.googleKey }
            TranslationKind.DEEPL -> DeepLEngineTranslator({ settings.deeplKey })
            else -> MlKitEngineTranslator(FiEnTranslator())
        }
        t
    }
    var status by remember(audio) { mutableStateOf("") }
    var busy by remember(audio) { mutableStateOf(false) }
    var generating by remember(audio) { mutableStateOf(false) }
    var vocab by remember(audio) { mutableStateOf<List<VocabItem>?>(null) }
    var keySegs by remember(audio) { mutableStateOf(keySentences(segs, 6)) }
    var newName by remember(audio, displayTitle) { mutableStateOf(displayTitle) }
    var trimStart by remember(audio) { mutableStateOf("0") }
    var trimEnd by remember(audio) {
        mutableStateOf("%.0f".format(Locale.US, audioDurationSec(audio).coerceAtLeast(1.0)))
    }
    var confirmDelete by remember(audio) { mutableStateOf(false) }

    // Count segments missing EN translation
    val missingEnCount = remember(segs) { segs.count { it.en.isBlank() } }

    Column(Modifier.fillMaxSize()) {
        // Proper Material 3 TopAppBar
        TopAppBar(
            title = {
                Column {
                    Text(
                        displayTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "%.1f s • %d segments".format(Locale.US, audioDurationSec(audio), segs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    if (confirmDelete) {
                        onDelete(audio)
                    } else {
                        confirmDelete = true
                    }
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
        }

        val mediaPlayer = remember(audio) { MediaPlayer() }
        var isPlaying by remember(audio) { mutableStateOf(false) }
        var currentPos by remember(audio) { mutableIntStateOf(0) }
        var duration by remember(audio) { mutableIntStateOf(0) }
        var playerReady by remember(audio) { mutableStateOf(false) }

        DisposableEffect(audio) {
            try {
                mediaPlayer.setDataSource(audio.absolutePath)
                mediaPlayer.setOnPreparedListener { mp ->
                    duration = mp.duration
                    playerReady = true
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    status = "Error loading audio"
                    true
                }
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    currentPos = 0
                }
                mediaPlayer.prepareAsync()
            } catch (e: Exception) {
                status = "Audio player error: ${e.message}"
            }
            onDispose {
                mediaPlayer.release()
            }
        }

        var isDragging by remember(audio) { mutableStateOf(false) }

        LaunchedEffect(isPlaying) {
            while (isPlaying) {
                if (!isDragging) {
                    currentPos = mediaPlayer.currentPosition
                }
                delay(200)
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                FloatingActionButton(
                    onClick = {
                        if (playerReady) {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Text(if (isPlaying) "⏸" else "▶", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Text(formatTime(currentPos), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = currentPos.toFloat(),
                    onValueChange = {
                        isDragging = true
                        currentPos = it.toInt()
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        if (playerReady) mediaPlayer.seekTo(currentPos)
                    },
                    valueRange = 0f..(if (duration > 0) duration.toFloat() else 100f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(formatTime(duration), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        val vocabList = vocab
        if (vocabList != null) {
            Card(Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Vocabulary ×${vocabList.size} + ${keySegs.size} key sentences",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        vocabList.forEach { v ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(v.fi, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(v.en.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium)
                                Text("×${v.count}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(segs) { index, s ->
                val bgColor = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = bgColor)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(relTime(s.startMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Box 1: Finnish
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "🇫🇮  ${s.fi}",
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        
                        // Box 2: English
                        if (s.en.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "🇬🇧  ${s.en}",
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    if (index < segs.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        var showTrim by remember(audio) { mutableStateOf(false) }
        var showRename by remember(audio) { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { showTrim = !showTrim }) { Text("✂ Trim clip") }
            OutlinedButton(onClick = { showRename = !showRename }) { Text("✏ Rename") }
        }

        AnimatedVisibility(visible = showTrim) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("Export clip (seconds)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = trimStart, onValueChange = { trimStart = it },
                        label = { Text("Start") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                    OutlinedTextField(
                        value = trimEnd, onValueChange = { trimEnd = it },
                        label = { Text("End") }, modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = "trimming…"
                            scope.launch {
                                try {
                                    val r = TrimAudio.trimToMp3(
                                        audio,
                                        trimStart.toDoubleOrNull() ?: 0.0,
                                        trimEnd.toDoubleOrNull() ?: 0.0,
                                        settings.bitrateKbps
                                    )
                                    status = "clip saved: ${r.clip.name} (${r.segments} segments)"
                                    onChanged()
                                } catch (e: Exception) {
                                    status = "trim failed: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("Clip") }
                }
            }
        }

        AnimatedVisibility(visible = showRename) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("Lesson name", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("e.g. AKK suomi 2026-09-01") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(enabled = !busy, onClick = {
                        val clean = newName.trim()
                        if (clean.isEmpty()) {
                            status = "name is empty"
                            return@Button
                        }
                        if (clean == displayTitle) {
                            status = "name unchanged"
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            try {
                                val renamed = onRename(clean)
                                status = if (renamed) "lesson renamed" else "this legacy recording cannot be renamed safely"
                                if (renamed) onChanged()
                            } catch (e: Exception) {
                                status = "rename failed: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Rename") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                status = "building PDF…"
                scope.launch {
                    try {
                        val pdf = sidecar(audio, "pdf")
                        withContext(Dispatchers.IO) {
                            PdfExport.exportPdf(pdf, audio.nameWithoutExtension, segs)
                        }
                        status = "PDF saved — sharing…"
                        onChanged()
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", pdf
                        )
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share PDF"))
                    } catch (e: Exception) {
                        status = "PDF failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("📄 Export PDF") }
            Button(
                enabled = !generating && segs.isNotEmpty(),
                onClick = {
                    generating = true
                    status = "building study pack…"
                    scope.launch(Dispatchers.IO) {
                        try {
                            val key = keySentences(segs, 6)
                            val words = topVocabulary(segs, 25)
                            words.forEach { it.en = translator.translate(it.fi) }
                            withContext(Dispatchers.Main) {
                                keySegs = key
                                vocab = words
                                status = "study pack ready: ${words.size} words, ${key.size} key sentences"
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                status = "study pack failed: ${e.message}"
                            }
                        } finally {
                            withContext(Dispatchers.Main) { generating = false }
                        }
                    }
                }
            ) { Text("Study Pack") }
            OutlinedButton(
                enabled = vocab != null && !busy,
                onClick = {
                    busy = true
                    status = "building study PDF…"
                    scope.launch {
                        try {
                            val pdf = File(audio.parent, audio.nameWithoutExtension + "-study.pdf")
                            withContext(Dispatchers.IO) {
                                PdfExport.exportStudyPdf(
                                    pdf, audio.nameWithoutExtension, segs,
                                    vocab ?: emptyList(), keySegs
                                )
                            }
                            status = "study PDF saved — sharing…"
                            onChanged()
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", pdf
                            )
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share study PDF"))
                        } catch (e: Exception) {
                            status = "study PDF failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Study PDF") }
        }
        } // inner Column
    }
}
