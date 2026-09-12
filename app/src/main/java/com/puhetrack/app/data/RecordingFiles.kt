package com.puhetrack.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class Seg(val fi: String, val en: String, val startMs: Long, val endMs: Long? = null)

fun sidecar(audio: File, ext: String): File =
    File(audio.parent, audio.nameWithoutExtension + "." + ext)

fun listAudioFiles(dir: File): List<File> {
    dir.mkdirs()
    return dir.listFiles { f ->
        f.isFile && (f.extension.lowercase() == "mp3" || f.extension.lowercase() == "wav")
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

fun readSegments(jsonFile: File): List<Seg> {
    if (!jsonFile.exists()) return emptyList()
    return try {
        val arr = JSONArray(jsonFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Seg(
                o.optString("fi"),
                o.optString("en"),
                o.optLong("startMs"),
                if (o.has("endMs") && !o.isNull("endMs")) o.optLong("endMs") else null
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun writeSegmentsJson(jsonFile: File, segs: List<Seg>) {
    val arr = JSONArray()
    segs.forEach { s ->
        arr.put(
            JSONObject()
                .put("fi", s.fi)
                .put("en", s.en)
                .put("startMs", s.startMs)
                .put("endMs", s.endMs ?: JSONObject.NULL)
        )
    }
    jsonFile.writeText(arr.toString())
}

fun relTime(ms: Long): String {
    val m = ms.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d", m / 3600000, (m % 3600000) / 60000, (m % 60000) / 1000)
}

fun srtTime(ms: Long): String {
    val m = ms.coerceAtLeast(0)
    return String.format(
        Locale.US, "%02d:%02d:%02d,%03d",
        m / 3600_000, (m % 3600_000) / 60_000, (m % 60_000) / 1000, m % 1000
    )
}

fun writeTxt(txtFile: File, segs: List<Seg>) {
    val sb = StringBuilder()
    segs.forEach { s ->
        sb.append("[${relTime(s.startMs)}]\nFI: ${s.fi}\nEN: ${s.en}\n\n")
    }
    txtFile.writeText(sb.toString())
}

fun writeSrt(srtFile: File, segs: List<Seg>) {
    if (segs.isEmpty()) return
    val sb = StringBuilder()
    segs.forEachIndexed { i, s ->
        val nextStart = segs.getOrNull(i + 1)?.startMs
        val end = s.endMs?.takeIf { it > s.startMs }
            ?: nextStart?.minus(200)?.coerceAtLeast(s.startMs + 800)
            ?: (s.startMs + maxOf(2000L, s.fi.length * 70L))
        sb.append("${i + 1}\n${srtTime(s.startMs)} --> ${srtTime(end)}\n${s.fi}\n")
        if (s.en.isNotBlank()) sb.append("${s.en}\n")
        sb.append("\n")
    }
    srtFile.writeText(sb.toString())
}
