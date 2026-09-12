package com.puhetrack.app.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recognizes the exact PCM stream already owned and archived by PuheTrack.
 *
 * Android 13 added [RecognizerIntent.EXTRA_AUDIO_SOURCE]. It is the only reliable
 * way to record and recognize concurrently: SpeechRecognizer never competes for
 * the microphone because it reads our pipe instead. Audio delivery is buffered
 * on a dedicated thread, so a stalled recognition service cannot stall capture.
 */
class GoogleLiveEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningState: (Boolean) -> Unit = {}
) {
    private data class PipeState(
        val read: ParcelFileDescriptor,
        val write: ParcelFileDescriptor,
        val queue: ArrayBlockingQueue<ByteArray>,
        val active: AtomicBoolean,
        val overloadReported: AtomicBoolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var pipe: PipeState? = null
    private var generation = 0L
    private var desiredListening = false
    private var consecutiveErrors = 0
    private var lastCallbackAt = 0L

    fun start() = handler.post {
        consecutiveErrors = 0
        beginNewGeneration()
    }

    /** Called on the capture thread. It never blocks that thread. */
    fun offerPcm(buffer: ByteArray, byteCount: Int) {
        val target = pipe ?: return
        if (!target.active.get() || byteCount <= 0) return
        if (!target.queue.offer(buffer.copyOf(byteCount)) &&
            target.overloadReported.compareAndSet(false, true)
        ) {
            handler.post {
                if (pipe === target && desiredListening) {
                    onError("Live text fell behind and is reconnecting; audio is still safe")
                    beginNewGeneration()
                }
            }
        }
    }

    fun pause() = handler.post { stopCurrent() }

    fun resume() = handler.post {
        consecutiveErrors = 0
        beginNewGeneration()
    }

    fun stop() = pause()

    private fun stopCurrent() {
        desiredListening = false
        generation++
        handler.removeCallbacksAndMessages(null)
        destroyCurrent(cancel = true)
        onListeningState(false)
    }

    private fun beginNewGeneration() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            desiredListening = false
            onError("Recorded Live Translate requires Android 13 or newer")
            return
        }
        generation++
        val token = generation
        desiredListening = true
        lastCallbackAt = System.currentTimeMillis()
        destroyCurrent(cancel = true)

        val descriptors = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: IOException) {
            onError("Could not connect recorded audio to live recognition")
            scheduleRestart(token, benign = false)
            return
        }
        val state = PipeState(
            read = descriptors[0],
            write = descriptors[1],
            queue = ArrayBlockingQueue(PIPE_QUEUE_CHUNKS),
            active = AtomicBoolean(true),
            overloadReported = AtomicBoolean(false)
        )
        pipe = state
        startPipeWriter(state, token)

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(listenerFor(token))
            }
            recognizer?.startListening(recognitionIntent(state.read))
            onListeningState(true)
            scheduleWatchdog(token)
        } catch (failure: Throwable) {
            Log.w(TAG, "Speech recognition could not start", failure)
            onError("Speech recognition could not start; audio is still being saved")
            scheduleRestart(token, benign = false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun recognitionIntent(source: ParcelFileDescriptor) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fi-FI")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    private fun listenerFor(token: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrent(token)) return
            touch()
            onListeningState(true)
        }

        override fun onBeginningOfSpeech() { if (isCurrent(token)) touch() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (isCurrent(token)) touch() }

        override fun onError(error: Int) {
            if (!isCurrent(token)) return
            touch()
            onListeningState(false)
            val benign = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!benign) onError(errorMessage(error))
            scheduleRestart(token, benign)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrent(token)) return
            touch()
            emitFinal(results)
            // A recognizer that ignores segmented mode ends after one result.
            // Reconnect it to a fresh pipe while capture continues safely.
            scheduleRestart(token, benign = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrent(token)) return
            touch()
            firstText(partialResults)?.let(onPartial)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!isCurrent(token)) return
            touch()
            consecutiveErrors = 0
            emitFinal(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            if (!isCurrent(token)) return
            scheduleRestart(token, benign = true)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun emitFinal(results: Bundle?) {
        firstText(results)?.let(onFinal)
        consecutiveErrors = 0
    }

    private fun firstText(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun startPipeWriter(state: PipeState, token: Long) {
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(state.write).use { output ->
                    while (state.active.get()) {
                        val pcm = try {
                            state.queue.take()
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (pcm.isEmpty()) break
                        output.write(pcm)
                    }
                }
            } catch (failure: Throwable) {
                if (state.active.get()) {
                    Log.w(TAG, "Recognition audio pipe closed unexpectedly", failure)
                    handler.post {
                        if (isCurrent(token) && pipe === state) {
                            onError("Live text connection restarted; audio is still safe")
                            scheduleRestart(token, benign = false)
                        }
                    }
                }
            }
        }, "PuheTrack-live-audio").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleRestart(token: Long, benign: Boolean) {
        if (!isCurrent(token)) return
        if (!benign) consecutiveErrors++
        val delayMs = if (benign) 150L else {
            (200L * (1L shl consecutiveErrors.coerceAtMost(5))).coerceAtMost(8_000L)
        }
        handler.postDelayed({
            if (isCurrent(token)) beginNewGeneration()
        }, delayMs)
    }

    private fun scheduleWatchdog(token: Long) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isCurrent(token)) return
                if (System.currentTimeMillis() - lastCallbackAt > WATCHDOG_MS) {
                    Log.w(TAG, "Speech recognizer watchdog restart")
                    beginNewGeneration()
                    return
                }
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }, WATCHDOG_MS)
    }

    private fun isCurrent(token: Long): Boolean = desiredListening && generation == token

    private fun touch() { lastCallbackAt = System.currentTimeMillis() }

    private fun destroyCurrent(cancel: Boolean) {
        closePipe()
        val old = recognizer
        recognizer = null
        runCatching { if (cancel) old?.cancel() else old?.stopListening() }
        runCatching { old?.destroy() }
    }

    private fun closePipe() {
        val old = pipe
        pipe = null
        old ?: return
        old.active.set(false)
        old.queue.clear()
        old.queue.offer(ByteArray(0))
        runCatching { old.write.close() }
        runCatching { old.read.close() }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Live recognition rejected the audio stream; recording continues"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition network unavailable; recording continues"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy; recording continues"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Finnish speech recognition is unavailable; recording continues"
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition service unavailable; recording continues"
        else -> "Speech recognition interrupted; recording continues"
    }

    companion object {
        private const val TAG = "PuheTrackLive"
        private const val SAMPLE_RATE = 16_000
        private const val PIPE_QUEUE_CHUNKS = 24
        private const val WATCHDOG_MS = 45_000L
    }
}
