# PuheTrack

PuheTrack is an Android app for understanding Finnish speech in two deliberately separate modes:

- **Live Translate** records the microphone in exact 30-minute MP3 chunks while showing finished Finnish utterances with English translations (Android 13+).
- **30-minute Recording** preserves audio in exact 30-minute captured-audio chunks, then transcribes and translates each closed chunk in the background.

The current implementation is version **2.14.4** (`versionCode 26`), package `com.puhetrack.app`, min SDK 26 and target SDK 36. Batch recording supports Android 8.0+; recorded Live Translate requires Android 13+ so the app can send its own captured PCM stream to the speech recognizer without a second microphone consumer. During an active Live session, setup copy collapses and the chronological Finnish/English feed follows the newest partial or translated text.

## Documentation

- [`PRODUCT.md`](PRODUCT.md) — current product and architecture specification.
- [`IMPLEMENTATION_NOTES.md`](IMPLEMENTATION_NOTES.md) — reliability changes and design decisions.
- [`TESTING.md`](TESTING.md) — verified automated evidence and the required device matrix.
- [`PHONE_TEST_PLAN.md`](PHONE_TEST_PLAN.md) — short hands-on test sequence for the owner.
- [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) — release gate; unchecked physical tests are intentional blockers to a trust claim.
- [`ED_AUDIT_PROMPT.md`](ED_AUDIT_PROMPT.md) — ready-to-copy independent audit prompt.
- [`HISTORY.md`](HISTORY.md) — concise project history.
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — dependency/model license inventory for distribution.
- [`RESUME_CHECKPOINT.md`](RESUME_CHECKPOINT.md) — current handoff/status record.

`PLAN.md` and `AI_Review_Package/` are historical inputs, not the current specification. `/Users/tahmid_23/Documents/PuheTrack-for-Claude` is context only and is not part of this build.

## Build

Use JDK 17 and the Android SDK/NDK versions declared in Gradle:

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

Release signing is configured only when `PUHE_STORE_FILE`, `PUHE_STORE_PASSWORD`, `PUHE_KEY_ALIAS`, and `PUHE_KEY_PASSWORD` are supplied through user Gradle properties or environment variables. The project never falls back to a debug signature for release.
