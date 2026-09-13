# PuheTrack completion handoff

**Completed:** 2026-09-12  
**Implementation and automated release work:** 100%  
**Physical-device acceptance:** installed and launched; owner testing pending  
**Release candidate:** 2.14.4 / versionCode 26  
**Project:** `/Users/tahmid_23/Documents/PuheTrack`  
**Historical context only:** `/Users/tahmid_23/Documents/PuheTrack-for-Claude`

## Current product contract

PuheTrack has two recording modes:

1. **Live Translate** records the microphone to durable MP3 while displaying Finnish recognition and finished Finnish/English transcript segments. PuheTrack owns the single microphone stream and gives copies of the already-recorded PCM to Android speech recognition through the Android 13+ external-audio pipe. Recognition failure or backpressure must not block recording.
2. **30-minute Recording** archives exact 30-minute captured-audio chunks, then transcribes and translates them. Offline mode uses recovery-friendly WAV; OpenAI mode uses MP3 capped at 96 kbps so a full chunk remains below PuheTrack's 24 MB upload safety ceiling.

Both modes rotate at exactly **28,800,000 mono frames at 16 kHz**. Paused time is excluded from the boundary.

Recorded Live Translate requires Android 13/API 33 or newer and a compatible Android speech-recognition service. On older phones, normal 30-minute Recording remains available.

## Completed implementation

- Replaced competing microphone consumers with one app-owned `AudioRecord` path.
- Added MP3 recording to Live Translate and a bounded, non-blocking PCM pipe to `SpeechRecognizer`.
- Added recognizer generations, watchdog/restart behavior, stale-callback rejection, and truthful degraded-recognition messages while audio continues.
- Coordinated Live start, pause, resume and stop so audio is closed and persisted before the terminal saved state.
- Changed every active recording boundary from 20 to exactly 30 minutes.
- Added frame-exact chunk splitting, per-chunk storage admission, durable WAV/MP3 recovery, and Live-specific completion/recovery rules.
- Grouped Live audio chunks and the transcript into one Library session with playback, share, search, rename and family deletion behavior.
- Capped cloud MP3 choices at 64/96 kbps and budgeted a complete 30-minute file before starting each chunk.
- Updated product, implementation, test, release and independent-audit documentation.
- Kept the installed name and package identity as **PuheTrack** / `com.puhetrack.app` so an in-place upgrade can preserve recordings.
- Kept the current launcher artwork as requested; visual refinements can happen after functional acceptance.
- Repaired model resume after the physical phone exposed HTTP 416: exact-size partials verify locally, stale ranges restart from zero, corrupt complete partials get one clean retry, and regression tests cover the range decisions.
- Corrected the pinned `tokens.txt` manifest to the independently verified 93,939-byte SHA-256 `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` after the phone exposed the old incorrect value.
- Normalized the Record/Files/Search/Settings navigation icons to 24 dp, made the center Record guidance scroll-safe, and made both mode-selector rectangles share the same intrinsic height.
- Collapsed the complete setup/readiness header throughout active Live sessions so the transcript receives the available height, while retaining it before Start and throughout Batch mode.
- Made Live transcript auto-follow react to evolving Finnish partials, completed English text and translation availability, not just changes in segment count.

## Automated verification evidence

Final command:

```bash
JAVA_HOME=/private/tmp/puhetrack-jdk17/Contents/Home \
ANDROID_HOME=/Users/tahmid_23/Library/Android/sdk \
./gradlew --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.vfs.watch=false \
  clean test lintRelease assembleRelease bundleRelease
```

Result: **BUILD SUCCESSFUL** on 2026-09-12 (9m 35s; 98 tasks); the same 20-test suite passed in debug and release and release lint had zero errors. The APK signature, AAB JAR signature, APK 16 KB zip alignment, and all ARM64 ELF `LOAD` alignments were verified. No hard-coded OpenAI/Google key or signing-password value was found in the source scan.

## Final artifacts

- `releases/PuheTrack-2.14.4.apk` — 84,301,797 bytes  
  SHA-256: `ebf29cbc0d29e4b153f2b5eaeb266a11a321c6f24a8347402124f8481e0d7f09`
- `releases/PuheTrack-2.14.4.aab` — 68,677,636 bytes  
  SHA-256: `29c109e1068b9a0cad24b3d5cc2f71727eb0b45bda26519aefaff9a8e44d2598`
- `releases/PuheTrack-2.14.4-device-test.apk` — 84,331,712 bytes  
  SHA-256: `9f9cfc150743408a8febdd8af19dc5d9d950bb43989b9a1462867ace38aa75e8`  
  Local physical-test build only; same minified release payload, re-signed with the matching project debug certificate to preserve the previously installed debug build's data.
- Signing subject: `CN=PuheTrack, OU=PuheTrack, O=PuheTrack, L=Turku, ST=Finland, C=FI`
- Certificate SHA-256: `8203b25c9b0035077781f9d4a56142da5fb7de7c25803c7c0a4c2ee3cb6f1bb5`

## External acceptance still required

The code/release implementation is complete, but recordings should not be called production-trustworthy until the physical P0 matrix in `TESTING.md` passes. The local device-test lineage was upgraded in place on a Samsung SM-A346E running API 36. The phone reports **Offline transcription ready** after verifying all four pinned files and the activation manifest. The two mode-selector surfaces have identical device bounds and were visually checked. The owner then completed an approximately five-second Live test and reported that it appeared to work. Version 2.14.4/code 26 was installed without clearing app/model data to improve active-Live transcript space and auto-follow; its hands-on UI behavior and long-session reliability still require the tests in `PHONE_TEST_PLAN.md`.

The phone's old code-21 build used the Android debug certificate, so Android correctly rejected the upload-signed release APK as an incompatible upgrade. The installed device-test APK uses the matching debug certificate and is for local testing only. A later upload/Play-signed installation cannot upgrade this debug-signed lineage in place; preserve/export important test recordings before switching signing lineages.

When the phone is available:

1. Run the Live and Batch smoke tests, then the real-boundary/process-kill/soak checks in `TESTING.md`.
2. Record exact symptoms and preserve the affected session if anything fails.
3. Use `ED_AUDIT_PROMPT.md` for an independent GPT-6 Astra high/xhigh read-only audit after the phone smoke test.

## Continuity advice

Automatic context compaction does not require a new task: this file contains the durable state and exact remaining external checks. Continuing this task is preferable while it remains available because its summarized history is retained. If a new task is ever needed, ask it to read this file first; do not ask it to rediscover or reimplement completed work.

## Source backup state

The project was initialized as a local Git repository on branch `main` and a reviewed initial source/documentation commit was created. `.gitignore` excludes build outputs, APK/AAB packages, recordings, downloaded models, local Android properties, signing material, archives and common secret formats; the real model-repository source package and its tests are included. A second staged secret-pattern scan found no token/private-key value.

The source backup is hosted in the private GitHub repository `https://github.com/TamidToki/PuheTrack` on branch `main`. GitHub CLI was authenticated as `TamidToki` through the owner's approved device flow. Build products, recordings, downloaded models, local configuration, signing material and secret-like files remain excluded from the repository by `.gitignore`.
