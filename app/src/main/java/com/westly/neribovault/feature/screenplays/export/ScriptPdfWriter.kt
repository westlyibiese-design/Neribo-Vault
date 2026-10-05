package com.westly.neribovault.feature.screenplays.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.screenplays.engine.PageRow
import com.westly.neribovault.feature.screenplays.engine.ScriptPage
import com.westly.neribovault.feature.screenplays.engine.ScriptPaginator
import com.westly.neribovault.feature.screenplays.engine.defaultNoticeText
import com.westly.neribovault.feature.screenplays.engine.wrapText
import java.io.OutputStream
import java.time.Year

/** How many pages the PDF has: [totalPages] counts every page, [scriptPages] only the script. */
data class PdfSummary(val totalPages: Int, val scriptPages: Int)

private class GridLine(val row: Int, val col: Int, val text: String)

private sealed class PdfPage {
    class Title(val lines: List<GridLine>) : PdfPage()
    class Notice(val lines: List<GridLine>) : PdfPage()
    class Script(val page: ScriptPage) : PdfPage()
}

/** Draws a screenplay as a PDF with [PdfDocument]. Call it from a background dispatcher. */
object ScriptPdfWriter {

    /** Writes [screenplay] to [out]. Throws [IllegalStateException] ("Nothing to export") for an empty script. */
    fun write(screenplay: ScreenplayEntity, out: OutputStream): PdfSummary {
        val blocks = Fountain.parse(screenplay.content)
        val script = ScriptPaginator.paginate(blocks)
        if (script.pages.none { page -> page.rows.any { it.text.isNotBlank() } }) {
            throw IllegalStateException("Nothing to export")
        }

        val pages = ArrayList<PdfPage>()
        pages.add(PdfPage.Title(titleLines(screenplay)))
        val notice = noticeFor(screenplay)
        if (notice.isNotBlank()) pages.add(PdfPage.Notice(noticeLines(notice)))
        for (page in script.pages) pages.add(PdfPage.Script(page))

        val paints = PdfPaints()
        val document = PdfDocument()
        try {
            for ((index, page) in pages.withIndex()) {
                val info = PdfDocument.PageInfo.Builder(
                    PdfLayoutConstants.PAGE_WIDTH_PT,
                    PdfLayoutConstants.PAGE_HEIGHT_PT,
                    index + 1,
                ).create()
                val pdfPage = document.startPage(info)
                try {
                    drawPage(pdfPage.canvas, page, index, pages, paints)
                } finally {
                    document.finishPage(pdfPage)
                }
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return PdfSummary(totalPages = pages.size, scriptPages = script.pageCount)
    }

    private fun drawPage(canvas: Canvas, page: PdfPage, index: Int, all: List<PdfPage>, paints: PdfPaints) {
        when (page) {
            is PdfPage.Title -> page.lines.forEach { drawGridLine(canvas, it.row, it.col, it.text, paints) }
            is PdfPage.Notice -> page.lines.forEach { drawGridLine(canvas, it.row, it.col, it.text, paints) }
            is PdfPage.Script -> drawScriptPage(canvas, page.page, index, all, paints)
        }
        canvas.drawText(
            PdfLayoutConstants.FOOTER_TEXT,
            PdfLayoutConstants.FOOTER_CENTER_X_PT,
            PdfLayoutConstants.FOOTER_BASELINE_PT,
            paints.footer,
        )
    }

    private fun drawScriptPage(canvas: Canvas, page: ScriptPage, index: Int, all: List<PdfPage>, paints: PdfPaints) {
        for (row in page.rows) drawPageRow(canvas, row, paints)
        val isFirstScriptPage = all.take(index).none { it is PdfPage.Script }
        if (!isFirstScriptPage) {
            val baseline = baselineFor(PdfLayoutConstants.PAGE_NUMBER_TOP_PT, paints.text)
            canvas.drawText("${page.number}.", PdfLayoutConstants.PAGE_NUMBER_RIGHT_PT, baseline, paints.pageNumber)
        }
    }

    private fun drawPageRow(canvas: Canvas, row: PageRow, paints: PdfPaints) {
        if (row.type == BlockType.PAGE_BREAK) return
        drawGridLine(canvas, row.row, row.col, row.text, paints)
    }

    private fun drawGridLine(canvas: Canvas, row: Int, col: Int, text: String, paints: PdfPaints) {
        if (row !in 0..PdfLayoutConstants.LAST_ROW) return
        val clean = cleanText(text)
        if (clean.isEmpty()) return
        val x = col * PdfLayoutConstants.COL_WIDTH_PT
        val rowTop = PdfLayoutConstants.TOP_MARGIN_PT + row * PdfLayoutConstants.ROW_HEIGHT_PT
        canvas.drawText(clean, x, baselineFor(rowTop, paints.text), paints.text)
    }

    /** Tabs become one space; control characters and unpaired surrogates are dropped. */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\t' -> sb.append(' ')
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    sb.append(c).append(text[i + 1])
                    i += 1
                }
                Character.isSurrogate(c) -> Unit
                c.isISOControl() -> Unit
                else -> sb.append(c)
            }
            i += 1
        }
        return sb.toString()
    }

    private fun baselineFor(rowTop: Float, paint: Paint): Float {
        val fm = paint.fontMetrics
        return rowTop + (PdfLayoutConstants.ROW_HEIGHT_PT - (fm.descent - fm.ascent)) / 2f - fm.ascent
    }

    private fun centeredCol(line: String): Int =
        (PdfLayoutConstants.CENTER_COL - line.length / 2).coerceAtLeast(0)

    private fun titleLines(screenplay: ScreenplayEntity): List<GridLine> {
        val result = ArrayList<GridLine>()
        val titleText = screenplay.title.trim().ifEmpty { PdfLayoutConstants.UNTITLED }
        var row = PdfLayoutConstants.TITLE_START_ROW
        for (line in wrapText(titleText, PdfLayoutConstants.TITLE_WIDTH)) {
            result.add(GridLine(row, centeredCol(line), line))
            row += 1
        }
        val author = screenplay.author.trim()
        if (author.isNotEmpty()) {
            row += 1
            val label = PdfLayoutConstants.WRITTEN_BY
            result.add(GridLine(row, centeredCol(label), label))
            row += 2
            for (line in wrapText(author, PdfLayoutConstants.TITLE_WIDTH)) {
                result.add(GridLine(row, centeredCol(line), line))
                row += 1
            }
        }
        val contact = screenplay.contact.trim()
        if (contact.isNotEmpty()) {
            val lines = wrapText(contact, PdfLayoutConstants.CONTACT_WIDTH).takeLast(PdfLayoutConstants.GRID_ROWS)
            val first = PdfLayoutConstants.LAST_ROW - (lines.size - 1)
            for ((i, line) in lines.withIndex()) {
                result.add(GridLine(first + i, PdfLayoutConstants.LEFT_COL, line))
            }
        }
        return result
    }

    private fun noticeFor(screenplay: ScreenplayEntity): String {
        if (!screenplay.noticeEnabled) return ""
        return if (screenplay.noticeText.isBlank()) {
            defaultNoticeText(screenplay.author, Year.now().value)
        } else {
            screenplay.noticeText
        }
    }

    private fun noticeLines(notice: String): List<GridLine> {
        val lines = wrapText(notice.trimEnd(), PdfLayoutConstants.NOTICE_WIDTH).takeLast(PdfLayoutConstants.GRID_ROWS)
        val first = if (PdfLayoutConstants.NOTICE_START_ROW + lines.size > PdfLayoutConstants.GRID_ROWS) {
            PdfLayoutConstants.GRID_ROWS - lines.size
        } else {
            PdfLayoutConstants.NOTICE_START_ROW
        }
        return lines.mapIndexed { i, line -> GridLine(first + i, PdfLayoutConstants.LEFT_COL, line) }
    }
}

/** The three paints: text and page number are scaled so each character advances exactly 7.2 pt. */
private class PdfPaints {
    val text: Paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = PdfLayoutConstants.TEXT_SIZE_PT
        color = 0xFF000000.toInt()
        val m = measureText("M")
        if (m > 0f) textScaleX = PdfLayoutConstants.COL_WIDTH_PT / m
    }
    val footer: Paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = PdfLayoutConstants.FOOTER_SIZE_PT
        color = PdfLayoutConstants.FOOTER_COLOR
        textAlign = Paint.Align.CENTER
    }
    val pageNumber: Paint = Paint(text).apply {
        textAlign = Paint.Align.RIGHT
    }
}
