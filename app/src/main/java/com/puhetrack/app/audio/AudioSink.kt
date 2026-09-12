package com.puhetrack.app.audio

interface PcmSink {
    fun start()
    fun writeSamples(pcm16: ByteArray, length: Int)
    fun stop()
}
