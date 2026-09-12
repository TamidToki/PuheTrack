# PuheTrack — original plan (superseded)

This filename is retained so old links do not break. The original Vosk/WAV prototype plan is no longer the product architecture.

Use these documents instead:

- `PRODUCT.md` — authoritative current behavior and architecture.
- `IMPLEMENTATION_NOTES.md` — completed 2.14 reliability work.
- `TESTING.md` — automated evidence and physical-device requirements.
- `RELEASE_CHECKLIST.md` — remaining release gates.
- `ED_AUDIT_PROMPT.md` — independent final audit prompt.

The current product has two explicit modes: recorded Live Translate (Android 13+) with finished Finnish/English text, and durable exact-30-minute captured-audio recording followed by background transcription/translation. Both modes archive audio. The production app does not use Vosk or the prototype marker-file queue.
