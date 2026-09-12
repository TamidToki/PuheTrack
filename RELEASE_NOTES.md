# PuheTrack 2.14.4 release notes

Version 2.14.4 (versionCode 26) is the functional reliability release candidate.

## What changed

- **Recorded Live Translate:** Live mode now archives microphone audio as MP3 while showing Finnish recognition and finished Finnish/English transcript text.
- **One microphone owner:** The same app-owned PCM stream is written to durable audio first and copied through a bounded, non-blocking Android recognition pipe. Recognition problems do not stop the archive.
- **Exact 30-minute chunks:** Both modes rotate after exactly 28,800,000 captured mono frames at 16 kHz. Pauses do not consume chunk time.
- **Reliable recovery:** Open WAV and MP3 recordings can be repaired after process interruption, with explicit session/chunk states and no false success.
- **Grouped Live sessions:** A Live session appears once in the Library and contains its transcript plus playable/shareable audio chunks.
- **Safer cloud sizing:** MP3 is limited to 64 or 96 kbps so a full 30-minute chunk stays below PuheTrack's 24 MB upload safety threshold.
- **Offline download repair:** A fully downloaded model partial is now checksum-verified locally instead of issuing an invalid end-of-file range that returns HTTP 416. Stale ranges restart safely.
- **Correct token manifest:** The 93,939-byte tokenizer file is pinned to the SHA-256 independently verified from the exact model revision, eliminating the false checksum failure.
- **Record UI repair:** Bottom navigation icons now share the same 24 dp size, the central microphone/help text remains reachable at larger system font/display sizes, and both mode-selector rectangles share one height.
- **More live transcript space:** The mode selector, explanation and readiness card remain available before recording, then collapse throughout an active Live session. Batch guidance remains visible.
- **Live auto-follow:** The chronological feed scrolls to the newest row when its Finnish partial, English translation or availability state changes, not only when a new row is created.
- **Release hardening:** 20 focused unit tests, zero release-lint errors, release signing, 16 KB APK/native-library alignment, backup exclusions and secret handling were verified.

## Requirements and limits

- Recorded Live Translate requires Android 13/API 33 or newer and a compatible Android speech-recognition service.
- Normal 30-minute Recording remains available on supported Android 8/API 26+ devices.
- Offline transcription requires the pinned model package. Cloud transcription/translation requires the owner's configured provider credentials and network access.
- Physical microphone, real 30-minute boundary, forced process-death and long-soak acceptance tests remain listed in `TESTING.md`; automated build success alone is not proof that every phone/provider combination is production-ready.

## Upgrade guidance

Install the signed APK with `adb install -r` or through the intended Play test track. Do not uninstall an existing build first if its app-private recordings must be preserved.
