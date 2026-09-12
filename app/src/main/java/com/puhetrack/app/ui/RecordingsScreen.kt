package com.puhetrack.app.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.data.ChunkManifest
import com.puhetrack.app.data.ProcessingStage
import com.puhetrack.app.data.requiresPaidRetranscriptionConfirmation
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fileDate(f: File): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))

private fun fileDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))

private fun fileSize(f: File): String {
    val kb = f.length() / 1024
    return if (kb < 1024) "${kb} KB" else "%.1f MB".format(kb / 1024f)
}

private fun fileDuration(f: File): String = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(f.absolutePath)
    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    r.release()
    formatElapsed(ms / 1000)
} catch (_: Exception) { "" }

private fun formatProgress(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d", m, s)
}

val LocalPlayer = compositionLocalOf<MediaPlayer?> { null }

@Composable
fun RecordingsScreen(
    directory: File,
    files: List<File>,
    liveSessions: List<SessionManifest>,
    processing: Map<String, ChunkManifest>,
    titles: Map<String, String>,
    onOpen: (File) -> Unit,
    onOpenLive: (SessionManifest) -> Unit,
    onDelete: (File) -> Unit,
    onDeleteLive: (SessionManifest) -> Unit,
    onDeleteAll: () -> Unit,
    onShareAudio: (File) -> Unit,
    onShareText: (File) -> Unit,
    onRetryProcessing: (ChunkManifest, Boolean) -> Unit
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }

    val libraryItems = remember(files, liveSessions) {
        (files.map { LibraryItem.Audio(it) } + liveSessions.map { LibraryItem.Live(it) })
            .sortedByDescending { it.updatedAt }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
        }
    }

    fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        playing = null
    }

    fun togglePlay(f: File) {
        try {
            if (playing == f.absolutePath) {
                stopPlayer()
                return
            }
            stopPlayer()
            playError = ""
            val mp = MediaPlayer()
            player = mp
            playing = f.absolutePath
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                if (playing == f.absolutePath) {
                    stopPlayer()
                } else {
                    try { it.release() } catch (_: Exception) {}
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                playError = "Can't play ${f.name} (code $what) — it may be from an interrupted session. Try Clean up."
                if (playing == f.absolutePath) stopPlayer()
                else { try { mp.release() } catch (_: Exception) {} }
                true
            }
            mp.setDataSource(f.absolutePath)
            mp.prepareAsync()
        } catch (e: Exception) {
            playError = "Can't play ${f.name}: ${e.message}"
            stopPlayer()
        }
    }

    CompositionLocalProvider(LocalPlayer provides player) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Library (${libraryItems.size})", style = MaterialTheme.typography.headlineMedium)
                Row {
                    if (libraryItems.isNotEmpty()) {
                        IconButton(onClick = {
                            if (confirmAll) {
                                confirmAll = false
                                stopPlayer()
                                onDeleteAll()
                            } else {
                                confirmAll = true
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Delete all",
                                tint = if (confirmAll) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))

            if (libraryItems.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🎙", fontSize = 80.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No sessions yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap Record to start your first session",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    items(libraryItems, key = { it.key }) { item ->
                        when (item) {
                            is LibraryItem.Audio -> RecordingRow(
                                f = item.file,
                                displayTitle = titles[item.file.absolutePath],
                                manifest = processing[item.file.absolutePath],
                                playing = playing == item.file.absolutePath,
                                onOpen = { onOpen(item.file) },
                                onTogglePlay = { togglePlay(item.file) },
                                onShareAudio = { onShareAudio(item.file) },
                                onDelete = {
                                    if (playing == item.file.absolutePath) stopPlayer()
                                    onDelete(item.file)
                                },
                                onRetryProcessing = { manifest, confirmed -> onRetryProcessing(manifest, confirmed) }
                            )
                            is LibraryItem.Live -> LiveTranscriptRow(
                                session = item.session,
                                directory = directory,
                                onOpen = { onOpenLive(item.session) },
                                onShare = {
                                    onShareText(File(directory, "${item.session.baseName}.txt"))
                                },
                                onDelete = { onDeleteLive(item.session) }
                            )
                        }
                    }
                }
            }
            
            if (playError.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(playError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        IconButton(onClick = { playError = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface LibraryItem {
    val key: String
    val updatedAt: Long

    data class Audio(val file: File) : LibraryItem {
        override val key: String = file.absolutePath
        override val updatedAt: Long = file.lastModified()
    }

    data class Live(val session: SessionManifest) : LibraryItem {
        override val key: String = session.sessionId
        override val updatedAt: Long = session.updatedAtEpochMs
    }
}

@Composable
private fun LiveTranscriptRow(
    session: SessionManifest,
    directory: File,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val txt = remember(session, directory) { File(directory, "${session.baseName}.txt") }
    val preview by produceState("", txt.absolutePath, txt.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                txt.useLines { lines ->
                    lines.firstOrNull { it.startsWith("FI:") }?.removePrefix("FI: ").orEmpty()
                }
            }.getOrDefault("")
        }
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        "${fileDate(session.createdAtEpochMs)} • Recorded live audio + transcript",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preview.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onShare, enabled = txt.exists()) {
                    Icon(Icons.Default.Share, contentDescription = "Share transcript")
                }
                IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open transcript")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete transcript", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingRow(
    f: File,
    displayTitle: String?,
    manifest: ChunkManifest?,
    playing: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onShareAudio: () -> Unit,
    onDelete: () -> Unit,
    onRetryProcessing: (ChunkManifest, Boolean) -> Unit
) {
    val durationLabel by produceState("audio", f.absolutePath, f.lastModified()) {
        value = withContext(Dispatchers.IO) { fileDuration(f).ifBlank { "audio" } }
    }
    val previewSnippet by produceState("", f.absolutePath, f.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val txt = File(f.parent, f.nameWithoutExtension + ".txt")
                if (txt.exists()) {
                    txt.bufferedReader().useLines { lines ->
                        lines.firstOrNull { it.startsWith("FI:") }
                            ?.removePrefix("FI: ")
                            ?.take(80)
                            .orEmpty()
                    }
                } else ""
            }.getOrDefault("")
        }
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (playing) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (playing) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.puhetrack.app.R.drawable.ic_mic),
                        contentDescription = null,
                        tint = if (playing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val displayName = displayTitle ?: f.name
                        .removePrefix("puhetrack-")
                        .substringBeforeLast(".")
                    
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${fileDate(f)} • $durationLabel • ${fileSize(f)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Preview snippet from transcript
                    if (previewSnippet.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "\"$previewSnippet${if (previewSnippet.length >= 80) "…" else ""}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                    if (manifest != null && manifest.processingStage != ProcessingStage.COMPLETE) {
                        Spacer(Modifier.height(6.dp))
                        ProcessingStatus(manifest, onRetryProcessing)
                    }
                }
            }
            
            if (playing) {
                Spacer(Modifier.height(16.dp))
                val player = LocalPlayer.current
                var progress by remember { mutableIntStateOf(0) }
                var duration by remember { mutableIntStateOf(0) }
                var isDragging by remember { mutableStateOf(false) }

                LaunchedEffect(playing, player) {
                    if (playing && player != null) {
                        while (true) {
                            try {
                                if (player.isPlaying && !isDragging) {
                                    duration = player.duration
                                    progress = player.currentPosition
                                }
                            } catch (_: Exception) {}
                            delay(200)
                        }
                    }
                }
                
                Column(Modifier.fillMaxWidth()) {
                    Slider(
                        value = if (duration > 0) progress.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { 
                            isDragging = true
                            progress = (it * duration).toInt()
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            if (duration > 0 && player != null) {
                                try { player.seekTo(progress) } catch(_:Exception){} 
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatProgress(progress.toLong() / 1000), style = MaterialTheme.typography.bodySmall)
                        Text(formatProgress(duration.toLong() / 1000), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(48.dp)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(32.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
            ) {
                if (!playing) {
                    IconButton(onClick = onTogglePlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = onShareAudio) {
                    Icon(Icons.Default.Share, contentDescription = "Share Audio")
                }
                IconButton(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Open Text")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProcessingStatus(
    manifest: ChunkManifest,
    onRetry: (ChunkManifest, Boolean) -> Unit
) {
    val label = when (manifest.processingStage) {
        ProcessingStage.CAPTURING -> "Capture not yet closed"
        ProcessingStage.PENDING_TRANSCRIPTION -> "Waiting to transcribe"
        ProcessingStage.TRANSCRIBING -> "Transcribing Finnish"
        ProcessingStage.FINNISH_COMMITTED -> "Finnish saved · waiting for English"
        ProcessingStage.TRANSLATING -> "Translating English"
        ProcessingStage.RETRY_PENDING -> "Automatic retry scheduled"
        ProcessingStage.BLOCKED -> manifest.errorCode?.replace('_', ' ') ?: "Processing needs attention"
        ProcessingStage.OUTCOME_UNKNOWN -> "Cloud response was lost · may already be billed"
        ProcessingStage.COMPLETE -> "Complete"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (manifest.processingStage in setOf(ProcessingStage.BLOCKED, ProcessingStage.OUTCOME_UNKNOWN)) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    )
    val needsPaidRepeatConfirmation = requiresPaidRetranscriptionConfirmation(manifest)
    if (needsPaidRepeatConfirmation) {
        OutlinedButton(onClick = { onRetry(manifest, true) }) { Text("Confirm retranscription") }
    } else if (manifest.processingStage in setOf(ProcessingStage.BLOCKED, ProcessingStage.RETRY_PENDING)) {
        OutlinedButton(onClick = { onRetry(manifest, false) }) { Text("Retry processing") }
    }
}
