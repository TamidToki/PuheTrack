package com.puhetrack.app.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.puhetrack.app.data.Seg
import com.puhetrack.app.data.relTime
import java.io.File

object PdfExport {
    fun exportPdf(pdfFile: File, title: String, segs: List<Seg>) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 44f
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var canvas = page.canvas
        var y = margin + 10f

        val titlePaint = Paint().apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val timePaint = Paint().apply { textSize = 11f; color = 0xFF888888.toInt() }
        val fiPaint = Paint().apply { textSize = 13f }
        val enPaint = Paint().apply {
            textSize = 13f
            color = 0xFF0B57D0.toInt()
        }

        fun newPageIfNeeded(need: Float) {
            if (y + need > pageH - margin) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
                canvas = page.canvas
                y = margin
            }
        }

        fun drawWrapped(text: String, paint: Paint, indent: Float = 0f) {
            val maxW = pageW - margin * 2 - indent
            val words = text.split(' ')
            var line = StringBuilder()
            for (w in words) {
                val test = if (line.isEmpty()) w else "$line $w"
                if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                    newPageIfNeeded(18f)
                    canvas.drawText(line.toString(), margin + indent, y, paint)
                    y += 17f
                    line = StringBuilder(w)
                } else {
                    line = StringBuilder(test)
                }
            }
            if (line.isNotEmpty()) {
                newPageIfNeeded(18f)
                canvas.drawText(line.toString(), margin + indent, y, paint)
                y += 17f
            }
        }

        canvas.drawText(title, margin, y, titlePaint)
        y += 26f
        segs.forEach { s ->
            newPageIfNeeded(60f)
            canvas.drawText(relTime(s.startMs), margin, y, timePaint)
            y += 16f
            drawWrapped(s.fi, fiPaint)
            if (s.en.isNotBlank()) drawWrapped(s.en, enPaint)
            y += 10f
        }
        doc.finishPage(page)
        pdfFile.parentFile?.mkdirs()
        pdfFile.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    fun exportStudyPdf(
        pdfFile: File,
        title: String,
        segs: List<Seg>,
        vocab: List<com.puhetrack.app.study.VocabItem>,
        key: List<Seg>
    ) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 44f
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var canvas = page.canvas
        var y = margin + 10f

        val titlePaint = Paint().apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headPaint = Paint().apply {
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val timePaint = Paint().apply { textSize = 11f; color = 0xFF888888.toInt() }
        val fiPaint = Paint().apply { textSize = 13f }
        val enPaint = Paint().apply {
            textSize = 13f
            color = 0xFF0B57D0.toInt()
        }

        fun newPageIfNeeded(need: Float) {
            if (y + need > pageH - margin) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
                canvas = page.canvas
                y = margin
            }
        }

        fun drawWrapped(text: String, paint: Paint, indent: Float = 0f) {
            val maxW = pageW - margin * 2 - indent
            val words = text.split(' ')
            var line = StringBuilder()
            for (w in words) {
                val test = if (line.isEmpty()) w else "$line $w"
                if (paint.measureText(test) > maxW && line.isNotEmpty()) {
                    newPageIfNeeded(18f)
                    canvas.drawText(line.toString(), margin + indent, y, paint)
                    y += 17f
                    line = StringBuilder(w)
                } else {
                    line = StringBuilder(test)
                }
            }
            if (line.isNotEmpty()) {
                newPageIfNeeded(18f)
                canvas.drawText(line.toString(), margin + indent, y, paint)
                y += 17f
            }
        }

        fun sectionHead(t: String) {
            newPageIfNeeded(40f)
            y += 8f
            canvas.drawText(t, margin, y, headPaint)
            y += 20f
        }

        canvas.drawText("$title — study pack", margin, y, titlePaint)
        y += 26f

        if (key.isNotEmpty()) {
            sectionHead("Key sentences")
            key.forEach { s ->
                newPageIfNeeded(60f)
                canvas.drawText(
                    com.puhetrack.app.data.relTime(s.startMs), margin, y, timePaint
                )
                y += 16f
                drawWrapped(s.fi, fiPaint)
                if (s.en.isNotBlank()) drawWrapped(s.en, enPaint)
                y += 8f
            }
        }

        if (vocab.isNotEmpty()) {
            sectionHead("Vocabulary (most frequent)")
            vocab.forEach { v ->
                newPageIfNeeded(20f)
                val line = "${v.fi} — ${v.en.ifBlank { "…" }}  (×${v.count})"
                drawWrapped(line, fiPaint)
                y += 2f
            }
            y += 8f
        }

        if (segs.isNotEmpty()) {
            sectionHead("Full transcript")
            segs.forEach { s ->
                newPageIfNeeded(60f)
                canvas.drawText(
                    com.puhetrack.app.data.relTime(s.startMs), margin, y, timePaint
                )
                y += 16f
                drawWrapped(s.fi, fiPaint)
                if (s.en.isNotBlank()) drawWrapped(s.en, enPaint)
                y += 8f
            }
        }

        doc.finishPage(page)
        pdfFile.parentFile?.mkdirs()
        pdfFile.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
