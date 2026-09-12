import re
with open('app/src/main/java/com/puhetrack/app/stt/Transcriber.kt', 'r') as f:
    text = f.read()

text = text.replace('fun acceptPcm16(data: ByteArray, length: Int)', 'fun acceptPcm16(data: ByteArray, length: Int)\n    fun flush() {}')

with open('app/src/main/java/com/puhetrack/app/stt/Transcriber.kt', 'w') as f:
    f.write(text)
