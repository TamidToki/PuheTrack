import re

with open("app/src/main/java/com/puhetrack/app/RecordTranslateService.kt", "r") as f:
    content = f.read()

# 1. Update pauseRequested in loops
content = content.replace("if (_isPaused.value) {", "if (pauseRequested) {")

# 2. Add try/finally to audioLoop
# We need to wrap the while loop in try/finally
# It's easier to use sed or manual replacement.
