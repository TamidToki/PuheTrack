import re

with open("app/src/main/java/com/puhetrack/app/RecordTranslateService.kt", "r") as f:
    text = f.read()

# Replace scope.launch { with explicit dispatchers
text = text.replace("tickerJob = scope.launch {", "tickerJob = scope.launch(Dispatchers.Main) {")
text = text.replace("partialTranslateJob = scope.launch {", "partialTranslateJob = scope.launch(Dispatchers.IO) {")
text = text.replace("scope.launch {", "scope.launch(Dispatchers.IO) {")

# Add wakeLock safety
old_wakelock = "acquireWakeLock()\n        requestAudioFocus()"
new_wakelock = """try {
            acquireWakeLock()
        } catch (e: Exception) {
            releaseWakeLock()
            throw e
        }
        requestAudioFocus()"""
text = text.replace(old_wakelock, new_wakelock)

with open("app/src/main/java/com/puhetrack/app/RecordTranslateService.kt", "w") as f:
    f.write(text)

