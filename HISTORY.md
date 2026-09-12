# PuheTrack project history

This is a concise historical record. `PRODUCT.md` is the current source of truth.

## Original prototype

PuheTrack began as a Kotlin/Compose Android recorder intended to capture Finnish lessons, show Finnish speech-to-text and translate it to English. Early plans combined live recording and live recognition in one pipeline, experimented with Vosk, Parakeet/Sherpa and cloud providers, and later added LAME MP3, library playback, PDF/SRT export, trimming, study tools and a Quick Settings tile.

The original `PLAN.md`, root `fix_*.py` scripts and `AI_Review_Package/` preserve pieces of that development history. They describe several superseded designs, including Vosk, a 15-minute boundary, competing microphone consumers, multiple speech engines and marker-file processing.

## Reliability review input

External AI reviews—including material saved under `/Users/tahmid_23/Documents/PuheTrack-for-Claude`—identified that successful demo behavior was not enough for trustworthy recordings. The final plan prioritized source-data integrity, one microphone owner, process-death recovery, explicit state, idempotent durable work, truthful product copy, model supply-chain pinning and release evidence. The Claude folder remained read-only context.

## 2.14.0 reliability baseline

The production architecture was narrowed to two explicit modes:

- Live Translate: Android-provided Finnish recognition, durable finished-text transcript and English translation, no audio archive.
- 20-minute Recording: exact captured-frame WAV or MP3 chunks followed by durable background transcription and translation.

Major reliability work included frame-exact rotation, pause exclusion, one-second WAV/MP3 durability checkpoints, complete-frame MP3 recovery, session/chunk manifests, atomic canonical transcripts, terminal lifecycle protection, WorkManager processing, Finnish-first checkpoints, translation-only resume, OpenAI `OUTCOME_UNKNOWN`, explicit paid-repeat confirmation, Sherpa isolation in `:inference`, pinned offline model hashes, leases for destructive file operations, transcript-only library/search integration, truthful readiness/settings UI, secure Quick Settings handoff, backup exclusions, strict release signing and Android 16/16 KB toolchain updates.

Version changed once from 2.13.2/code 21 to 2.14.0/code 22 after the implementation pass.

## 2.14.1 recorded-Live correction

The owner clarified that Live Translate must archive the microphone audio at the same time as it produces finished Finnish/English text. Version 2.14.1/code 23 therefore gives both modes one app-owned `AudioRecord`; Live recognition consumes a pipe carrying the already-captured PCM on Android 13+. Live audio is stored as MP3 and grouped with its transcript in the Library. The requested rotation interval was also changed from 20 to exactly 30 minutes (28,800,000 frames at 16 kHz), and the cloud MP3 ceiling was reduced to 96 kbps so a full file remains below PuheTrack's 24 MB upload safety limit.

## 2.14.2 model-download and Record UI repair

Physical testing exposed HTTP 416 after the 652 MB encoder partial had already reached its exact expected length. The downloader had tried to resume at byte 652,184,281, an empty range rejected by the host. Version 2.14.2/code 24 verifies exact-size partials locally, restarts stale ranges safely, removes corrupt complete partials before a clean retry, and adds regression coverage. It also replaces the oversized launcher-style Record navigation artwork with the same 24 dp icon treatment as Files/Search/Settings and makes the center Record guidance scroll-safe at enlarged font sizes.

## 2.14.3 pinned-token and selector repair

The next physical retry correctly reached the final 93,939-byte `tokens.txt` but exposed an incorrect expected hash in PuheTrack's model manifest. The exact pinned-revision file was independently downloaded and measured as SHA-256 `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d`; version 2.14.3/code 25 pins that verified value and adds a regression assertion. The Live Translate and 30-minute Recording selector surfaces now also share the same intrinsic row height and centered text, so the selected rectangle does not change size between modes.

## 2.14.4 live transcript space and follow behavior

The first successful short Live test showed that the pre-session mode explanation and readiness card consumed space needed by the transcript. Version 2.14.4/code 26 keeps that guidance before recording but animates the complete setup header away for every active Live state. Batch mode retains the guidance because it has no live feed. Live auto-follow now reacts to changes in the newest segment's Finnish text, English text and translation availability as well as to new rows, keeping the most recent evolving pair visible during normal recording.

## Verification status

Automated build/unit/lint/signature/alignment evidence is maintained in `TESTING.md` and `RELEASE_CHECKLIST.md`. Physical microphone, process-kill, real 30-minute boundary, cloud-account and three-hour soak checks are release acceptance work and are intentionally not rewritten as “passed” based only on code inspection.
