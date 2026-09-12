# PuheTrack verification record

**Code under test:** 2.14.4 / versionCode 26  
**Last updated:** 2026-09-12

This file distinguishes evidence produced on the build host from acceptance tests that require a real Android microphone/device. A successful build is not presented as proof that long recordings are safe.

## Automated evidence

The final verification command is:

```bash
JAVA_HOME=<JDK-17> ANDROID_HOME=<Android-SDK> \
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  :app:lintRelease \
  :app:assembleRelease \
  :app:bundleRelease
```

Current 2.14.4 evidence is recorded below after the final gate. Previous artifact hashes are historical and do not verify the active-Live header/auto-follow change.

Unit coverage currently contains 20 passing tests across six suites:

| Suite | Verified behavior |
|---|---|
| `ChunkClockTest` | exact 28,800,000-frame boundary, crossed-buffer split without loss/duplication, no pause progress, multiple boundaries in one read |
| `WavWriterTest` | recovery truncates a half sample and repairs the RIFF/data lengths |
| `Mp3RecoveryTest` | complete consecutive-frame recovery; junk/random data is not promoted |
| `StoragePolicyTest` | full offline chunk plus reserve; full recorded-Live MP3 budget; 30-minute cloud MP3 budget below 24 MB |
| `ChunkManifestTest` | session and transcript JSON round-trip, process-interruption stage choice, Live audio completion without batch work, paid-repeat confirmation, terminal lifecycle protection |
| `ModelRepositoryTest` | host-verified token length/hash pin; exact-size partial verifies locally instead of requesting an empty range; partial/oversized planning; strict `Content-Range` offset parsing |

Additional release checks:

- Android lint must finish with zero errors. Warnings are reviewed, not hidden behind a baseline.
- The release APK and AAB must be produced with R8/resource shrinking.
- `apksigner verify` must pass for the APK when upload-key properties are configured.
- `zipalign -c -P 16 -v 4` must pass.
- Every `arm64-v8a` native library's ELF `LOAD` alignment must be at least `0x4000` (16 KB).

Final 2.14.4 results:

- Final clean combined gate: **BUILD SUCCESSFUL** on 2026-09-12 (9m 35s; 98 tasks).
- Unit tests: the same **20-test suite passed in debug and release**, with zero failures, errors or skips.
- Release lint: **zero errors**. The guarded Android 13 recorded-Live path has no unresolved API-level warning.
- R8/resource-shrunk release APK and signed Play AAB: produced successfully.
- APK signature: verified with one signer using APK Signature Scheme v2.
- AAB JAR signature: verified.
- APK 16 KB zip alignment: verified successfully.
- ARM64 native libraries: all ELF `LOAD` segments in all seven packaged libraries are aligned to `2**14` (`0x4000`).
- Source secret scan: no hard-coded OpenAI/Google API key or signing-password value found; signing passwords are read from Gradle properties.

Final artifacts:

```text
releases/PuheTrack-2.14.4.apk  84,301,797 bytes
SHA-256: ebf29cbc0d29e4b153f2b5eaeb266a11a321c6f24a8347402124f8481e0d7f09

releases/PuheTrack-2.14.4.aab  68,677,636 bytes
SHA-256: 29c109e1068b9a0cad24b3d5cc2f71727eb0b45bda26519aefaff9a8e44d2598
```

## Build-host limitation discovered

An Android 35 ARM64 16 KB-page emulator image and AVD (`puhetrack_api35_16k`) were installed. Emulator 37.1.11 exits before boot on this Apple-silicon host with:

```text
Incompatible processor. This Qt build requires the following features: neon
```

The emulator and QEMU binaries themselves identify as Mach-O ARM64 and Hypervisor.Framework reports available, so this is a host emulator/Qt startup blocker rather than an observed PuheTrack failure. No `adb` device became available. Emulator/UI assertions are therefore not marked passed.

Later on 2026-09-10, a Samsung SM-A346E running API 36 connected successfully. The pre-existing code-21 app was debug-signed, so Android correctly rejected an in-place upgrade with the upload-signed release certificate. A local device-test copy of the same minified 2.14.1 release payload was signed with the matching project debug certificate and installed with `adb install -r`. Code 23/version 2.14.1 was confirmed, the main activity launched, no crash-buffer entry appeared, and the pre-existing Library item remained visible. This proves installation/launch/data-preserving debug-lineage upgrade only; the microphone and feature checkboxes below remain pending.

After the owner reproduced HTTP 416 and Record-screen scaling issues, the matching device-test build of 2.14.2/code 24 installed successfully as another in-place upgrade. The installed version was confirmed and the activity start command completed without a crash-buffer entry. Hands-on confirmation of model activation and the repaired Record layout remains pending.

After the next retry exposed the incorrect `tokens.txt` manifest hash, the exact pinned-revision file was downloaded independently: 93,939 bytes, SHA-256 `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d`. Version 2.14.3/code 25 with that pin and the equal-height mode selector installed successfully on the same phone without clearing data. The phone then displayed **Offline transcription ready — The pinned model is installed and verified**, which requires every expected file length/hash plus the activation manifest to pass. The mode-selector surfaces also reported identical bounds, `[72,315][540,504]` and `[540,315][1008,504]`, and were visually inspected in the device screenshot. This verifies model installation/readiness and the requested selector geometry; an actual offline audio inference remains a separate test below.

The owner then ran an approximately five-second Live test and reported that it appeared to work. Version 2.14.4/code 26 was clean-built, verified and installed in place on the same phone. It collapses the setup/readiness header while Live is active and follows updates to the newest FI/EN pair. The phone was locked during the post-install UI-tree attempt, so those two UI behaviors are intentionally awaiting the owner's next hands-on test rather than being marked passed.

## Physical-device release matrix

Run these on at least one API 33–36 physical phone and one older supported phone (API 26–30 where practical). Recorded Live Translate is expected to be unavailable on the older phone; 30-minute Recording must remain usable. Capture Logcat, manifest/transcript files, media duration and hashes for the failure cases.

### P0 — must pass before trusting recordings

- [ ] Grant microphone permission, start Batch/Offline, speak, pause for at least 30 seconds, resume, stop. Confirm pause adds neither samples nor chunk-clock time.
- [ ] Record continuously across the real 30:00 captured-audio boundary. Confirm chunk 1 has exactly 28,800,000 manifest frames, chunk 2 begins without a repeated or missing audible phrase, and both files play.
- [ ] Repeat the real boundary test with OpenAI/MP3 at 96 kbps; confirm file size remains below 24 MB.
- [ ] Force-stop/kill the app during a WAV capture after >5 seconds. Relaunch; confirm a playable recovered file, `RECOVERED_PARTIAL`, and `INTERRUPTED` session.
- [ ] Force-stop/kill during MP3 capture after >5 seconds. Relaunch; confirm only complete MPEG frames are promoted and the recovered media plays.
- [ ] Force-stop/kill during recorded Live Translate after >5 seconds. Relaunch; confirm its MP3 is recovered under the Live session, remains playable, and is not queued for batch transcription.
- [ ] Stop immediately during `STARTING`, during pause acknowledgment, and just after a boundary. Confirm one terminal session state and no orphan microphone/notification.
- [ ] Deny/revoke microphone permission and exhaust storage. Confirm capture does not start and no false “saved” state appears.
- [ ] Lock the screen and background the app for at least 35 minutes. Exercise notification pause/resume/stop and confirm foreground-service survival across a boundary.
- [ ] Run a three-hour-captured-audio Batch soak with at least six boundaries, pauses, screen-off time and an incoming audio-focus interruption. Compare total manifest frames with decoded media duration.

### P1 — processing and product behavior

- [ ] Offline: install the pinned model on Wi-Fi, interrupt/resume its download, verify activation, transcribe Finnish speech, then translate with ML Kit.
- [ ] OpenAI: transcribe a Finnish chunk and verify provider segment timestamps become canonical transcript offsets.
- [ ] Simulate network loss after OpenAI upload begins. Confirm `OUTCOME_UNKNOWN`, no automatic repeat after relaunch, and a separate **Confirm retranscription** action.
- [ ] Kill the app during offline inference. Confirm capture is unaffected and work safely retries later.
- [ ] Kill during English translation. Confirm durable Finnish remains and retry skips already completed English segments.
- [ ] Recorded Live Translate on API 33+: verify microphone level, partial text, multiple finished utterances, FI/EN pairing, pause/resume stale-callback rejection, stop persistence, and a playable non-empty MP3 in the same Library session.
- [ ] Keep Live running across a real 30-minute boundary; confirm two playable MP3 chunks, exactly 28,800,000 frames in chunk 1, continuous transcript timing, and only one grouped Library item.
- [ ] Interrupt the recognition network/service while Live is recording. Confirm the UI reports degraded/reconnecting text while MP3 capture continues without a gap or false failure.
- [ ] Try Live mode below API 33 and with no compatible recognition service; verify it is disabled with truthful guidance while 30-minute Recording remains available.
- [ ] Test Google and DeepL missing/invalid keys, transient failure, retry and manual repair. Confirm keys never appear in Logcat.
- [ ] Rename a multi-chunk session. Confirm display title changes while filenames, hashes, manifests and work identities remain stable.
- [ ] Search Finnish and English in audio and live sessions; open the correct result.
- [ ] Share large `.txt`, audio and PDFs; verify recipient access survives chooser handoff and no binder-size crash occurs.
- [ ] Attempt delete while processing, then after processing. Confirm the lease prevents the race and complete file families are removed afterward.
- [ ] Add/use the Quick Settings tile from unlocked and locked states; confirm exactly one start/stop toggle per tap.

### P2 — compatibility and release

- [ ] Install the signed release APK as an upgrade over the prior user build; verify old recordings remain listed and legacy markers require manual consent.
- [ ] Verify fresh install, permission education, notification behavior and backup/restore exclusions.
- [ ] Test light/dark theme, font scaling, rotation and accessibility labels on Record, Library, Search, Settings and detail screens.
- [ ] Install a Play internal-test AAB on a 16 KB-page-size device and exercise both native paths (LAME and Sherpa).

## Acceptance rule

Implementation is complete when the code/release checks pass. The app should be called **recording-trustworthy** only when every P0 checkbox passes on physical hardware. Provider production readiness additionally requires the relevant P1 cloud tests with the owner's real accounts and billing limits.
