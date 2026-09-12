# Ready-to-copy prompt for Ed (or GPT-6 Astra)

Use this after the final build evidence has been written into `TESTING.md` and `RELEASE_CHECKLIST.md`:

```text
Act as the independent pre-release auditor for PuheTrack at:
/Users/tahmid_23/Documents/PuheTrack

Do not inspect or edit /Users/tahmid_23/Documents/PuheTrack-for-Claude; it is historical context only. Start by reading README.md, PRODUCT.md, IMPLEMENTATION_NOTES.md, TESTING.md, RELEASE_CHECKLIST.md, RESUME_CHECKPOINT.md, app/build.gradle.kts, AndroidManifest.xml, backup/provider XML, and then every production source and unit test. Treat old PLAN.md, root fix_*.py scripts and AI_Review_Package as historical, not authoritative.

First perform a read-only, skeptical audit. Do not edit anything until you have delivered findings and I explicitly ask for fixes. Verify implementation against code, not documentation claims.

Concentrate on failures that could lose irreplaceable Finnish audio/text, duplicate or omit PCM frames at exact 30-minute rotation, corrupt WAV/MP3 on close or process death, race start/pause/resume/stop, leave the foreground microphone alive, overwrite terminal manifests, duplicate WorkManager work, repeat a potentially billed OpenAI transcription after a lost response, redo transcription when only English failed, leak API keys, let file deletion/rename race processing, mishandle stale SpeechRecognizer callbacks or the Android 13+ external-audio pipe, let recognition backpressure block capture, or allow Sherpa JNI failure to kill capture.

Trace these end to end:
1. Batch start → exact frame splitting → one-second durability checkpoints → close → manifest ordering → queue → Finnish commit → English checkpoints.
2. Stop/error/process death at every transition, including an open MP3, open WAV, TRANSCRIBING, TRANSLATING and OUTCOME_UNKNOWN.
3. Recorded Live end to end: one `AudioRecord` owner → MP3-first PCM write → non-blocking recognizer pipe → partial/final callback generation → Finnish atomic persistence → translation recovery → grouped library/play/share/delete. Prove that recognizer restart, pause, stop, network loss, pipe closure or queue overload cannot stall or corrupt audio capture.
4. OpenAI cancellation and uncertainty semantics; prove no automatic or generic manual path bypasses explicit paid-repeat confirmation, including older BLOCKED manifests with confirm_retranscription.
5. Repository atomic writes/backups, session lifecycle monotonicity, leases, rename identity, legacy import and backup exclusions.
6. Android 14–16 foreground-service and Quick Settings behavior, exported-component boundaries, release signing, R8/JNI keep rules and 16 KB native-library readiness.
7. Offline model revision pin, expected sizes/hashes, resumable download, metered policy, activation and inference process isolation.

Run the available unit/build/lint/release/signature/alignment checks yourself. Review the recorded evidence, but do not count a build as proof of microphone behavior. Identify which claims still require a physical phone, real 30-minute boundary, forced process death, real cloud credentials or a three-hour soak.

Report only evidence-backed findings in severity order:
- P0: likely source-data loss/corruption, covert recording, or destructive security issue.
- P1: serious reliability, duplicate billing, lifecycle, privacy or release blocker.
- P2: material correctness/UX/maintainability issue.
- P3: low-risk improvement.

For every finding include: concise title, exact file and one-based line(s), triggering sequence, impact, why existing guards/tests do not cover it, and the smallest regression test that would fail before a fix. Separate confirmed defects from hypotheses. Also provide:
- a requirements-to-code coverage table;
- a list of unsupported production-readiness claims;
- an assessment of the physical test matrix;
- a final verdict: NO-GO, CONDITIONAL GO, or GO, with explicit conditions.

Do not recommend a wholesale rewrite. Do not change versions or credentials. Do not claim tests were run unless you show their command and outcome. End after the audit report and wait for authorization before editing.
```

## Recommended model usage

GPT-5.6 Sol can implement and verify this project. A fresh GPT-6 Astra pass is useful because it supplies independent judgment, not because any specific part is beyond Sol. Use Astra at **high** or **xhigh** reasoning for the prompt above; reserve it for the audit rather than routine UI/documentation work.
