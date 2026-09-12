package com.puhetrack.app.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class WavWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun recoveryTruncatesAHalfSampleAndRepairsHeader() {
        val source = temporary.newFile("capture.wav.part")
        val recovered = temporary.newFile("capture.wav.recovery")
        recovered.delete()
        WavWriter(source, sampleRate = 4).apply {
            start()
            writeSamples(ByteArray(8) { it.toByte() }, 8)
            writeSamples(ByteArray(4) { (it + 8).toByte() }, 4)
            stop()
        }
        RandomAccessFile(source, "rw").use { file ->
            file.seek(file.length())
            file.write(99)
        }

        val frames = WavWriter.recoverCopy(source, recovered)

        assertEquals(6L, frames)
        assertEquals(56L, recovered.length())
        RandomAccessFile(recovered, "r").use { file ->
            file.seek(40)
            val littleEndianLength = Integer.reverseBytes(file.readInt())
            assertEquals(12, littleEndianLength)
        }
    }
}
