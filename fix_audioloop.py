import re
with open('app/src/main/java/com/puhetrack/app/RecordTranslateService.kt', 'r') as f:
    text = f.read()

old_loop = '''                if (!skipEnabled) {
                    if (!emitChunk(buffer, n)) break
                    continue
                }
                // Voice activity detection: drop long silence from the file
                // (transcript clock is frozen for dropped audio so .srt stays matched).
                val rms = computeRms(buffer, n)
                if (rms >= vadThreshold) {
                    if (!loggedSpeech) {
                        loggedSpeech = true
                        Log.i(TAG, "first speech detected (rms=$rms)")
                    }
                    hangover = hangoverChunks
                    silenceRunMs = 0L
                    if (skipping) {
                        skipping = false
                        while (preRoll.isNotEmpty()) {
                            val c = preRoll.removeFirst()
                            if (!emitChunk(c, c.size)) break
                        }
                        _status.value = "recording • ${_formatLabel.value}"
                    }
                    if (!emitChunk(buffer, n)) break
                } else if (hangover > 0) {
                    hangover--
                    silenceRunMs = 0L
                    if (!emitChunk(buffer, n)) break
                } else {
                    if (preRoll.size >= 4) preRoll.removeFirst()
                    preRoll.add(buffer.copyOf(n))
                    silenceRunMs += chunkMs
                    if (!skipping && silenceRunMs >= silenceThresholdMs) {
                        skipping = true
                        preRoll.clear() // held audio was all silence — drop it
                        _status.value = "recording (silence paused)"
                    }
                }'''

new_loop = '''                val rms = computeRms(buffer, n)
                
                // Track VAD unconditionally to flush the engine on silence boundaries (prevents word duplication)
                if (rms >= vadThreshold) {
                    silenceRunMs = 0L
                    skipping = false
                } else {
                    silenceRunMs += chunkMs
                    if (!skipping && silenceRunMs >= silenceThresholdMs) {
                        skipping = true
                        engine?.flush()
                    }
                }

                if (!skipEnabled) {
                    if (!emitChunk(buffer, n)) break
                    continue
                }

                // Voice activity detection: drop long silence from the file
                if (rms >= vadThreshold) {
                    if (!loggedSpeech) {
                        loggedSpeech = true
                        Log.i(TAG, "first speech detected (rms=$rms)")
                    }
                    hangover = hangoverChunks
                    if (_status.value.contains("paused")) {
                        while (preRoll.isNotEmpty()) {
                            val c = preRoll.removeFirst()
                            if (!emitChunk(c, c.size)) break
                        }
                        _status.value = "recording • ${_formatLabel.value}"
                    }
                    if (!emitChunk(buffer, n)) break
                } else if (hangover > 0) {
                    hangover--
                    if (!emitChunk(buffer, n)) break
                } else {
                    if (preRoll.size >= 4) preRoll.removeFirst()
                    preRoll.add(buffer.copyOf(n))
                    if (_status.value.contains("recording •")) {
                        preRoll.clear() // held audio was all silence — drop it
                        _status.value = "recording (silence paused)"
                    }
                }'''

text = text.replace(old_loop, new_loop)

with open('app/src/main/java/com/puhetrack/app/RecordTranslateService.kt', 'w') as f:
    f.write(text)
