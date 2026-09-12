package com.puhetrack.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puhetrack.app.data.relTime
import com.puhetrack.app.stt.TranscriptSegment

fun formatElapsed(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun RecorderScreen(
    isRecording: Boolean,
    isPaused: Boolean,
    status: String,
    level: Float,
    elapsedSec: Long,
    segments: List<TranscriptSegment>,
    modelReady: Boolean,
    heardSec: Long,
    isTranslating: Boolean = false,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onRetryTranslation: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { com.puhetrack.app.settings.SettingsStore(context) }
    val listState = rememberLazyListState()

    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.size - 1)
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha = pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "pulseAlpha"
    ).value

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {

        if (isRecording) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            if (level > 0.7f) MaterialTheme.colorScheme.error
                            else if (level > 0.3f) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (settings.recordingMode == "LIVE" && (segments.isNotEmpty() || isRecording)) {
                val fiScroll = rememberScrollState()
                val enScroll = rememberScrollState()
                val fiText = segments.joinToString(" ") { it.textFi }.trim()
                val enText = segments.joinToString(" ") { it.textEn.replace("Translating...", "").replace("Error", "") }.trim()
                
                LaunchedEffect(fiText) { fiScroll.animateScrollTo(fiScroll.maxValue) }
                LaunchedEffect(enText) { enScroll.animateScrollTo(enScroll.maxValue) }

                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(modifier = Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Text("Finnish (Live)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = fiText.ifEmpty { "Listening..." },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxSize().verticalScroll(fiScroll)
                            )
                        }
                    }
                    Surface(modifier = Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Text("English Translation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = enText.ifEmpty { "Waiting for translation..." },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxSize().verticalScroll(enScroll)
                            )
                        }
                    }
                }
            } else {
                if (segments.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (isRecording) "Listening (15m chunks)" else "🇫🇮 Speak Finnish → 🇬🇧 English",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            if (isRecording && settings.recordingMode == "BATCH") {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Processing chunks in background...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(segments, key = { it.id }) { seg ->
                            SegmentCard(
                                seg = seg,
                                isTranslating = isTranslating && seg.textEn.isBlank() && !seg.translationUnavailable,
                                onRetry = { onRetryTranslation(seg.id) }
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRecording && !isPaused) MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)
                                else if (isPaused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isRecording) formatElapsed(elapsedSec) else "Ready",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRecording) {
                        IconButton(onClick = onPauseResume) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pause/Resume",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (segments.isNotEmpty()) {
                            IconButton(onClick = onShare) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FloatingActionButton(
                            onClick = onStop,
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = MaterialTheme.colorScheme.onError)
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.height(56.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 24.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Record", fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(
    seg: TranscriptSegment,
    isTranslating: Boolean,
    onRetry: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        relTime(seg.startMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (seg.translationUnavailable) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "translation unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "🇫🇮  ${seg.textFi}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (seg.textEn.isNotBlank()) {
                    Text(
                        "🇬🇧  ${seg.textEn}",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                } else if (isTranslating) {
                    val transitionAlpha = rememberInfiniteTransition(label = "transAlpha")
                    val alpha = transitionAlpha.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
                        label = "transAlphaVal"
                    ).value
                    Text(
                        "🇬🇧  Translating…",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha),
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(12.dp)
                    )
                } else if (!seg.translationUnavailable) {
                    Text(
                        "🇬🇧  …",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    Text(
                        "🇬🇧  Translation failed",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}
