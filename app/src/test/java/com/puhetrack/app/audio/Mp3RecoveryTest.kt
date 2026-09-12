package com.puhetrack.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream

class Mp3RecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun recoveryDropsJunkPrefixAndIncompleteTrailingFrame() {
        val source = temporary.newFile("capture.mp3.part")
        val destination = temporary.newFile("capture.mp3.recovery")
        destination.delete()

        source.outputStream().use { raw ->
            raw.write(byteArrayOf(1, 2, 3, 4, 5))
            DataOutputStream(raw).use { output ->
                repeat(2) { writeMpeg2Layer3Frame(output) }
                output.writeInt(frameHeader())
                output.write(ByteArray(40))
            }
        }

        val recovered = Mp3Recovery.recoverCopy(source, destination)

        assertEquals(2, recovered.encodedFrames)
        assertEquals(1_152L, recovered.sampleFrames)
        assertEquals(576L, recovered.durableBytes)
        assertEquals(576L, destination.length())
        assertTrue(destination.readBytes().take(2) == listOf(0xff.toByte(), 0xf3.toByte()))

        val inspected = Mp3Recovery.inspect(destination)
        assertEquals(recovered.encodedFrames, inspected.encodedFrames)
        assertEquals(recovered.sampleFrames, inspected.sampleFrames)
    }

    @Test
    fun randomBytesAreNotPromotedToRecording() {
        val source = temporary.newFile("junk.mp3.part")
        val destination = temporary.newFile("junk.mp3.recovery")
        destination.delete()
        source.writeBytes(ByteArray(2_000) { (it * 31).toByte() })

        val recovered = Mp3Recovery.recoverCopy(source, destination)

        assertEquals(0, recovered.encodedFrames)
        assertEquals(0L, recovered.durableBytes)
        assertTrue(!destination.exists())
    }

    private fun writeMpeg2Layer3Frame(output: DataOutputStream) {
        output.writeInt(frameHeader())
        output.write(ByteArray(284))
    }

    private fun frameHeader(): Int =
        0xffe00000.toInt() or // sync
            (2 shl 19) or // MPEG-2
            (1 shl 17) or // Layer III
            (1 shl 16) or // no CRC
            (8 shl 12) or // 64 kbps
            (2 shl 10) // 16 kHz
}
