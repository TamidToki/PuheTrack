# PuheTrack Stability & Architecture Refactor Plan (v3 Final)

**Purpose:** Execution plan for hardening PuheTrack's Dual-Mode Architecture. Work through phases **in order**.

---

## Phase 0 — Safety Net (do this first)

1. Create a new git branch `refactor/stability-pass`.
2. Add a smoke-test checklist to run manually after each phase:
   - Start LIVE mode, speak a sentence, confirm partial + final text + translation appear.
   - Start BATCH mode (OFFLINE), simulate a shortened chunk, confirm WAV closes and files are written.
   - Repeat BATCH test with OpenAI Whisper engine (MP3 path).
   - Trigger pause/resume mid-recording via notification action and via a phone call.
   - Force-kill the app process mid-chunk, relaunch, confirm recovery.
   - **Foreground Service Check**: Confirm on API 34+ that the service successfully launches as a microphone-type FGS without throwing `SecurityException`.

---

## Phase 1 — RecordTranslateService: Structured Concurrency & Lifecycles

**Goal:** Clean up concurrency without prematurely killing vital file I/O operations.

1. Add a `CoroutineExceptionHandler` to the service-level `scope` to prevent silent failures:
   ```kotlin
   private val exceptionHandler = CoroutineExceptionHandler { _, e ->
       Log.e(TAG, "Unhandled coroutine failure", e)
       _status.value = "error: ${e.message ?: e::class.simpleName}"
   }
   private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
   ```
2. **Graceful Job Management (`stopSession`)**: 
   - Explicitly cancel the transient UI/Live jobs: `partialTranslateJob?.cancel()`, `liveEngine?.stop()`, and `tickerJob?.cancel()`.
   - Do not let it terminate naturally without waiting. Keep the current pattern of joining `audioJob`, but make it a bounded await: `withTimeoutOrNull(10_000) { audioJob?.join() }`.
   - Only set status "saved" and call `stopSelf()` after the sink close and final chunk enqueue have finished.
   - Note: Unfinished uploads may be lost when the process dies after `stopSelf()`; we explicitly rely on the marker-file recovery (Phase 3) to re-process them on the next launch.
3. Route all translation/network/inference work explicitly to `Dispatchers.IO`. Audit every `scope.launch` call.
4. **Serialized Chunk Processing**: In `processBatchChunk`, serialize execution so chunk N+1 waits for chunk N:
   ```kotlin
   private val batchProcessingMutex = Mutex()
   // Inside processBatchChunk:
   scope.launch(Dispatchers.IO) {
       batchProcessingMutex.withLock { /* body */ }
   }
   ```
5. **Fix AudioRecord Race (Flag-based ONLY)**: Add `@Volatile private var pauseRequested = false`. The read-loop checks this flag itself and calls `rec.stop()`/`startRecording()` from *inside* the same loop thread.
6. **Wakelock Safety**: Audit `startSession()` early-returns. Ensure `acquireWakeLock()` is called *after* all validations, or wrapped in a `try/finally` block.
7. **Fix `liveAudioLoop()`**:
   - Add `else if (read < 0) break` to prevent busy-spinning on negative error codes.
   - Add a `try/finally` block so `sink?.stop()`, `rec.stop()`, and `rec.release()` always execute even if `sink.writeSamples()` throws.
   - Apply the same chunk rotation logic as `audioLoop()` to `liveAudioLoop()` (close and start a new 15-minute MP3 file at 64 kbps), rather than writing one unbounded file.

---

## Phase 2 — GoogleLiveEngine: Restart Backoff & Watchdog

**Goal:** Prevent restart storms and silent hangs without being overly aggressive.

1. Add an exponential backoff to `restartListening()`:
   ```kotlin
   private var consecutiveErrors = 0
   private val maxBackoffMs = 4000L

   private fun restartListening(error: Int = 0) {
       if (!isListening) return
       // DO NOT increment backoff for normal speech timeouts (6) or no match (7)
       if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
           consecutiveErrors++
       }
       val delayMs = (50L * (1 shl consecutiveErrors.coerceAtMost(6))).coerceAtMost(maxBackoffMs)
       handler.postDelayed({ startListeningInternal() }, delayMs)
   }
   ```
   Reset `consecutiveErrors = 0` inside `onResults`.
2. On any exception caught inside `startListeningInternal()`, destroy and null the `recognizer`.
3. **Watchdog**: Add a 45-second watchdog checking if a callback fired. If `isListening` is true but no callback fired in 45s, destroy and recreate the recognizer.

---

## Phase 3 — Batch Engines: Caching, Retries, Validation

**Goal:** Efficient caching, safe network retries, and deterministic crash recovery.

1. **SherpaBatch Caching**: Convert the `OfflineRecognizer` to a lazily-initialized singleton so the model loads once per process. Release only on a new `SherpaBatch.shutdown()` called from `Service.onDestroy()`.
2. **SherpaBatch WAV Parsing**: Replace the 44-byte skip with a proper scan for the `"data"` sub-chunk marker.
3. **OpenAIBatch Network Safety**:
   - Limit Whisper bitrate to 128 kbps (since 15 min @ 320 kbps > 25MB). **Read-time enforcement:** Coerce the value at read time in `SettingsStore` when `batchEngine == "OPENAI"`. When the user switches engines in `SettingsScreen`, re-clamp the stored value and reflect it in the UI so the slider doesn't lie.
   - Add a pre-flight file-size check: `if (mp3File.length() > 24_000_000) throw ...`
   - Add retry logic but ONLY retry on 5xx/429 or `IOException` network errors. Do NOT retry on 401/400.
4. **State-Marker Crash Recovery**: 
   - Create a `.closed` marker immediately when the chunk file is closed/enqueued.
   - Rename to `.processing` when transcription starts.
   - Delete the marker entirely on success.
   - On app startup, scan for both `.closed` and `.processing` marker files and re-enqueue them for processing.

---

## Phase 4 — Manifest, Size & Release Hygiene

**Goal:** API 36 compliance and R8 shrinking.

1. **Foreground Service Manifest**: 
   - Ensure the manifest includes `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />` AND `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />`.
   - Manifest declaration must include `<service android:name=".RecordTranslateService" android:foregroundServiceType="microphone" ...>`.
   - **Runtime requirement (API 29+)**: Pass the microphone type at runtime in every `startForeground()` call site: `startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)`.
   - Verify `RECORD_AUDIO` is granted *immediately* before starting the FGS, as Android 14+ throws if it starts without it.
2. **R8 Shrinking**: Enable `isMinifyEnabled = true` and `isShrinkResources = true`.
3. Add targeted `-keep` rules in `proguard-rules.pro`:
   - Start minimal with ONLY `-keep class com.k2fsa.sherpa.onnx.** { *; }`
   - Only add more rules if the minified release build or missing_rules.txt demands them.
4. **Remove Azure SDK Thoroughly**: 
   - Grep the whole project for `com.microsoft.cognitiveservices`, `SpeechConfig`, `SpeechRecognizer`, `AzureEngine`, and `AZURE`. 
   - Delete any dead code that references it.
5. Verify 16KB-page-size alignment for `ndkVersion = "26.3.11579264"`.
6. Full clean release build and run Phase 0 smoke-tests on the **release-signed** build.
