import re
with open('app/src/main/java/com/puhetrack/app/ui/RecorderScreen.kt', 'r') as f:
    text = f.read()

# Undo the bad sed
text = text.replace('Text(text = status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)\n                    Spacer(Modifier.height(4.dp))\n                    Text(', 'Text(')
# Wait, let's fix indentation too if it was messed up
text = text.replace('Text(text = status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)\n                    Spacer(Modifier.height(4.dp))\n                    Text(', 'Text(')

# Now, add the status text ONLY at the right spot.
target = '''Text(
                        text = if (isRecording) formatElapsed(elapsedSec) else "Ready",'''
replacement = '''Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (isRecording) formatElapsed(elapsedSec) else "Ready",'''

text = text.replace(target, replacement)
text = text.replace('color = MaterialTheme.colorScheme.onSurface\n                    )', 'color = MaterialTheme.colorScheme.onSurface\n                    )\n                    }')

with open('app/src/main/java/com/puhetrack/app/ui/RecorderScreen.kt', 'w') as f:
    f.write(text)
