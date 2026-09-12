# PuheTrack — product and system specification

**Version:** 2.14.4 (versionCode 26) · **Package:** `com.puhetrack.app`  
**Platform:** Android 8.0+ (min SDK 26), target/compile SDK 36  
**Primary languages:** Finnish speech → Finnish text → English translation

**Release evidence:** see `TESTING.md` and `RELEASE_CHECKLIST.md`; physical-phone acceptance remains separate from build evidence.

## Product contract

PuheTrack has two modes with different promises. The mode is selected visibly on the Record screen and becomes immutable when the session starts.

| Mode | User result | Audio policy | Processing |
|---|---|---|---|
| Live Translate | Recorded microphone audio plus finished Finnish utterances paired chronologically with English | Exact 30-minute MP3 chunks; pause time is excluded | One app-owned `AudioRecord` writes MP3 and feeds the same PCM to Android `SpeechRecognizer` through `EXTRA_AUDIO_SOURCE`; translation is durable per final utterance |
| 30-minute Recording | Durable audio chunks plus Finnish/English transcript sidecars | Exact 30 minutes of captured audio per full chunk; pause time is excluded | Closed chunks enter WorkManager and use either the pinned offline model or OpenAI Whisper |

Recorded Live Translate requires Android 13/API 33 or newer and a compatible recognition service. This restriction prevents the unsafe fallback where `SpeechRecognizer` and `AudioRecord` compete for the microphone. Both modes check for enough free space for a complete next chunk plus reserve before each chunk. A recording may succeed even while transcription/translation is blocked; the audio remains available and processing can be retried.

## Reliability invariants

1. **One microphone owner.** Every recording session has one `AudioRecord`. In Live mode, Android recognition reads a pipe carrying those already-captured PCM bytes; it does not own a second microphone stream.
2. **Frame-exact rotation.** At 16 kHz mono, a full chunk closes at exactly 28,800,000 captured PCM frames. Pauses submit no frames and do not move the boundary.
3. **Source data first.** PCM is written to the local encoder before it is offered to Live recognition, and audio is closed durably before background batch inference begins. Live Finnish text is atomically committed before translation is queued.
4. **Finnish before English.** Finnish transcript segments are a durable checkpoint. English can resume segment-by-segment without repeating transcription.
5. **No blind paid duplicate.** A disconnected OpenAI request is recorded as `OUTCOME_UNKNOWN`. It is excluded from automatic work and requires a separate **Confirm retranscription** action, including for older manifests carrying the confirmation prerequisite.
6. **Terminal state wins.** Late pause/resume writes cannot downgrade a `STOPPED`, `INTERRUPTED`, or `FAILED` session.
7. **Recover, do not guess.** Open WAV/MP3 files are recovered conservatively after process death. Invalid partial media is retained/marked rather than represented as verified audio.
8. **File identity is stable.** Rename changes only a session's display title. Audio, manifests, hashes, queue IDs, and sidecar identities do not move.
9. **Destructive operations respect leases.** Delete/rename cannot race an active processing lease.
10. **Secrets and large data do not roam.** Encrypted API-key preferences, recordings, and offline model data are excluded from backup/device transfer.

## Runtime architecture

### Capture service

`RecordTranslateService` owns session state, foreground notification, audio focus, wake lock, pause/resume/stop, live UI events, and final teardown. Its explicit public state is:

```text
IDLE → STARTING → RECORDING ⇄ PAUSING/PAUSED → STOPPING → SAVED
                                                     ↘ FAILED
```

The service is `START_NOT_STICKY`: Android cannot honestly reconstruct an in-memory microphone capture after killing it. On the next process start, repository recovery closes recoverable media and marks unfinished sessions `INTERRUPTED`.

### Batch capture

`BatchCaptureController` reads PCM16, calculates level, splits every read at exact `ChunkClock` boundaries, and writes one file at a time:

- Offline transcription: 16 kHz mono WAV, about 57.6 MB per full chunk.
- OpenAI transcription: mono MP3 at 64/96 kbps. 96 kbps is the enforced maximum; an ideal 30-minute CBR file is about 21.6 MB and PuheTrack rejects anything above its 24 MB application safety limit before upload.
- Live Translate: mono MP3 at 64/96 kbps plus the same PCM stream delivered to the Android recognizer through a bounded off-capture-thread pipe. Recognition failure never stops the local audio archive.

WAV headers and file descriptors are checkpointed about once per captured second. MP3 output calls `fflush` + `fsync` about once per captured second and again on finalization. LAME is protected by an exclusive process-wide encoder lease.

### Durable processing

Each chunk has a `.state.json` manifest and unique WorkManager job. The stages are:

```text
CAPTURING
  → PENDING_TRANSCRIPTION → TRANSCRIBING
  → FINNISH_COMMITTED → TRANSLATING → COMPLETE
                         ↘ RETRY_PENDING / BLOCKED
  → OUTCOME_UNKNOWN (OpenAI response lost; user confirmation required)
```

Offline Sherpa inference runs in a non-exported private `:inference` process. A native inference crash therefore does not terminate microphone capture. Long files are decoded in bounded 30-second windows.

OpenAI uses `whisper-1`, Finnish language, verbose JSON and provider segment timestamps. HTTP calls are asynchronous and coroutine cancellation closes the socket. A network disconnect after request submission becomes `OUTCOME_UNKNOWN`, not an automatic retry.

Translation uses ML Kit locally or Google/DeepL when explicitly configured. Translation progress is committed after every segment, so a retry resumes from unfinished English segments. Cloud translation HTTP is also coroutine-cancellable.

### Metadata and exports

Session manifests freeze the selected mode, transcription engine, translation provider, bitrate, app version and display title. Chunk manifests store lifecycle, integrity, captured-frame count, durable bytes, active-session offset, processing stage, retry metadata, provider/model identity, config hash and audio SHA-256 when processing/recovery computes it.

Canonical transcript JSON is authoritative. Compatibility exports are regenerated beside it:

- Batch: `.mp3` or `.wav`, `.session.json`, `.state.json`, `.transcript.json`, `.json`, `.txt`, `.srt`.
- Live: one or more `.mp3` chunks plus `.session.json`, per-chunk `.state.json`, `.transcript.json`, `.json`, `.txt`, and `.srt`.
- On demand: normal PDF and study PDF.

Metadata writes use a same-directory `.new` file, `fsync`, atomic rename, and a last-known-good `.bak` generation.

## Offline model

The offline Finnish recognizer is `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`, pinned to revision:

```text
2bda32ec70b097a55adaa07d9a7173915b43cc78
```

Four required files total 670,478,772 bytes (~671 MB). Every file has a fixed expected length and SHA-256, including the host-verified 93,939-byte `tokens.txt` hash `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d`. Downloads default to an unmetered connection, resume from `.part` files when the host honors ranges, verify complete partials locally instead of requesting an empty end-of-file range, safely restart stale HTTP 416 ranges, verify before rename, and commit an activation manifest only after all files pass. The user can explicitly permit metered data.

## UI and library

- Record screen: explicit two-mode selector, readiness explanation, state-aware controls, timer/level, one paired FI/EN chronological feed, uniformly sized navigation icons, and scroll-safe empty/listening guidance at enlarged font sizes.
- Library: standalone batch audio chunks and grouped recorded-Live sessions, playback/share/delete, display-title rename, processing state, manual retry, and explicit paid-repeat confirmation.
- Search: Finnish and English across both session types; scanning occurs off the main thread.
- Detail: transcript, translation repair, safe trim, PDF/study export, and delete confirmation. Large PDF work and row metadata extraction run off the main thread.
- Settings: real offline-model state/download, transcription provider, translation provider, encrypted cloud-key management, bitrate and truthful privacy copy. Obsolete silence-cut controls were removed.

The Quick Settings tile uses the Android 14+ `PendingIntent` API. A non-exported activity creates a private one-shot toggle request; external apps cannot turn the exported launcher activity into a recording trigger.

## Privacy and networking

Recordings and transcripts live under app-private storage. API keys use Android encrypted shared preferences and are excluded from backup. Nothing is uploaded unless the frozen session configuration selects a cloud provider:

- OpenAI: audio chunk for Finnish transcription.
- Google Translate or DeepL: Finnish segment text for English translation.
- ML Kit/offline Sherpa: on-device processing after required model downloads.
- Android Live recognition receives the app-captured audio stream and may process it locally or over the network according to the recognition service installed on the device; the app describes it as Android-provided, not guaranteed offline.

## Build and release

- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- Kotlin/Compose plugin 2.0.20
- NDK 28.2.13676358
- CMake 3.22.1
- R8 and resource shrinking enabled for release
- Release signing only from external `PUHE_*` properties; no debug-key fallback

See `TESTING.md` for current evidence. Compilation, unit tests, lint, signing and 16 KB alignment are automation-verifiable. Microphone behavior, provider behavior, true 30-minute rotation and long-run survival remain device acceptance tests and must pass before recordings are trusted in production.
