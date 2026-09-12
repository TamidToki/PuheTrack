package com.puhetrack.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.puhetrack.app.audio.BatchCaptureController
import com.puhetrack.app.audio.ChunkClock
import com.puhetrack.app.audio.StoragePolicy
import com.puhetrack.app.data.CanonicalSegment
import com.puhetrack.app.data.RecordingRepository
import com.puhetrack.app.data.SessionLifecycle
import com.puhetrack.app.data.SessionManifest
import com.puhetrack.app.data.TranslationState
import com.puhetrack.app.processing.ProcessingCoordinator
import com.puhetrack.app.session.RecordingMode
import com.puhetrack.app.session.SessionConfig
import com.puhetrack.app.session.SessionState
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.GoogleLiveEngine
import com.puhetrack.app.stt.TranscriptSegment
import com.puhetrack.app.translate.FinnishTranslit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RecordTranslateService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): RecordTranslateService = this@RecordTranslateService
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, failure ->
        Log.e(TAG, "Background operation failed: ${failure::class.java.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private lateinit var repository: RecordingRepository

    private var captureJob: Job? = null
    private var tickerJob: Job? = null
    private var batchController: BatchCaptureController? = null
    private var liveEngine: GoogleLiveEngine? = null
    @Volatile private var liveGeneration: String? = null
    private val livePersistenceJobs = ConcurrentHashMap.newKeySet<Job>()
    private var livePersistenceFailed = AtomicBoolean(false)
    private val pendingChunkIds = ConcurrentHashMap.newKeySet<String>()
    private val liveStableIds = ConcurrentHashMap<Long, String>()
    private val batchUiIds = ConcurrentHashMap<Long, String>()
    private val idCounter = AtomicLong(System.currentTimeMillis())
    private var finishOnce = AtomicBoolean(false)

    private var currentSession: SessionManifest? = null
    private var currentAudio: File? = null
    private var sessionStartElapsed = 0L
    private var pausedAccumMs = 0L
    private var pauseStartElapsed = 0L
    private var partialStartMs = 0L
    private var lastLiveEndMs = 0L
    private var stopRequested = false
    private var autoPaused = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState
    private val _currentMode = MutableStateFlow(RecordingMode.BATCH)
    val currentMode: StateFlow<RecordingMode> = _currentMode
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused
    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status
    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec
    private val _heardSec = MutableStateFlow(0L)
    val heardSec: StateFlow<Long> = _heardSec
    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating
    private val _formatLabel = MutableStateFlow("")
    val formatLabel: StateFlow<String> = _formatLabel

    override fun onCreate() {
        super.onCreate()
        repository = RecordingRepository(this)
        createChannel()
        scope.launch(Dispatchers.IO) {
            val report = repository.recover()
            if (report.recoveredWav > 0 || report.recoveredMp3 > 0 || report.resetProcessing > 0 ||
                report.importedLegacy > 0 || report.interruptedSessions > 0
            ) {
                Log.i(TAG, "Recovery completed: wav=${report.recoveredWav}, mp3=${report.recoveredMp3}, reset=${report.resetProcessing}, legacy=${report.importedLegacy}, sessions=${report.interruptedSessions}")
            }
            ProcessingCoordinator.reconcileAndEnqueue(applicationContext)
        }
        scope.launch {
            ProcessingCoordinator.events.collect { event ->
                pendingChunkIds.remove(event.chunkId)
                _isTranslating.value = pendingChunkIds.isNotEmpty()
                val file = repository.findManifest(event.chunkId) ?: return@collect
                val manifest = runCatching { repository.readManifest(file) }.getOrNull() ?: return@collect
                if (manifest.sessionId != currentSession?.sessionId) return@collect
                val rendered = event.segments.map { segment ->
                    val uiId = stableLong(segment.segmentId)
                    batchUiIds[uiId] = event.chunkId
                    TranscriptSegment(
                        id = uiId,
                        textFi = segment.textFi,
                        textEn = segment.textEn.orEmpty(),
                        textTranslit = FinnishTranslit.transliterate(segment.textFi),
                        isFinal = true,
                        startMs = manifest.sessionActiveOffsetMs + segment.startMs,
                        translationUnavailable = segment.translationState == TranslationState.UNAVAILABLE
                    )
                }
                _segments.value = _segments.value + rendered
            }
        }
        scope.launch {
            ProcessingCoordinator.liveEvents.collect { event ->
                if (event.sessionId != currentSession?.sessionId) return@collect
                val uiId = liveStableIds.entries.firstOrNull { it.value == event.segment.segmentId }?.key
                    ?: return@collect
                _segments.value = _segments.value.map { existing ->
                    if (existing.id != uiId) existing
                    else existing.copy(
                        textEn = event.segment.textEn ?: "Translation unavailable",
                        translationUnavailable = event.segment.translationState == TranslationState.UNAVAILABLE
                    )
                }
                _isTranslating.value = _segments.value.any {
                    it.isFinal && it.textEn == "Translating…"
                } || pendingChunkIds.isNotEmpty()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_PAUSE_RESUME -> if (_isPaused.value) resumeSession() else pauseSession()
            ACTION_TOGGLE -> if (isSessionActive()) stopSession() else startSession()
            null -> if (!running) stopSelf()
        }
        // A killed capture cannot honestly be resumed. Durable recovery handles its open chunk.
        return START_NOT_STICKY
    }

    @Synchronized
    fun startSession() {
        if (isSessionActive() || running) {
            _status.value = "A session is already active"
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.value = "Microphone permission is required"
            return
        }
        val config = SessionConfig.from(SettingsStore(this))
        if (!StoragePolicy.canStart(config, repository.root.usableSpace)) {
            _sessionState.value = SessionState.FAILED
            _status.value = "Not enough free storage to start safely"
            return
        }
        if (config.mode == RecordingMode.LIVE && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _sessionState.value = SessionState.FAILED
            _status.value = "Recorded Live Translate requires Android 13 or newer"
            return
        }
        if (config.mode == RecordingMode.LIVE && !SpeechRecognizer.isRecognitionAvailable(this)) {
            _sessionState.value = SessionState.FAILED
            _status.value = "Live speech recognition is unavailable on this device"
            return
        }

        resetForNewSession(config)
        try {
            startService(Intent(this, RecordTranslateService::class.java))
            startForegroundCompat("Preparing ${modeLabel(config.mode)}…", active = true)
        } catch (_: Throwable) {
            running = false
            _sessionState.value = SessionState.FAILED
            _status.value = "Android did not allow the recording service to start"
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val session = repository.createSession(config)
                currentSession = session
                if (stopRequested) {
                    finishSession(SessionLifecycle.STOPPED, SessionState.SAVED, "Stopped before capture started")
                    return@launch
                }
                acquireWakeLock()
                requestAudioFocus()
                if (config.mode == RecordingMode.BATCH) beginBatch(session) else beginLive(session)
                startTicker()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failSession("Could not start safely")
            }
        }
    }

    private fun resetForNewSession(config: SessionConfig) {
        finishOnce = AtomicBoolean(false)
        stopRequested = false
        autoPaused = false
        currentSession = null
        currentAudio = null
        batchController = null
        liveEngine = null
        liveGeneration = null
        livePersistenceJobs.clear()
        livePersistenceFailed = AtomicBoolean(false)
        pendingChunkIds.clear()
        liveStableIds.clear()
        batchUiIds.clear()
        sessionStartElapsed = SystemClock.elapsedRealtime()
        pausedAccumMs = 0
        pauseStartElapsed = 0
        partialStartMs = 0
        lastLiveEndMs = 0
        _currentMode.value = config.mode
        _sessionState.value = SessionState.STARTING
        _isRecording.value = false
        _isPaused.value = false
        _segments.value = emptyList()
        _level.value = 0f
        _elapsedSec.value = 0
        _heardSec.value = 0
        _isTranslating.value = false
        _formatLabel.value = if (config.mode == RecordingMode.LIVE) {
            "MP3 · ${config.bitrateKbps} kbps · 30-minute safe chunks"
        } else {
            if (config.batchEngine.name == "OFFLINE") "WAV · 16 kHz · mono" else "MP3 · ${config.bitrateKbps} kbps"
        }
        _status.value = "Preparing ${modeLabel(config.mode)}…"
        running = true
    }

    private fun beginBatch(session: SessionManifest) {
        val controller = BatchCaptureController(
            context = applicationContext,
            repository = repository,
            session = session,
            callbacks = object : BatchCaptureController.Callbacks {
                override fun onCaptureStarted(formatLabel: String) {
                    if (stopRequested) {
                        batchController?.requestStop("stopped_during_start")
                        return
                    }
                    _formatLabel.value = formatLabel
                    _sessionState.value = SessionState.RECORDING
                    _isRecording.value = true
                    _status.value = "Recording chunk 1 · 00:00 / 30:00"
                    startForegroundCompat(_status.value, active = true)
                }

                override fun onLevel(level: Float) { _level.value = level }

                override fun onProgress(totalFrames: Long, chunkSequence: Int, chunkFrames: Long) {
                    _heardSec.value = totalFrames / ChunkClock.SAMPLE_RATE
                    val seconds = chunkFrames / ChunkClock.SAMPLE_RATE
                    _status.value = "Recording chunk $chunkSequence · ${clock(seconds)} / 30:00"
                }

                override fun onChunkClosed(manifestFile: File, manifest: com.puhetrack.app.data.ChunkManifest) {
                    currentAudio = File(repository.root, manifest.audioFileName)
                    pendingChunkIds += manifest.chunkId
                    _isTranslating.value = true
                    ProcessingCoordinator.enqueue(applicationContext, manifestFile)
                }

                override fun onPauseAcknowledged(paused: Boolean) {
                    _isPaused.value = paused
                    _sessionState.value = if (paused) SessionState.PAUSED else SessionState.RECORDING
                    _status.value = if (paused) "Paused · captured time is preserved" else "Recording resumed"
                    startForegroundCompat(_status.value, active = true)
                }
            }
        )
        batchController = controller
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                controller.run()
                finishSession(
                    SessionLifecycle.STOPPED,
                    SessionState.SAVED,
                    if (pendingChunkIds.isEmpty()) "Recording saved" else "Recording saved · processing continues"
                )
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failSession("Recording stopped safely after an audio error")
            }
        }
    }

    private suspend fun beginLive(session: SessionManifest) = withContext(Dispatchers.Main) {
        val generation = UUID.randomUUID().toString()
        liveGeneration = generation
        liveEngine = GoogleLiveEngine(
            context = applicationContext,
            onPartial = { text -> handleLivePartial(generation, text) },
            onFinal = { text -> handleLiveFinal(generation, text) },
            onError = { error ->
                if (currentSession?.sessionId == session.sessionId && !stopRequested) _status.value = error
            },
            onListeningState = { listening ->
                if (currentSession?.sessionId != session.sessionId || stopRequested || _isPaused.value) return@GoogleLiveEngine
                if (listening) {
                    _status.value = "Recording audio · Live Finnish → English"
                } else if (_sessionState.value == SessionState.RECORDING) {
                    _status.value = "Recording audio · reconnecting live text…"
                }
            }
        ).also { engine ->
            _status.value = "Preparing recorded Live Translate…"
            engine.start()
        }

        val controller = BatchCaptureController(
            context = applicationContext,
            repository = repository,
            session = session,
            callbacks = object : BatchCaptureController.Callbacks {
                override fun onCaptureStarted(formatLabel: String) {
                    if (stopRequested) {
                        batchController?.requestStop("stopped_during_start")
                        return
                    }
                    _formatLabel.value = formatLabel + " · 30-minute safe chunks"
                    _sessionState.value = SessionState.RECORDING
                    _isRecording.value = true
                    _status.value = "Recording audio · Live Finnish → English"
                    startForegroundCompat(_status.value, active = true)
                }

                override fun onLevel(level: Float) { _level.value = level }

                override fun onProgress(totalFrames: Long, chunkSequence: Int, chunkFrames: Long) {
                    _heardSec.value = totalFrames / ChunkClock.SAMPLE_RATE
                }

                override fun onPcm(buffer: ByteArray, byteCount: Int) {
                    liveEngine?.offerPcm(buffer, byteCount)
                }

                override fun onChunkClosed(
                    manifestFile: File,
                    manifest: com.puhetrack.app.data.ChunkManifest
                ) {
                    currentAudio = File(repository.root, manifest.audioFileName)
                }

                override fun onPauseAcknowledged(paused: Boolean) {
                    _isPaused.value = paused
                    _sessionState.value = if (paused) SessionState.PAUSED else SessionState.RECORDING
                    _status.value = if (paused) {
                        "Paused · captured audio and text are preserved"
                    } else {
                        "Recording audio · Live Finnish → English"
                    }
                    startForegroundCompat(_status.value, active = true)
                }
            }
        )
        batchController = controller
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                controller.run()
                livePersistenceJobs.toList().forEach { it.join() }
                if (livePersistenceFailed.get()) {
                    finishSession(
                        SessionLifecycle.FAILED,
                        SessionState.FAILED,
                        "Audio saved · some transcript text could not be saved"
                    )
                } else {
                    finishSession(
                        SessionLifecycle.STOPPED,
                        SessionState.SAVED,
                        "Audio and transcript saved"
                    )
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failSession("Recording stopped safely after an audio error")
            }
        }
    }

    private fun handleLivePartial(generation: String, text: String) {
        currentSession ?: return
        if (liveGeneration != generation) return
        if (stopRequested || _isPaused.value || !_isRecording.value) return
        val current = _segments.value.toMutableList()
        if (current.lastOrNull()?.isFinal == false) {
            current[current.lastIndex] = current.last().copy(textFi = text)
        } else {
            partialStartMs = activeElapsedMs()
            current += TranscriptSegment(
                id = idCounter.incrementAndGet(),
                textFi = text,
                textEn = "",
                isFinal = false,
                startMs = partialStartMs
            )
        }
        _segments.value = current
    }

    private fun handleLiveFinal(generation: String, text: String) {
        val session = currentSession ?: return
        if (liveGeneration != generation || stopRequested || _isPaused.value || text.isBlank()) return
        val id = idCounter.incrementAndGet()
        val stableId = UUID.randomUUID().toString()
        liveStableIds[id] = stableId
        val now = activeElapsedMs()
        val hadPartial = _segments.value.lastOrNull()?.isFinal == false
        val start = (if (hadPartial) partialStartMs else lastLiveEndMs).coerceAtMost(now)
        val end = maxOf(now, start + 1)
        lastLiveEndMs = end
        val finalSegment = TranscriptSegment(
            id = id,
            textFi = text,
            textEn = "Translating…",
            textTranslit = FinnishTranslit.transliterate(text),
            isFinal = true,
            startMs = start
        )
        _segments.value = _segments.value.filter { it.isFinal } + finalSegment
        val canonical = CanonicalSegment(
            segmentId = stableId,
            startMs = start,
            endMs = end,
            textFi = text,
            timingProvenance = "live_utterance_approximate"
        )
        _isTranslating.value = true
        val persistenceJob = scope.launch(Dispatchers.IO) {
            repository.appendLiveFinnish(session, canonical)
            // The Finnish commit is the irreplaceable operation. If queue insertion fails,
            // startup reconciliation will rediscover the pending translation.
            runCatching {
                ProcessingCoordinator.enqueueLiveTranslation(applicationContext, session, canonical.segmentId)
            }.onFailure { failure ->
                Log.w(TAG, "Live translation was saved for later queue reconciliation", failure)
            }
        }
        livePersistenceJobs += persistenceJob
        persistenceJob.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) livePersistenceFailed.set(true)
            livePersistenceJobs.remove(persistenceJob)
        }
    }

    fun pauseSession(auto: Boolean = false) {
        if (!isSessionActive() || _isPaused.value || _sessionState.value == SessionState.STOPPING) return
        autoPaused = auto
        pauseStartElapsed = SystemClock.elapsedRealtime()
        _sessionState.value = SessionState.PAUSING
        _status.value = "Pausing…"
        if (_currentMode.value == RecordingMode.LIVE) {
            liveEngine?.pause()
            _isPaused.value = true
            _segments.value = _segments.value.filter { it.isFinal }
            batchController?.requestPause()
            _status.value = if (auto) "Pausing for an audio interruption…" else "Pausing…"
        } else {
            batchController?.requestPause()
        }
        currentSession?.let { session ->
            scope.launch(Dispatchers.IO) { currentSession = repository.updateSession(session, SessionLifecycle.PAUSED) }
        }
        startForegroundCompat(_status.value, active = true)
    }

    fun resumeSession() {
        if (!_isPaused.value || _sessionState.value == SessionState.STOPPING) return
        pausedAccumMs += SystemClock.elapsedRealtime() - pauseStartElapsed
        autoPaused = false
        _status.value = "Resuming…"
        if (_currentMode.value == RecordingMode.LIVE) {
            _isPaused.value = false
            _sessionState.value = SessionState.STARTING
            liveEngine?.resume()
            batchController?.requestResume()
        } else {
            batchController?.requestResume()
        }
        currentSession?.let { session ->
            scope.launch(Dispatchers.IO) { currentSession = repository.updateSession(session, SessionLifecycle.ACTIVE) }
        }
    }

    @Synchronized
    fun stopSession() {
        if (!isSessionActive() && !running) return
        if (_sessionState.value == SessionState.STOPPING) return
        stopRequested = true
        _sessionState.value = SessionState.STOPPING
        _isRecording.value = false
        _isPaused.value = false
        _level.value = 0f
        _status.value = "Saving captured data…"
        tickerJob?.cancel()
        tickerJob = null
        if (_currentMode.value == RecordingMode.BATCH) {
            batchController?.requestStop()
            if (batchController == null) {
                scope.launch(Dispatchers.IO) {
                    finishSession(SessionLifecycle.STOPPED, SessionState.SAVED, "Stopped before capture started")
                }
            }
        } else {
            liveGeneration = null
            liveEngine?.stop()
            batchController?.requestStop()
            if (batchController == null) {
                scope.launch(Dispatchers.IO) {
                    finishSession(SessionLifecycle.STOPPED, SessionState.SAVED, "Stopped before capture started")
                }
            }
        }
    }

    fun retryTranslation(segmentId: Long) {
        val liveId = liveStableIds[segmentId]
        val session = currentSession
        if (liveId != null && session != null) {
            _segments.value = _segments.value.map {
                if (it.id == segmentId) it.copy(textEn = "Translating…", translationUnavailable = false) else it
            }
            _isTranslating.value = true
            scope.launch(Dispatchers.IO) {
                ProcessingCoordinator.retryLiveTranslation(applicationContext, session, liveId)
            }
            return
        }
        batchUiIds[segmentId]?.let { chunkId ->
            scope.launch(Dispatchers.IO) { ProcessingCoordinator.retryTranslation(applicationContext, chunkId) }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isSessionActive()) {
                _elapsedSec.value = activeElapsedMs() / 1000
                delay(1_000)
            }
        }
    }

    private suspend fun failSession(message: String) {
        finishSession(SessionLifecycle.FAILED, SessionState.FAILED, message)
    }

    private suspend fun finishSession(
        lifecycle: SessionLifecycle,
        finalState: SessionState,
        message: String
    ) {
        if (!finishOnce.compareAndSet(false, true)) return
        tickerJob?.cancel()
        tickerJob = null
        currentSession?.let { session ->
            runCatching { currentSession = repository.updateSession(session, lifecycle) }
        }
        liveEngine?.stop()
        liveEngine = null
        batchController = null
        abandonAudioFocus()
        releaseWakeLock()
        _isRecording.value = false
        _isPaused.value = false
        _level.value = 0f
        _sessionState.value = finalState
        _status.value = message
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isSessionActive(): Boolean = _sessionState.value in setOf(
        SessionState.STARTING,
        SessionState.RECORDING,
        SessionState.PAUSING,
        SessionState.PAUSED,
        SessionState.STOPPING
    )

    private fun activeElapsedMs(): Long {
        val pausedNow = if (_isPaused.value) SystemClock.elapsedRealtime() - pauseStartElapsed else 0L
        return (SystemClock.elapsedRealtime() - sessionStartElapsed - pausedAccumMs - pausedNow).coerceAtLeast(0)
    }

    fun audioFile(): File? = currentAudio
    fun shareableFile(): File? {
        val session = currentSession ?: return currentAudio
        return if (session.config.mode == RecordingMode.LIVE) {
            File(repository.root, "${session.baseName}.txt").takeIf { it.exists() }
        } else currentAudio
    }
    fun recordingsDir(): File = repository.root

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PuheTrack:capture")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseSession(auto = true)
                        AudioManager.AUDIOFOCUS_GAIN -> if (autoPaused) resumeSession()
                    }
                }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun abandonAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(text: String, active: Boolean) {
        val notification = buildNotification(text, active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String, active: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("PuheTrack")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
        if (active) {
            val pause = PendingIntent.getService(
                this,
                1,
                Intent(this, RecordTranslateService::class.java).setAction(ACTION_PAUSE_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getService(
                this,
                2,
                Intent(this, RecordTranslateService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, if (_isPaused.value) "Resume" else "Pause", pause)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
        }
        return builder.build()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!running) stopSelf()
        return false
    }

    override fun onDestroy() {
        batchController?.requestStop("service_destroyed")
        liveGeneration = null
        liveEngine?.stop()
        tickerJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        abandonAudioFocus()
        running = false
        super.onDestroy()
    }

    private fun stableLong(value: String): Long =
        value.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }.let { if (it == Long.MIN_VALUE) 0 else kotlin.math.abs(it) }

    private fun clock(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun modeLabel(mode: RecordingMode): String =
        if (mode == RecordingMode.LIVE) "Live Translate" else "30-minute Recording"

    companion object {
        private const val TAG = "PuheTrack"
        private const val CHANNEL_ID = "rec"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        const val CHUNK_DURATION_MS = 30L * 60 * 1000
        const val ACTION_STOP = "com.puhetrack.app.STOP"
        const val ACTION_PAUSE_RESUME = "com.puhetrack.app.PAUSE_RESUME"
        const val ACTION_TOGGLE = "com.puhetrack.app.TOGGLE"

        @Volatile var running = false
            private set
    }
}
