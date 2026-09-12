package com.puhetrack.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.data.CanonicalSegment
import com.puhetrack.app.data.TranslationState
import com.puhetrack.app.data.relTime
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranscriptDetailScreen(
    session: SessionManifest,
    directory: File,
    segments: List<CanonicalSegment>,
    audioFiles: List<File>,
    onBack: () -> Unit,
    onShare: (File) -> Unit,
    onShareAudio: (File) -> Unit,
    onDelete: (SessionManifest) -> Unit,
    onRetryTranslation: (String) -> Unit
) {
    val textFile = remember(session) { File(directory, "${session.baseName}.txt") }
    var confirmDelete by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    fun toggleAudio(file: File) {
        if (playing == file.absolutePath) {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            playing = null
            return
        }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                playing = file.absolutePath
                mediaPlayer.setOnPreparedListener { it.start() }
                mediaPlayer.setOnCompletionListener {
                    runCatching { it.release() }
                    if (player === it) player = null
                    playing = null
                }
                mediaPlayer.setOnErrorListener { target, _, _ ->
                    runCatching { target.release() }
                    if (player === target) player = null
                    playing = null
                    true
                }
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.prepareAsync()
            }
        }.onFailure {
            player = null
            playing = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(session.displayTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Recorded live · ${audioFiles.size} audio chunk${if (audioFiles.size == 1) "" else "s"} · ${segments.size} finished utterances",
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
                IconButton(onClick = { onShare(textFile) }, enabled = textFile.exists()) {
                    Icon(Icons.Default.Share, contentDescription = "Share transcript")
                }
                IconButton(onClick = {
                    if (confirmDelete) onDelete(session) else confirmDelete = true
                }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = if (confirmDelete) "Confirm delete" else "Delete",
                        tint = if (confirmDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Text(
                    "Saved audio",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            if (audioFiles.isEmpty()) {
                item {
                    Text(
                        "No audio chunk was recovered for this session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            } else {
                items(audioFiles, key = { it.absolutePath }) { audio ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Audio ${audioFiles.indexOf(audio) + 1}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    "MP3 · %.1f MB".format(audio.length() / 1_048_576f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { toggleAudio(audio) }) {
                                Icon(
                                    if (playing == audio.absolutePath) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playing == audio.absolutePath) "Pause audio" else "Play audio"
                                )
                            }
                            IconButton(onClick = { onShareAudio(audio) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share audio")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Finished transcript",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                )
            }
            if (segments.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("No finished speech was saved in this session.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "The microphone audio remains available above even when live recognition produces no finished text.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(segments, key = { it.segmentId }) { segment ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(relTime(segment.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(segment.textFi, style = MaterialTheme.typography.bodyLarge)
                        Row {
                            if (segment.translationState == TranslationState.UNAVAILABLE) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        "English translation unavailable",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(onClick = { onRetryTranslation(segment.segmentId) }) {
                                        Text("Retry")
                                    }
                                }
                            } else if (segment.translationState == TranslationState.PENDING) {
                                Text(
                                    "English translation pending",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    segment.textEn.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(1.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
