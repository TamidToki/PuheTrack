package com.puhetrack.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.puhetrack.app.data.Seg
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.data.readSegments
import com.puhetrack.app.data.relTime
import com.puhetrack.app.data.sidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface SearchSource {
    val key: String
    val title: String

    data class Audio(val file: File) : SearchSource {
        override val key = file.absolutePath
        override val title = file.nameWithoutExtension.removePrefix("puhetrack-")
    }

    data class Live(val session: SessionManifest) : SearchSource {
        override val key = session.sessionId
        override val title = "${session.displayTitle} · live transcript"
    }
}

private data class SearchHit(val source: SearchSource, val segment: Seg)

private fun highlightText(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val lower = text.lowercase()
    var cursor = 0
    return buildAnnotatedString {
        while (cursor < text.length) {
            val match = lower.indexOf(query.lowercase(), cursor)
            if (match < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, match))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x40FFEB3B)))
            append(text.substring(match, match + query.length))
            pop()
            cursor = match + query.length
        }
    }
}

@Composable
fun SearchScreen(
    dir: File,
    audioFiles: List<File>,
    liveSessions: List<SessionManifest>,
    onOpenAudio: (File) -> Unit,
    onOpenLive: (SessionManifest) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim()
    val hits by produceState(emptyList<SearchHit>(), normalized, dir.absolutePath, audioFiles, liveSessions) {
        value = if (normalized.length < 2) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                buildList {
                    audioFiles.forEach { audio ->
                        readSegments(sidecar(audio, "json")).forEach { segment ->
                            if (segment.matches(normalized) && size < 100) add(SearchHit(SearchSource.Audio(audio), segment))
                        }
                    }
                    liveSessions.forEach { session ->
                        readSegments(File(dir, "${session.baseName}.json")).forEach { segment ->
                            if (segment.matches(normalized) && size < 100) add(SearchHit(SearchSource.Live(session), segment))
                        }
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search Finnish or English") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        )

        when {
            normalized.isEmpty() -> SearchEmpty("Search every saved recording and live transcript")
            normalized.length < 2 -> SearchEmpty("Type at least two letters")
            hits.isEmpty() -> SearchEmpty("No Finnish or English matches")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(hits, key = { "${it.source.key}:${it.segment.startMs}:${it.segment.fi}" }) { hit ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            when (val source = hit.source) {
                                is SearchSource.Audio -> onOpenAudio(source.file)
                                is SearchSource.Live -> onOpenLive(source.session)
                            }
                        }.padding(vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(hit.source.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(relTime(hit.segment.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(highlightText(hit.segment.fi, normalized), style = MaterialTheme.typography.bodyLarge)
                        if (hit.segment.en.isNotBlank()) {
                            Text(
                                highlightText(hit.segment.en, normalized),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchEmpty(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Seg.matches(query: String): Boolean =
    fi.contains(query, ignoreCase = true) || en.contains(query, ignoreCase = true)
