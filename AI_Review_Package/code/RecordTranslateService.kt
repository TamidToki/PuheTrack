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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.puhetrack.app.audio.Mp3Encoder
import com.puhetrack.app.audio.Mp3Writer
import com.puhetrack.app.audio.WavWriter
import com.puhetrack.app.audio.PcmSink
import com.puhetrack.app.batch.OpenAIBatch
import com.puhetrack.app.data.Seg
import com.puhetrack.app.data.relTime
import com.puhetrack.app.data.sidecar
import com.puhetrack.app.data.writeSegmentsJson
import com.puhetrack.app.data.writeSrt as writeSrtFileShared
import com.puhetrack.app.export.PdfExport
import com.puhetrack.app.settings.SettingsStore
import com.puhetrack.app.stt.TranscriptSegment
import com.puhetrack.app.translate.FiEnTranslator
import com.puhetrack.app.translate.FinnishTranslit
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordTranslateService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): RecordTranslateService = this@RecordTranslateService
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val batchProcessingMutex = kotlinx.coroutines.sync.Mutex()
    private var audioJob: Job? = null
    private var liveJob: Job? = null
    private var tickerJob: Job? = null
    
    private var liveEngine: com.puhetrack.app.stt.GoogleLiveEngine? = null

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var sink: PcmSink? = null
    @Volatile private var currentAudio: File? = null
    private var translator = FiEnTranslator()
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private var sessionStartElapsed = 0L
    private var pausedAccumMs = 0L
    private var pauseStartElapsed = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    val segments: StateFlow<List<TranscriptSegment>> = _segments

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec

    private var heardMs = 0L
    private val _heardSec = MutableStateFlow(0L)
    val heardSec: StateFlow<Long> = _heardSec

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating

    private val _formatLabel = MutableStateFlow("")
    val formatLabel: StateFlow<String> = _formatLabel

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (_isRecording.value) stopSession() else stopSelf()
            ACTION_PAUSE_RESUME -> if (_isPaused.value) resumeSession() else pauseSession(auto = false)
            ACTION_TOGGLE -> if (_isRecording.value) stopSession() else startSession()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch(Dispatchers.IO) {
            try { translator.ensureModel() } catch (_: Exception) {}
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!_isRecording.value) stopSelf()
        return false
    }

    fun startSession() {
        if (_isRecording.value || running) {
            _status.value = "finishing previous save… tap Record again in a second"
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _status.value = "mic permission missing — allow microphone and retry"
            return
        }
        try {
            startService(Intent(this, RecordTranslateService::class.java))
        } catch (e: Exception) {
            _status.value = "could not start background recording: ${e.message}"
            return
        }
        val settings = SettingsStore(this)
        val dir = getExternalFilesDir("recordings")
        if (dir == null) {
            _status.value = "storage unavailable"
            return
        }
        
        
        sessionStartElapsed = SystemClock.elapsedRealtime()
        _isTranslating.value = false
        pausedAccumMs = 0L
        heardMs = 0L
        _heardSec.value = 0L
        _segments.value = emptyList()
        _elapsedSec.value = 0L

        acquireWakeLock()
        requestAudioFocus()

        _isRecording.value = true
        running = true
        _isPaused.value = false
        
        startForeground(NOTIF_ID, buildNotification(getString(R.string.recording), recording = true))
        
        val store = SettingsStore(this)
        _formatLabel.value = if (store.batchEngine == "OPENAI") "MP3 + Whisper" else "WAV + Sherpa"
        if (store.recordingMode == "LIVE") {
            liveJob = scope.launch(Dispatchers.Main) { startLiveLoop() }
            audioJob = scope.launch(Dispatchers.IO) { liveAudioLoop() }
        } else {
            audioJob = scope.launch(Dispatchers.IO) { audioLoop() }
        }
        
        tickerJob = scope.launch {
            while (_isRecording.value) {
                delay(1000)
                _elapsedSec.value = activeElapsedMs() / 1000
            }
        }
    }

    fun pauseSession(auto: Boolean = false) {
        if (!_isRecording.value || _isPaused.value) return
        _isPaused.value = true
        pauseStartElapsed = SystemClock.elapsedRealtime()
        try { recorder?.stop() } catch (_: Exception) {}
        _status.value = if (auto) "paused (call / audio interruption)" else "paused"
        startForeground(NOTIF_ID, buildNotification(_status.value, recording = true))
    }

    fun resumeSession() {
        if (!_isRecording.value || !_isPaused.value) return
        pausedAccumMs += SystemClock.elapsedRealtime() - pauseStartElapsed
        try {
            recorder?.startRecording()
            _isPaused.value = false
        } catch (e: Exception) {
            _status.value = "mic resume failed: ${e.message}"
            return
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.recording), recording = true))
    }

    fun stopSession() {
        if (!_isRecording.value) return
        _isRecording.value = false
        _isPaused.value = false
        _status.value = "saving…"
        
        liveEngine?.stop()
        
        scope.launch(Dispatchers.IO) {
            try { audioJob?.join() } catch (_: Exception) {}
            audioJob = null
            try { liveJob?.join() } catch (_: Exception) {}
            liveJob = null
            tickerJob?.cancel()
            tickerJob = null
            recorder = null
            
            abandonAudioFocus()
            releaseWakeLock()

            _status.value = "saved"
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun liveAudioLoop() {
        val sampleRate = 16000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding).coerceAtLeast(4096)
        val buffer = ByteArray(minBuf)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channel, encoding, minBuf * 4
        )
        recorder = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "live mic init failed")
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "live mic start failed: ${e.message}")
            return
        }

        val dir = getExternalFilesDir("recordings")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        currentAudio = File(dir, "puhetrack-live-$stamp.mp3")
        sink = Mp3Writer(currentAudio!!, bitrateKbps = 64)
        
        try { sink?.start() } catch (e: Exception) {
            return
        }

        while (_isRecording.value) {
            if (_isPaused.value) {
                Thread.sleep(120)
                continue
            }
            val read = rec.read(buffer, 0, buffer.size)
            if (read > 0) {
                sink?.writeSamples(buffer, read)
            }
        }

        sink?.stop()
        try { rec.stop() } catch(_: Exception) {}
        rec.release()
    }

    private var partialTranslateJob: Job? = null

    private fun startLiveLoop() {
        _formatLabel.value = "Live Translate Mode"
        liveEngine = com.puhetrack.app.stt.GoogleLiveEngine(this,
            onPartial = { text ->
                val partialId = if (_segments.value.isNotEmpty() && !_segments.value.last().isFinal) {
                    _segments.value.last().id
                } else {
                    System.currentTimeMillis()
                }

                val current = _segments.value.toMutableList()
                if (current.isNotEmpty() && !current.last().isFinal) {
                    current[current.lastIndex] = current.last().copy(textFi = text)
                } else {
                    current.add(TranscriptSegment(id = partialId, textFi = text, textEn = "", isFinal = false, startMs = activeElapsedMs()))
                }
                _segments.value = current

                partialTranslateJob?.cancel()
                partialTranslateJob = scope.launch {
                    try {
                        val enText = translator.translate(text)
                        val updated = _segments.value.map { if (it.id == partialId) it.copy(textEn = enText) else it }
                        _segments.value = updated
                    } catch (_: Exception) {}
                }
            },
            onFinal = { text ->
                val finalId = System.currentTimeMillis()
                val current = _segments.value.toMutableList()
                if (current.isNotEmpty() && !current.last().isFinal) {
                    current.removeAt(current.lastIndex)
                }
                val finalSeg = TranscriptSegment(id = finalId, textFi = text, textEn = "Translating...", isFinal = true, startMs = activeElapsedMs())
                current.add(finalSeg)
                _segments.value = current
                
                scope.launch {
                    try {
                        val fiText = text
                        val enText = translator.translate(fiText)
                        val updated = _segments.value.map { if (it.id == finalId) it.copy(textEn = enText) else it }
                        _segments.value = updated
                        appendTranscriptFile(fiText, enText, finalSeg.startMs)
                        writeJsonFile()
                        writeSrtFile()
                        rewriteTxtFile()
                    } catch (e: Exception) {
                        Log.e(TAG, "Live translate failed", e)
                        val updated = _segments.value.map { if (it.id == finalId) it.copy(textEn = "Error", translationUnavailable = true) else it }
                        _segments.value = updated
                    }
                }
            },
            onError = { err ->
                _status.value = "Live: $err"
            }
        )
        liveEngine?.start()
    }

    private fun audioLoop() {
        val sampleRate = 16000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding).coerceAtLeast(4096)
        val buffer = ByteArray(minBuf)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channel, encoding, minBuf * 4
        )
        recorder = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            _status.value = "mic init failed"
            return
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            _status.value = "mic start failed: ${e.message}"
            return
        }

        var chunkStartMs = SystemClock.elapsedRealtime()
        var chunkIndex = 1
        
        val settings = SettingsStore(this@RecordTranslateService)
        val dir = getExternalFilesDir("recordings")
        var stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        
        val useWav = settings.batchEngine == "OFFLINE"
        currentAudio = if (useWav) File(dir, "puhetrack-$stamp.wav") else File(dir, "puhetrack-$stamp.mp3")
        sink = if (useWav) WavWriter(currentAudio!!) else Mp3Writer(currentAudio!!, bitrateKbps = settings.bitrateKbps)
        
        try { sink?.start() } catch (e: Exception) {
            _status.value = "recorder start failed: ${e.message}"
            return
        }
        _formatLabel.value = if (useWav) "WAV 16kHz" else "MP3 ${settings.bitrateKbps} kbps"

        while (_isRecording.value) {
            if (_isPaused.value) {
                Thread.sleep(120)
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now - chunkStartMs >= 900_000) {
                val oldFile = currentAudio
                sink?.stop()
                if (oldFile != null) {
                    processBatchChunk(oldFile, chunkIndex, chunkStartMs - sessionStartElapsed)
                }
                
                chunkIndex++
                chunkStartMs = now
                stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                currentAudio = if (useWav) File(dir, "puhetrack-$stamp.wav") else File(dir, "puhetrack-$stamp.mp3")
                sink = if (useWav) WavWriter(currentAudio!!) else Mp3Writer(currentAudio!!, bitrateKbps = settings.bitrateKbps)
                try { sink?.start() } catch (_: Exception) {}
            }

            val n = rec.read(buffer, 0, buffer.size)
            if (n > 0) {
                _level.value = computePeak(buffer, n)
                try { sink?.writeSamples(buffer, n) } catch (_: Exception) {}
                heardMs += n / 32
                _heardSec.value = heardMs / 1000
                
                val elapsedInChunk = (now - chunkStartMs) / 1000
                val m = elapsedInChunk / 60
                val s = elapsedInChunk % 60
                _status.value = "Recording... Chunk $chunkIndex ($m:${String.format(Locale.US, "%02d", s)} / 15:00)"
            } else if (n < 0) {
                _status.value = "mic read error: $n"
                break
            }
        }

        val oldFile = currentAudio
        sink?.stop()
        if (oldFile != null) {
            processBatchChunk(oldFile, chunkIndex, chunkStartMs - sessionStartElapsed)
        }

        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
        recorder = null
    }
    
    private fun processBatchChunk(file: File, chunkIndex: Int, sessionStartMs: Long) {
        scope.launch(Dispatchers.IO) {
            batchProcessingMutex.withLock {
                val settings = SettingsStore(this@RecordTranslateService)
                try {
                    val fiText = if (settings.batchEngine == "OFFLINE") {
                        com.puhetrack.app.batch.SherpaBatch.transcribe(this@RecordTranslateService, file)
                    } else {
                    val apiKey = settings.openaiKey
                    com.puhetrack.app.batch.OpenAIBatch.transcribe(file, apiKey, "fi")
                }
                
                val enText = translator.translate(fiText)
                
                val seg = TranscriptSegment(
                    id = chunkIndex.toLong(),
                    textFi = fiText,
                    textEn = enText,
                    textTranslit = FinnishTranslit.transliterate(fiText),
                    isFinal = true,
                    startMs = sessionStartMs
                )
                
                withContext(Dispatchers.Main) {
                    _segments.value = _segments.value + seg
                }
                
                appendTranscriptFile(fiText, enText, sessionStartMs)
                writeJsonFile()
                writeSrtFile()
                rewriteTxtFile()
                
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val pdfFile = File(getExternalFilesDir("recordings"), "puhetrack-chunk-$stamp-study.pdf")
                PdfExport.exportStudyPdf(
                    pdfFile = pdfFile,
                    title = "Chunk $chunkIndex",
                    segs = listOf(Seg(fiText, enText, sessionStartMs)),
                    vocab = emptyList(),
                    key = emptyList()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Chunk processing failed", e)
            }
            }
        }
    }

    private fun activeElapsedMs(): Long =
        SystemClock.elapsedRealtime() - sessionStartElapsed - pausedAccumMs -
            if (_isPaused.value) SystemClock.elapsedRealtime() - pauseStartElapsed else 0L

    private fun computePeak(buf: ByteArray, n: Int): Float {
        var peak = 0
        var i = 0
        while (i < n - 1) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF))
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            i += 2
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    private fun appendTranscriptFile(fi: String, en: String, startMs: Long) {
        try {
            val audio = currentAudio ?: return
            val txt = File(audio.parent, audio.nameWithoutExtension + ".txt")
            txt.appendText("[${relTime(startMs)}]\nFI: $fi\nEN: $en\n\n")
        } catch (_: Exception) {}
    }

    private fun writeJsonFile() {
        try {
            val audio = currentAudio ?: return
            val segs = _segments.value.map { Seg(it.textFi, it.textEn, it.startMs) }
            writeSegmentsJson(sidecar(audio, "json"), segs)
        } catch (_: Exception) {}
    }

    private fun writeSrtFile() {
        try {
            val audio = currentAudio ?: return
            val segs = _segments.value.map { Seg(it.textFi, it.textEn, it.startMs) }
            writeSrtFileShared(sidecar(audio, "srt"), segs)
        } catch (_: Exception) {}
    }

    private fun rewriteTxtFile() {
        try {
            val audio = currentAudio ?: return
            val txt = File(audio.parent, audio.nameWithoutExtension + ".txt")
            val sb = StringBuilder()
            _segments.value.forEach { s ->
                sb.append("[${relTime(s.startMs)}]\nFI: ${s.textFi}\nEN: ${s.textEn}\n\n")
            }
            txt.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    fun audioFile(): File? = currentAudio
    fun recordingsDir(): File? = getExternalFilesDir("recordings")
    fun retryTranslation(segmentId: Long) {}

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PuheTrack:rec").apply { acquire() }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun requestAudioFocus() {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        pauseSession(auto = true)
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        if (_isPaused.value) resumeSession()
                    }
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try {
            val am = getSystemService(AudioManager::class.java) ?: return
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        focusRequest = null
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.createNotificationChannel(
            NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String, recording: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, "rec")
            .setContentTitle("PuheTrack")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(recording)
            .setContentIntent(openPi)
        if (recording) {
            val stopPi = PendingIntent.getService(
                this, 1, Intent(this, RecordTranslateService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val togglePi = PendingIntent.getService(
                this, 2, Intent(this, RecordTranslateService::class.java).setAction(ACTION_PAUSE_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause/Resume", togglePi)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
        }
        return builder.build()
    }

    override fun onDestroy() {
        try { translator.close() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val TAG = "PuheTrack"
        const val ACTION_STOP = "com.puhetrack.app.STOP"
        const val ACTION_PAUSE_RESUME = "com.puhetrack.app.PAUSE_RESUME"
        const val ACTION_TOGGLE = "com.puhetrack.app.TOGGLE"

        @Volatile
        var running = false
            private set
    }
}
