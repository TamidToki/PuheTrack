package com.puhetrack.app.batch

import org.json.JSONArray
import org.json.JSONObject

data class BatchTranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val timingProvenance: String
)

data class BatchTranscript(
    val segments: List<BatchTranscriptSegment>,
    val providerRequestId: String? = null
) {
    val text: String get() = segments.joinToString(" ") { it.text }.trim()

    fun toJson(): String = JSONObject()
        .put("providerRequestId", providerRequestId ?: JSONObject.NULL)
        .put("segments", JSONArray().apply {
            segments.forEach { segment ->
                put(
                    JSONObject()
                        .put("text", segment.text)
                        .put("startMs", segment.startMs)
                        .put("endMs", segment.endMs)
                        .put("timingProvenance", segment.timingProvenance)
                )
            }
        })
        .toString()

    companion object {
        fun fromJson(text: String): BatchTranscript {
            val o = JSONObject(text)
            val arr = o.getJSONArray("segments")
            return BatchTranscript(
                segments = List(arr.length()) { index ->
                    val segment = arr.getJSONObject(index)
                    BatchTranscriptSegment(
                        text = segment.getString("text"),
                        startMs = segment.getLong("startMs"),
                        endMs = segment.getLong("endMs"),
                        timingProvenance = segment.optString("timingProvenance", "unknown")
                    )
                },
                providerRequestId = if (o.isNull("providerRequestId")) null else o.optString("providerRequestId")
            )
        }
    }
}
