import re
with open('app/src/main/java/com/puhetrack/app/stt/SherpaEngine.kt', 'r') as f:
    text = f.read()

flush_code = '''
    override fun flush() {
        if (recognizer == null) return
        val chunk: ByteArray
        synchronized(buf) {
            if (buf.size() == 0) return
            chunk = buf.toByteArray()
            buf.reset()
        }
        decodeChunk(chunk)
    }
'''

text = text.replace('private fun decodeChunk(chunk: ByteArray) {', flush_code + '\n    private fun decodeChunk(chunk: ByteArray) {')

with open('app/src/main/java/com/puhetrack/app/stt/SherpaEngine.kt', 'w') as f:
    f.write(text)
