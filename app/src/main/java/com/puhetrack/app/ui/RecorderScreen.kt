package com.puhetrack.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.puhetrack.app.data.relTime
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionState
import com.puhetrack.app.stt.TranscriptSegment

fun formatElapsed(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun RecorderScreen(
    mode: RecordingMode,
    sessionState: SessionState,
    modeChangeEnabled: Boolean,
    startEnabled: Boolean,
    readinessTitle: String,
    readinessDetail: String,
    isRecording: Boolean,
    isPaused: Boolean,
    status: String,
    level: Float,
    elapsedSec: Long,
    heardSec: Long,
    formatLabel: String,
    segments: List<TranscriptSegment>,
    isTranslating: Boolean = false,
    onModeChange: (RecordingMode) -> Unit,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onRetryTranslation: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val active = sessionState in setOf(
        SessionState.STARTING,
        SessionState.RECORDING,
        SessionState.PAUSING,
        SessionState.PAUSED,
        SessionState.STOPPING
    )

    val newest = segments.lastOrNull()
    LaunchedEffect(
        segments.size,
        newest?.id,
        newest?.textFi,
        newest?.textEn,
        newest?.translationUnavailable
    ) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AnimatedVisibility(visible = !(active && mode == RecordingMode.LIVE)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeSelector(mode, modeChangeEnabled, onModeChange)

                AnimatedContent(targetState = mode, label = "mode-purpose") { selected ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            if (selected == RecordingMode.LIVE) "See Finnish and English as you speak"
                            else "Keep reliable audio in exact 30-minute chunks",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (selected == RecordingMode.LIVE) {
                                "Microphone audio is saved locally in safe 30-minute MP3 chunks while finished text appears."
                            } else {
                                "Every closed chunk is saved locally before transcription starts."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().animateContentSize()
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(readinessTitle, style = MaterialTheme.typography.labelLarge)
                        Text(
                            readinessDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = isRecording && !isPaused) {
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(if (level > 0.78f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                )
            }
        }

        if (segments.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(58.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Text(
                        when {
                            sessionState == SessionState.STARTING -> "Preparing a safe session…"
                            isPaused -> "Capture is paused"
                            isRecording && mode == RecordingMode.LIVE -> "Listening for Finnish…"
                            isRecording -> "Recording locally…"
                            else -> "Ready when you are"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (isRecording && mode == RecordingMode.BATCH) {
                            "The first transcript appears after the current 30-minute chunk closes."
                        } else if (isRecording) {
                            "Finished utterances will appear here with their translation."
                        } else {
                            "Choose a mode above, then start recording."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(segments, key = { it.id }) { segment ->
                    TranscriptRow(
                        segment = segment,
                        isTranslating = isTranslating &&
                            (segment.textEn.isBlank() || segment.textEn == "Translating…") &&
                            !segment.translationUnavailable,
                        onRetry = { onRetryTranslation(segment.id) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                }
            }
        }

        RecorderControls(
            mode = mode,
            active = active,
            state = sessionState,
            isRecording = isRecording,
            isPaused = isPaused,
            status = status,
            elapsedSec = elapsedSec,
            heardSec = heardSec,
            formatLabel = formatLabel,
            hasTranscript = segments.any { it.isFinal },
            startEnabled = startEnabled,
            onStart = onStart,
            onPauseResume = onPauseResume,
            onStop = onStop,
            onShare = onShare
        )
    }
}

@Composable
private fun ModeSelector(
    mode: RecordingMode,
    enabled: Boolean,
    onModeChange: (RecordingMode) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(4.dp).height(IntrinsicSize.Min)) {
            ModeChoice(
                label = "Live Translate",
                selected = mode == RecordingMode.LIVE,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(RecordingMode.LIVE) }
            )
            ModeChoice(
                label = "30-minute Recording",
                selected = mode == RecordingMode.BATCH,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(RecordingMode.BATCH) }
            )
        }
    }
}

@Composable
private fun ModeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(11.dp),
        shadowElevation = if (selected) 1.dp else 0.dp,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun TranscriptRow(
    segment: TranscriptSegment,
    isTranslating: Boolean,
    onRetry: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                relTime(segment.startMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (!segment.isFinal) {
                Spacer(Modifier.width(8.dp))
                Text("listening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            segment.textFi.ifBlank { "…" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        when {
            segment.translationUnavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "English translation unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onRetry) { Text("Retry") }
            }
            segment.textEn.isNotBlank() && segment.textEn != "Translating…" -> Text(
                segment.textEn,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            segment.isFinal && isTranslating -> Text(
                "Translating…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

@Composable
private fun RecorderControls(
    mode: RecordingMode,
    active: Boolean,
    state: SessionState,
    isRecording: Boolean,
    isPaused: Boolean,
    status: String,
    elapsedSec: Long,
    heardSec: Long,
    formatLabel: String,
    hasTranscript: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "recording-pulse")
    val pulseAlpha = pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "recording-pulse-alpha"
    ).value

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).clip(CircleShape).background(
                            when {
                                isRecording && !isPaused -> MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)
                                active -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (active) formatElapsed(elapsedSec) else if (state == SessionState.FAILED) "Needs attention" else "Ready",
                    style = MaterialTheme.typography.titleMedium
                )
                if (active && mode == RecordingMode.BATCH) {
                    val position = heardSec % (30 * 60)
                    Text(
                        "Chunk ${formatElapsed(position)} / 30:00${if (formatLabel.isNotBlank()) " · $formatLabel" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (active && formatLabel.isNotBlank()) {
                    Text(formatLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (active) {
                if (hasTranscript) {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.Share, contentDescription = "Share transcript")
                    }
                }
                IconButton(
                    enabled = state == SessionState.RECORDING || state == SessionState.PAUSED,
                    onClick = onPauseResume
                ) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause"
                    )
                }
                Button(
                    enabled = state != SessionState.STOPPING,
                    onClick = onStop,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (state == SessionState.STOPPING) "Saving" else "Stop")
                }
            } else {
                Button(
                    enabled = startEnabled,
                    onClick = onStart,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (mode == RecordingMode.LIVE) "Start live" else "Start recording")
                }
            }
        }
    }
}
