# PuheTrack 2.14.4 reliability implementation

This is the implementation record for the production-hardening pass. It supersedes the older refactor notes and deliberately separates code-complete work from device validation.

## What changed

### Capture integrity

- Replaced wall-clock rotation with `ChunkClock`, which closes at 28,800,000 captured frames (16,000 Hz × 1,800 seconds).
- Split a microphone buffer exactly when it crosses a boundary; the next chunk receives only the remaining frames.
- Made pause stop capture-clock progress rather than encode synthetic silence.
- Added a free-space gate before every new chunk, sized for one complete WAV/MP3 chunk plus a 64 MB reserve.
- Reworked batch capture around one microphone owner and one active writer.
- Added explicit service states and guarded start/pause/resume/stop/failure teardown.
- Protected terminal session lifecycle from late asynchronous pause/resume writes.
- Marked active/paused manifests `INTERRUPTED` on process-start recovery.

### File durability and recovery

- WAV writes repair the header and `fsync` approximately every captured second and at close.
- MP3 writes use checked native return values, exact short-write detection, `fflush` + `fsync` approximately every captured second, checked final flush/close, and an exclusive encoder lease.
- Removed all synthetic-silence generation from MP3 capture.
- Added conservative MPEG Layer III recovery that scans for consecutive complete frames, discards junk prefix/trailing partial data, and derives recoverable sample frames.
- Recovery operates through a separate `.recovery` file; promotion occurs only after a valid durable copy exists.
- Session, chunk and canonical transcript JSON use `.new` + file sync + same-directory rename, retaining a `.bak` generation.
- Each closed chunk records its byte count and later SHA-256 before inference. Legacy marker files are imported as blocked/unknown and never auto-uploaded.

### Processing semantics

- Replaced in-service processing with unique WorkManager jobs and exponential backoff.
- Frozen session configuration controls every later worker; changing Settings cannot silently change an existing session.
- Canonical Finnish segments are committed before translation starts.
- English is checkpointed after each segment and retries skip completed segments.
- Missing model/key states are visible `BLOCKED` states with repair actions.
- OpenAI disconnects become `OUTCOME_UNKNOWN`; reconciliation excludes them and every compatible manifest form requires explicit paid-repeat confirmation.
- OpenAI and Google/DeepL calls are asynchronous and cancel their underlying OkHttp calls when the coroutine is cancelled.
- Sherpa work runs in a non-exported `:inference` process and uses bounded 30-second windows.

### Live Translate

- Replaced the competing-microphone prototype with one app-owned `AudioRecord`: local MP3 capture receives each PCM buffer first, then a non-blocking tap feeds the same bytes to Android 13+ `SpeechRecognizer` through `EXTRA_AUDIO_SOURCE`.
- Added a bounded recognition queue and dedicated pipe writer so recognition backpressure, network loss or service failure cannot stall the audio capture thread.
- Added recognizer generation tokens, pause/resume destruction, stale-callback rejection, bounded restart backoff and watchdog recovery.
- Commits each final Finnish utterance atomically before queueing translation.
- Stop closes the MP3 before reporting success and waits for all accepted Finnish persistence jobs. Queue insertion failure does not misreport source-text loss because startup reconciliation can recreate translation work.
- Saved live sessions group their 30-minute MP3 chunks with the transcript, support playback/audio sharing and translation retry, and delete the complete session family atomically.
- Recorded Live Translate is intentionally gated to Android 13+; older Android versions retain 30-minute Recording without risking a dual-microphone race.

### UI, files and settings

- Put `Live Translate | 30-minute Recording` on the Record screen and lock it during an active session.
- Replaced split transcript panels with one chronological FI/EN pair feed.
- Added truthful readiness states for recognition service, offline model and cloud key.
- Added real offline model state, explicit ~671 MB resumable download, unmetered default, metered opt-in, verification and repair.
- Fixed completed `.part` handling so the downloader verifies a full file locally instead of issuing an invalid empty range that Hugging Face answers with HTTP 416. Stale/invalid partial ranges now restart safely, and a corrupt complete partial is removed before one clean retry.
- Corrected the pinned 93,939-byte `tokens.txt` SHA-256 after physical installation exposed the bad manifest value; the replacement hash was computed from the exact pinned-revision download and anchored in `ModelRepositoryTest`.
- Normalized all bottom-navigation icons to 24 dp and made the Record screen's central microphone/help content scroll-safe at enlarged font/display settings.
- Keep the setup/mode/readiness header before Live recording, animate it away for every active Live state, and retain it in Batch mode where there is no live transcript.
- Auto-follow the newest transcript row whenever its Finnish partial, English result or translation-availability state changes, as well as when a new row is appended.
- Removed unimplemented silence-cut/VAD settings.
- Added processing status, retry, and **Confirm retranscription** for uncertain OpenAI outcomes.
- Made live transcripts searchable/shareable/deletable, and changed transcript sharing to a granted file URI so long lessons do not exceed Intent binder limits.
- Rename updates only a durable display title. Delete/rename observe processing leases.
- Moved search, PDF generation and library duration/preview extraction off the main thread.
- Fixed launcher assets that contained JPEG data under `.png` names.
- Added both external and internal app-private recording directories to `FileProvider`.

### Android/release hardening

- Target/compile SDK 36; AGP 8.10.1; Gradle 8.11.1; NDK 28.2.13676358.
- Added microphone foreground-service permissions/type, recognition-service visibility and a private inference service.
- Updated Quick Settings launch to use `PendingIntent` on Android 14+ and a non-exported one-shot toggle activity.
- Excluded keys, recordings and the revisioned offline model directory from modern and legacy backup rules.
- Enabled R8/resource shrinking.
- Removed release fallback to the debug signature. A release is signed only when the external upload-key properties are present.
- Bumped the recorded-Live release to 2.14.1/code 23, the range/UI repair to 2.14.2/code 24, the verified token-manifest/equal-selector repair to 2.14.3/code 25, and the active-Live transcript-space/auto-follow repair to 2.14.4/code 26.

## Key files

| Concern | Primary implementation |
|---|---|
| Session/service state | `RecordTranslateService.kt`, `session/SessionConfig.kt` |
| Exact capture and storage gate | `audio/BatchCaptureController.kt`, `audio/ChunkClock.kt`, `audio/StoragePolicy.kt` |
| Durable media | `audio/WavWriter.kt`, `audio/Mp3Writer.kt`, `audio/Mp3Recovery.kt`, `cpp/lame_bridge.c` |
| Metadata/recovery/leases | `data/ChunkManifest.kt`, `data/RecordingRepository.kt` |
| Durable work | `processing/ChunkProcessingWorker.kt`, `processing/LiveTranslationWorker.kt`, `processing/ProcessingCoordinator.kt` |
| Cloud transcription | `batch/OpenAIBatch.kt` |
| Offline isolation | `batch/OfflineInferenceClient.kt`, `batch/OfflineInferenceService.kt`, `batch/SherpaBatch.kt` |
| Model supply chain | `models/ModelRepository.kt` |
| Live recognition | `stt/GoogleLiveEngine.kt` |
| Product surfaces | `ui/RecorderScreen.kt`, `ui/RecordingsScreen.kt`, `ui/LiveTranscriptDetailScreen.kt`, `ui/SettingsScreen.kt` |

## Important intentional choices

- A hard-killed microphone session is recovered, not resumed.
- Live partial text is visible but is not persisted as a final utterance.
- The 30-minute boundary means captured speech duration, not wall time.
- OpenAI uncertainty favors avoiding a duplicate charge over automatic completion.
- Offline model activation proves the downloaded files matched pinned hashes at install time; runtime load failures still become recoverable processing failures.
- Historical scripts in the repository root and `AI_Review_Package/` are retained as history; production Gradle does not execute them.

## Model recommendation

GPT-5.6 Sol is capable of the entire implementation. GPT-6 Astra is not required to finish routine work. Its best use is an independent, skeptical final audit of capture races, process-death recovery, WorkManager idempotency, paid-request uncertainty, JNI ownership, Android lifecycle rules and the evidence in `TESTING.md`. Use high or xhigh reasoning and ask it to report findings before editing. The ready prompt is in `ED_AUDIT_PROMPT.md` and can be given to Ed or Astra unchanged.
