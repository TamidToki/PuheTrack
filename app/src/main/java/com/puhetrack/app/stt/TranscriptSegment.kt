package com.puhetrack.app.stt

data class TranscriptSegment(
    val id: Long = System.currentTimeMillis(),
    val textFi: String,
    val textEn: String,
    val textTranslit: String = "",
    val isFinal: Boolean = true,
    val startMs: Long,
    val translationUnavailable: Boolean = false
)
