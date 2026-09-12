package com.puhetrack.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtil {
    fun wrapPcm16(pcm: ByteArray, length: Int, sampleRate: Int = 16000): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + length)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(length)
        return bb.array() + pcm.copyOf(length)
    }
}
