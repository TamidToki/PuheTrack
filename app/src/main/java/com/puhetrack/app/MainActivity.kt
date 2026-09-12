package com.puhetrack.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.puhetrack.app.ui.theme.PuheTrackTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.models.ModelRepository
import com.puhetrack.app.processing.ProcessingCoordinator
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionState
import com.puhetrack.app.stt.TranslationKind
import com.puhetrack.app.ui.RecordingDetailScreen
import com.puhetrack.app.ui.RecordingsScreen
import com.puhetrack.app.ui.RecorderScreen
import com.puhetrack.app.ui.LiveTranscriptDetailScreen
import com.puhetrack.app.ui.SearchScreen
import com.puhetrack.app.ui.SettingsScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var service: RecordTranslateService? = null
    private var bound by mutableStateOf(false)
    private var pendingToggle by mutableStateOf(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RecordTranslateService.LocalBinder).service()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Empty callback = silent denial. Tell the user immediately what to
        // do instead of letting them discover it at Record time.
        if (grants[Manifest.permission.RECORD_AUDIO] == false) {
            toastState.value = "Microphone blocked — allow it in Settings → Apps → " +
                "PuheTrack → Permissions, then tap Record."
        } else if (grants[Manifest.permission.POST_NOTIFICATIONS] == false) {
            toastState.value = "Notifications off — recording still works, " +
                "but pause/stop buttons won't appear in the shade."
        }
    }
    private val toastState = mutableStateOf("")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestPerms()
        pendingToggle = TileToggleActivity.consume(this)

        // Bind only. The service promotes itself with startService() when a
        // recording starts (app is foreground then, so it's allowed), and
        // drops itself when idle+unbound. Starting it here crashes on
        // background launches (BackgroundServiceStartNotAllowedException).
        val intent = Intent(this, RecordTranslateService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)

        setContent {
            PuheTrackTheme {
                val store = remember { SettingsStore(applicationContext) }
                val recordingRepository = remember { RecordingRepository(applicationContext) }
                val uiScope = rememberCoroutineScope()
                var tab by remember { mutableIntStateOf(0) }
                var filesTick by remember { mutableIntStateOf(0) }
                var settingsTick by remember { mutableIntStateOf(0) }
                var selectedMode by remember {
                    mutableStateOf(
                        runCatching { RecordingMode.valueOf(store.recordingMode) }
                            .getOrDefault(RecordingMode.BATCH)
                    )
                }
                // Shared with the permission callback above so denials
                // surface immediately instead of at Record time.
                var toast by toastState
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(toast) {
                    if (toast.isNotBlank()) {
                        snackbarHostState.showSnackbar(toast)
                        toast = ""
                    }
                }
                var selectedAudio by remember { mutableStateOf<File?>(null) }
                var selectedLive by remember { mutableStateOf<SessionManifest?>(null) }
                val svc = service
                val isRecState = svc?.isRecording?.collectAsState()?.value ?: false
                val pausedState = svc?.isPaused?.collectAsState()?.value ?: false
                val statusState = svc?.status?.collectAsState()?.value ?: getString(R.string.starting)
                val levelState = svc?.level?.collectAsState()?.value ?: 0f
                val elapsedState = svc?.elapsedSec?.collectAsState()?.value ?: 0L
                val segmentState = svc?.segments?.collectAsState()?.value ?: emptyList()
                val heardState = svc?.heardSec?.collectAsState()?.value ?: 0L
                val translatingState = svc?.isTranslating?.collectAsState()?.value ?: false
                val formatState = svc?.formatLabel?.collectAsState()?.value ?: ""
                val sessionState = svc?.sessionState?.collectAsState()?.value ?: SessionState.IDLE
                val serviceMode = svc?.currentMode?.collectAsState()?.value ?: selectedMode
                val sessionActive = sessionState in setOf(
                    SessionState.STARTING,
                    SessionState.RECORDING,
                    SessionState.PAUSING,
                    SessionState.PAUSED,
                    SessionState.STOPPING
                )
                val displayedMode = if (sessionActive) serviceMode else selectedMode
                val isCurrentlyRecording = bound && isRecState
                val modelStatus = remember(tab, settingsTick) { ModelRepository.status(applicationContext) }
                val liveRecognitionAvailable = remember(tab) {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        SpeechRecognizer.isRecognitionAvailable(applicationContext)
                }
                val readiness = remember(displayedMode, tab, settingsTick, modelStatus, liveRecognitionAvailable) {
                    when {
                        displayedMode == RecordingMode.LIVE && !liveRecognitionAvailable ->
                            "Recorded Live Translate unavailable" to "This mode needs Android 13+ and a compatible speech-recognition service. 30-minute Recording is still available."
                        displayedMode == RecordingMode.LIVE ->
                            "Recorded Live Translate ready" to "The same microphone stream is saved as MP3 and sent to Finnish speech recognition. ${translationReadiness(store)}"
                        store.batchEngine == "OPENAI" && store.openaiKey.isBlank() ->
                            "Recording is ready; cloud processing needs a key" to "Audio will still be saved safely. Add an OpenAI key in Settings before retrying transcription."
                        store.batchEngine == "OPENAI" ->
                            "Cloud transcription ready" to "Each local MP3 chunk is closed before it is queued for OpenAI Whisper."
                        modelStatus is ModelRepository.Status.Ready ->
                            "Offline transcription ready" to "The pinned model is installed and verified; WAV chunks remain on this device."
                        modelStatus is ModelRepository.Status.Invalid ->
                            "Offline model needs repair" to "Recording is still available. Chunks will wait safely until the model is repaired in Settings."
                        else ->
                            "Offline model not installed" to "Recording is still available. Chunks wait safely until the ${ModelRepository.DISPLAY_SIZE} model is downloaded in Settings."
                    }
                }

                // Quick-tile toggle: runs once the service is bound
                LaunchedEffect(bound, pendingToggle) {
                    if (bound && pendingToggle) {
                        pendingToggle = false
                        svc?.let {
                            if (it.isRecording.value) it.stopSession() else it.startSession()
                        }
                        tab = 0
                    }
                }

                // File count for badge
                val dir = svc?.recordingsDir()
                    ?: (getExternalFilesDir("recordings") ?: File(filesDir, "recordings"))
                val fileCount = remember(dir, filesTick) {
                    dir.mkdirs()
                    val audioCount = recordingRepository.libraryAudioFiles().size
                    audioCount + recordingRepository.liveSessions().size
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        if (selectedAudio == null && selectedLive == null) {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("PuheTrack", style = MaterialTheme.typography.titleLarge)
                                        // Recording indicator
                                        AnimatedVisibility(
                                            visible = isCurrentlyRecording,
                                            enter = fadeIn(),
                                            exit = fadeOut()
                                        ) {
                                            RecordingIndicator()
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    },
                    bottomBar = {
                        if (selectedAudio == null && selectedLive == null) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 4.dp
                            ) {
                                val navItemColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = "Record",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = { Text("Record", fontWeight = if (tab == 0) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    selected = tab == 0,
                                    onClick = {
                                        // The tab IS the record button in users' minds:
                                        // tapping it while already here starts
                                        // recording instead of doing nothing.
                                        val s = svc
                                        if (tab == 0 && bound && s != null && !s.isRecording.value) {
                                            s.startSession()
                                        } else {
                                            tab = 0
                                        }
                                    },
                                    colors = navItemColors
                                )
                                NavigationBarItem(
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (fileCount > 0) {
                                                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$fileCount") }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_folder),
                                                contentDescription = "Files",
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    },
                                    label = { Text("Files", fontWeight = if (tab == 1) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    selected = tab == 1,
                                    onClick = { tab = 1; filesTick++ },
                                    colors = navItemColors
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            painterResource(R.drawable.ic_search),
                                            contentDescription = "Search",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = { Text("Search", fontWeight = if (tab == 2) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    colors = navItemColors
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            painterResource(R.drawable.ic_settings),
                                            contentDescription = "Settings",
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = { Text("Settings", fontWeight = if (tab == 3) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                                    selected = tab == 3,
                                    onClick = { tab = 3 },
                                    colors = navItemColors
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        val openLive = selectedLive
                        val open = selectedAudio
                        if (openLive != null) {
                            val liveSegments = remember(openLive, filesTick) {
                                recordingRepository.readTranscript(
                                    recordingRepository.sessionTranscriptFile(openLive)
                                )?.segments.orEmpty()
                            }
                            val liveAudio = remember(openLive, filesTick) {
                                recordingRepository.audioFilesForSession(openLive)
                            }
                            LiveTranscriptDetailScreen(
                                session = openLive,
                                directory = dir,
                                segments = liveSegments,
                                audioFiles = liveAudio,
                                onBack = { selectedLive = null; filesTick++ },
                                onShare = { shareTranscriptText(it) },
                                onShareAudio = { shareAudioFile(it) },
                                onDelete = { session ->
                                    uiScope.launch {
                                        val deleted = withContext(Dispatchers.IO) {
                                            recordingRepository.deleteLiveSession(session)
                                        }
                                        if (!deleted) toast = "Session is busy; try again when processing finishes"
                                        selectedLive = null
                                        filesTick++
                                    }
                                },
                                onRetryTranslation = { segmentId ->
                                    uiScope.launch {
                                        val queued = withContext(Dispatchers.IO) {
                                            ProcessingCoordinator.retryLiveTranslation(
                                                applicationContext,
                                                openLive,
                                                segmentId
                                            )
                                        }
                                        toast = if (queued) "Translation queued" else "Could not retry translation"
                                        filesTick++
                                    }
                                }
                            )
                        } else if (open != null) {
                            val openSession = remember(open, filesTick) {
                                recordingRepository.sessionForAudio(open)
                            }
                            RecordingDetailScreen(
                                audio = open,
                                displayTitle = openSession?.displayTitle
                                    ?: open.name.removePrefix("puhetrack-").substringBeforeLast("."),
                                settings = store,
                                onBack = { selectedAudio = null; filesTick++ },
                                onChanged = { filesTick++ },
                                onDelete = {
                                    val target = it
                                    uiScope.launch {
                                        val deleted = withContext(Dispatchers.IO) {
                                            recordingRepository.deleteAudioFamily(target)
                                        }
                                        if (!deleted) toast = "Recording is busy; try again when processing finishes"
                                        filesTick++
                                    }
                                    selectedAudio = null
                                },
                                onRename = { title ->
                                    val session = openSession
                                    if (session == null) false else withContext(Dispatchers.IO) {
                                        recordingRepository.renameSession(session.sessionId, title)
                                    }
                                }
                            )
                        } else when (tab) {
                            0 -> {
                                RecorderScreen(
                                    mode = displayedMode,
                                    sessionState = sessionState,
                                    modeChangeEnabled = !sessionActive,
                                    startEnabled = bound && svc != null &&
                                        (displayedMode != RecordingMode.LIVE || liveRecognitionAvailable),
                                    readinessTitle = readiness.first,
                                    readinessDetail = readiness.second,
                                    isRecording = isRecState,
                                    isPaused = pausedState,
                                    status = statusState,
                                    level = levelState,
                                    elapsedSec = elapsedState,
                                    heardSec = heardState,
                                    formatLabel = formatState,
                                    segments = segmentState,
                                    isTranslating = translatingState,
                                    onModeChange = { mode ->
                                        if (!sessionActive) {
                                            selectedMode = mode
                                            store.recordingMode = mode.name
                                        }
                                    },
                                    onStart = { svc?.startSession() },
                                    onPauseResume = { svc?.let { if (pausedState) it.resumeSession() else it.pauseSession() } },
                                    onStop = { svc?.stopSession() },
                                    onShare = { shareTranscriptText(svc?.shareableFile()) },
                                    onRetryTranslation = { id -> svc?.retryTranslation(id) }
                                )
                            }
                            1 -> {
                                @Suppress("UNUSED_EXPRESSION")
                                filesTick
                                val files = remember(dir, filesTick) {
                                    dir.mkdirs()
                                    recordingRepository.libraryAudioFiles()
                                }
                                val liveSessions = remember(dir, filesTick) {
                                    recordingRepository.liveSessions()
                                }
                                val processing = remember(files, filesTick) {
                                    files.mapNotNull { audio ->
                                        recordingRepository.manifestForAudio(audio)?.second
                                            ?.let { audio.absolutePath to it }
                                    }.toMap()
                                }
                                val titles = remember(files, filesTick) {
                                    files.mapNotNull { audio ->
                                        recordingRepository.sessionForAudio(audio)?.displayTitle
                                            ?.let { audio.absolutePath to it }
                                    }.toMap()
                                }
                                RecordingsScreen(
                                    directory = dir,
                                    files = files,
                                    liveSessions = liveSessions,
                                    processing = processing,
                                    titles = titles,
                                    onOpen = { selectedAudio = it },
                                    onOpenLive = { selectedLive = it },
                                    onDelete = { f ->
                                        uiScope.launch {
                                            val deleted = withContext(Dispatchers.IO) {
                                                recordingRepository.deleteAudioFamily(f)
                                            }
                                            if (!deleted) toast = "Recording is busy; try again when processing finishes"
                                            filesTick++
                                        }
                                    },
                                    onDeleteLive = { session ->
                                        uiScope.launch {
                                            val deleted = withContext(Dispatchers.IO) {
                                                recordingRepository.deleteLiveSession(session)
                                            }
                                            if (!deleted) toast = "Session is busy; try again when processing finishes"
                                            filesTick++
                                        }
                                    },
                                    onDeleteAll = {
                                        uiScope.launch {
                                            withContext(Dispatchers.IO) {
                                                files.forEach { recordingRepository.deleteAudioFamily(it) }
                                                liveSessions.forEach { runCatching { recordingRepository.deleteLiveSession(it) } }
                                            }
                                            filesTick++
                                        }
                                    },
                                    onShareAudio = { shareAudioFile(it) },
                                    onShareText = { shareTranscriptText(it) },
                                    onRetryProcessing = { manifest, confirmed ->
                                        uiScope.launch {
                                            val queued = withContext(Dispatchers.IO) {
                                                ProcessingCoordinator.manualRetry(
                                                    applicationContext,
                                                    manifest.chunkId,
                                                    confirmed
                                                )
                                            }
                                            toast = if (queued) "Processing queued" else "Could not queue this chunk"
                                            filesTick++
                                        }
                                    }
                                )
                            }
                            2 -> {
                                dir.mkdirs()
                                val searchableLiveSessions = remember(dir, filesTick) {
                                    recordingRepository.liveSessions()
                                }
                                SearchScreen(
                                    dir = dir,
                                    audioFiles = recordingRepository.libraryAudioFiles(),
                                    liveSessions = searchableLiveSessions,
                                    onOpenAudio = { selectedAudio = it },
                                    onOpenLive = { selectedLive = it }
                                )
                            }
                            else -> SettingsScreen(store = store, onSaved = {
                                settingsTick++
                                toast = it
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (TileToggleActivity.consume(this)) pendingToggle = true
    }

    private fun shareTranscriptText(audio: File?) {
        if (audio == null) return
        val txt = if (audio.extension.equals("txt", ignoreCase = true)) {
            audio
        } else {
            File(audio.parent, audio.nameWithoutExtension + ".txt")
        }
        if (!txt.isFile) {
            toastState.value = "No transcript is available yet"
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", txt)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share transcript"))
    }

    private fun shareAudioFile(audio: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", audio)
        val mime = if (audio.extension.lowercase() == "mp3") "audio/mpeg" else "audio/x-wav"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share recording"))
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

private fun translationReadiness(store: SettingsStore): String = when (store.translation) {
    TranslationKind.MLKIT -> "English translation runs on-device once ML Kit has its language model."
    TranslationKind.GOOGLE -> if (store.googleKey.isBlank()) {
        "Finnish text is saved first; add a Google key in Settings to translate it."
    } else {
        "English translation uses the selected Google cloud service."
    }
    TranslationKind.DEEPL -> if (store.deeplKey.isBlank()) {
        "Finnish text is saved first; add a DeepL key in Settings to translate it."
    } else {
        "English translation uses the selected DeepL cloud service."
    }
}

/** Pulsing red dot that indicates active recording, shown in the top bar. */
@Composable
private fun RecordingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "rec-pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rec-alpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "REC",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp
        )
    }
}
