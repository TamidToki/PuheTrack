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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fileDate(f: File): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))

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
    files: List<File>,
    onOpen: (File) -> Unit,
    onDelete: (File) -> Unit,
    onDeleteAll: () -> Unit,
    onCleanupJunk: (List<File>) -> Unit,
    onShareAudio: (File) -> Unit,
    onShareText: (File) -> Unit
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }

    // Interrupted sessions (crashes) leave tiny unplayable stubs.
    val junk = remember(files) { files.filter { it.length() < 16_384 } }

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
                Text("Recordings (${files.size})", style = MaterialTheme.typography.headlineMedium)
                Row {
                    if (junk.isNotEmpty()) {
                        IconButton(onClick = {
                            stopPlayer()
                            onCleanupJunk(junk)
                        }) {
                            Icon(Icons.Default.Warning, contentDescription = "Clean interrupted", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (files.isNotEmpty()) {
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

            if (files.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🎙", fontSize = 80.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No recordings yet",
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
                    items(files, key = { it.absolutePath }) { f ->
                        RecordingRow(
                            f = f,
                            playing = playing == f.absolutePath,
                            onOpen = { onOpen(f) },
                            onTogglePlay = { togglePlay(f) },
                            onShareAudio = { onShareAudio(f) },
                            onDelete = {
                                if (playing == f.absolutePath) stopPlayer()
                                onDelete(f)
                            }
                        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingRow(
    f: File,
    playing: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onShareAudio: () -> Unit,
    onDelete: () -> Unit
) {
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
                    val displayName = f.name
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
                        text = "${fileDate(f)} • ${fileDuration(f).ifBlank { "audio" }} • ${fileSize(f)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Preview snippet from transcript
                    val previewSnippet = remember(f) {
                        try {
                            val txt = java.io.File(f.parent, f.nameWithoutExtension + ".txt")
                            if (txt.exists()) {
                                val firstLine = txt.bufferedReader().lineSequence()
                                    .filter { it.startsWith("FI:") }
                                    .firstOrNull()
                                    ?.removePrefix("FI: ")
                                    ?.take(80)
                                firstLine ?: ""
                            } else ""
                        } catch (_: Exception) { "" }
                    }
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
