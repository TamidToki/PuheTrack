# PuheTrack phone test plan

Use version **2.14.4** (`versionCode 26`). Keep the phone charged and make sure several gigabytes of storage are free before the long tests. Do not delete a failed session until its exact symptom and time are recorded.

## Quick functional test (about 10 minutes)

1. Open **Live Translate**, start recording, and speak at least ten separate Finnish sentences for 3–5 minutes.
2. Confirm the setup/readiness panels disappear after Start, Finnish and English remain paired, and the feed automatically follows new partial Finnish and completed English text.
3. Scroll upward while speech continues. Note whether the app pulls you back to the bottom; this is the main usability behavior to evaluate next.
4. Pause for 30 seconds, speak while paused, resume, and say a distinctive sentence. Confirm paused speech is absent and post-resume speech appears once.
5. Stop. In Library, play the audio from the beginning and end, compare it with the transcript, close/reopen the app, and confirm the same session remains.
6. Switch to **30-minute Recording**, record 1–2 minutes, stop, and wait for offline Finnish/English processing. Confirm the audio plays even if text processing is delayed or fails.

## Long-session acceptance

1. Run Live Translate for at least **35 minutes** with the screen locked for part of the time. This crosses one real chunk boundary.
2. After stopping, confirm there is one Library session containing two playable MP3 chunks. Listen across 29:50–30:10 for a missing or duplicated phrase and confirm transcript time continues forward.
3. Repeat in 30-minute Recording mode. Confirm the first closed chunk begins processing while the second continues recording.
4. Run one **three-hour** recording before trusting the app for an irreplaceable lesson. Include pause/resume, screen-off time, Bluetooth or an incoming-call interruption, and low-network periods.
5. During a disposable test, force-stop PuheTrack after at least 10 seconds. Reopen it and confirm the partial audio is recovered and clearly marked interrupted rather than falsely complete.

If anything fails, report: mode, exact clock time, approximate session duration, whether the screen was locked, whether headphones/Bluetooth were active, visible error text, and whether the audio still plays. Preserve the session until logs and files can be inspected.

## Short improvement shortlist

1. Make auto-follow conditional: stay at the bottom while the user is reading live, but stop pulling down when they deliberately scroll upward; show a **Jump to latest** button.
2. Add a compact confidence/status line for degraded recognition, translation backlog, storage remaining, and current chunk number without taking transcript space.
3. Add an in-app post-session integrity summary: audio duration, chunk count, recovered/interrupted state, and transcription/translation completion.
4. After functional acceptance, polish typography, spacing, dark mode and the app icon; these are lower priority than the real 30-minute/process-death/three-hour tests.

