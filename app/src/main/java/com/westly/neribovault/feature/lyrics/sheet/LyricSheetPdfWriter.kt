package com.westly.neribovault.feature.lyrics.sheet

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import java.io.OutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val LEFT = 60f
private const val RIGHT = 535f
private const val CONTENT_WIDTH = RIGHT - LEFT
private const val TOP = 60f
private const val BOTTOM = 770f
private const val CONTINUATION_INDENT = 12f
private const val FOOTER_BASELINE = 812f
private const val FOOTER_CENTER_X = 297.5f
private const val FIT_TOLERANCE = 0.01f

private const val TITLE_LINE_HEIGHT = 30f
private const val WRITER_LINE_HEIGHT = 18f
private const val WRITER_GAP = 4f
private const val META_LINE_HEIGHT = 14f
private const val META_GAP = 8f
private const val RULE_GAP = 12f
private const val AFTER_RULE_GAP = 20f
private const val LABEL_LINE_HEIGHT = 14f
private const val LABEL_GAP = 4f
private const val META_SEPARATOR = "   ·   "

/** Builds the lyric sheet PDF: A4, serif type, the same footer on every page. */
object LyricSheetPdfWriter {

    /** The line printed at the bottom of every page. */
    const val FOOTER_TEXT = "Written with Neribo Vault · © NERIBO GROUP"

    /**
     * Writes [song] as an A4 PDF to [out] and returns the page count. Does not close [out].
     * Throws [IllegalStateException] ("Nothing to export") when the song has no lyric lines.
     * Call this from a background dispatcher.
     */
    fun write(song: SongEntity, options: LyricSheetOptions, out: OutputStream): Int {
        val sections = LyricsFormat.parse(song.content)
        val lines = sections.map { section -> section.text.split('\n').filter { it.isNotBlank() } }
        if (lines.none { it.isNotEmpty() }) throw IllegalStateException("Nothing to export")
        val paints = SheetPaints(options.large)
        val labels = sections.indices.map { index ->
            if (options.showLabels) LyricsFormat.labelAt(sections, index).uppercase() else null
        }
        val pages = SheetLayout(paints, options.large).build(song, labels, lines)
        return render(pages, paints, out)
    }

    private fun render(pages: List<PageContent>, paints: SheetPaints, out: OutputStream): Int {
        val document = PdfDocument()
        try {
            val total = pages.size
            pages.forEachIndexed { index, content ->
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(info)
                try {
                    drawPage(page.canvas, content, paints, index + 1, total)
                } finally {
                    document.finishPage(page)
                }
            }
            document.writeTo(out)
            out.flush()
            return total
        } finally {
            document.close()
        }
    }

    private fun drawPage(canvas: Canvas, content: PageContent, paints: SheetPaints, number: Int, total: Int) {
        for (ruleY in content.rules) {
            canvas.drawLine(LEFT, ruleY, RIGHT, ruleY, paints.rule)
        }
        for (line in content.lines) {
            val metrics = line.paint.fontMetrics
            val baseline = line.top + (line.height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            canvas.drawText(line.text, line.x, baseline, line.paint)
        }
        canvas.drawText(FOOTER_TEXT, FOOTER_CENTER_X, FOOTER_BASELINE, paints.footerCenter)
        if (total > 1) {
            canvas.drawText("$number / $total", RIGHT, FOOTER_BASELINE, paints.footerRight)
        }
    }
}

/** All paints of one sheet. Every paint is anti-aliased; text is drawn exactly as typed. */
private class SheetPaints(large: Boolean) {
    val bodySize: Float = if (large) 16f else 13f

    val body: Paint = textPaint(Typeface.SERIF, bodySize, Color.BLACK)
    val title: Paint = textPaint(Typeface.create(Typeface.SERIF, Typeface.BOLD), 24f, Color.BLACK)
    val writer: Paint = textPaint(Typeface.create(Typeface.SERIF, Typeface.ITALIC), 13f, gray(0x55))
    val meta: Paint = textPaint(Typeface.SANS_SERIF, 10f, gray(0x77))
    val label: Paint = textPaint(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), 9f, gray(0x77)).apply {
        letterSpacing = 0.12f
    }
    val footerCenter: Paint = textPaint(Typeface.SANS_SERIF, 8f, gray(0x66)).apply {
        textAlign = Paint.Align.CENTER
    }
    val footerRight: Paint = textPaint(Typeface.SANS_SERIF, 8f, gray(0x66)).apply {
        textAlign = Paint.Align.RIGHT
    }
    val rule: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gray(0xCC)
        strokeWidth = 0.75f
        style = Paint.Style.STROKE
    }

    private fun textPaint(face: Typeface, size: Float, paintColor: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            color = paintColor
        }

    private fun gray(level: Int): Int = Color.rgb(level, level, level)
}

/** One line of text to draw: [top] is the top of its line box and [height] the line height. */
private class DrawLine(
    val text: String,
    val paint: Paint,
    val x: Float,
    val top: Float,
    val height: Float,
)

/** Everything drawn on one page apart from the footer. */
private class PageContent {
    val lines = ArrayList<DrawLine>()
    val rules = ArrayList<Float>()
}

/** One display row of lyric text; continuation rows of a wrapped line are indented. */
private class BodyRow(val text: String, val indented: Boolean)

/**
 * Lays the song out onto pages with a running [y]. A section is kept together on one page when it
 * fits on an empty page; a taller section is split between rows, keeping its label with at least
 * two rows and leaving at least two rows on the last part.
 */
private class SheetLayout(private val paints: SheetPaints, large: Boolean) {

    private val pages = ArrayList<PageContent>()
    private var page = PageContent().also { pages.add(it) }
    private var y = TOP

    private val bodyLineHeight = paints.bodySize * 1.45f
    private val afterSection = if (large) 20f else 16f

    fun build(song: SongEntity, labels: List<String?>, lines: List<List<String>>): List<PageContent> {
        addHeader(song)
        for (index in lines.indices) {
            addSection(labels[index], bodyRows(lines[index]))
        }
        return pages
    }

    // ---- header -------------------------------------------------------------------------------

    private fun addHeader(song: SongEntity) {
        val title = song.title.trim().ifEmpty { "Untitled song" }
        for (row in wrapText(title, paints.title, CONTENT_WIDTH, CONTENT_WIDTH)) {
            placeHeaderLine(row, paints.title, TITLE_LINE_HEIGHT)
        }
        val writer = song.writer.trim()
        if (writer.isNotEmpty()) {
            y += WRITER_GAP
            for (row in wrapText("by $writer", paints.writer, CONTENT_WIDTH, CONTENT_WIDTH)) {
                placeHeaderLine(row, paints.writer, WRITER_LINE_HEIGHT)
            }
        }
        val pieces = ArrayList<String>()
        if (song.songKey.isNotBlank()) pieces.add("Key: ${song.songKey.trim()}")
        val tempo = song.tempoBpm
        if (tempo != null) pieces.add("$tempo BPM")
        if (song.mood.isNotBlank()) pieces.add("Mood: ${song.mood.trim()}")
        if (pieces.isNotEmpty()) {
            y += META_GAP
            for (row in wrapPieces(pieces, META_SEPARATOR, paints.meta, CONTENT_WIDTH)) {
                placeHeaderLine(row, paints.meta, META_LINE_HEIGHT)
            }
        }
        if (fits(RULE_GAP)) {
            y += RULE_GAP
            page.rules.add(y)
            y += AFTER_RULE_GAP
        } else {
            startNewPage()
        }
    }

    private fun placeHeaderLine(text: String, paint: Paint, height: Float) {
        if (!fits(height) && y > TOP) startNewPage()
        placeLine(text, paint, LEFT, height)
    }

    // ---- sections -----------------------------------------------------------------------------

    private fun bodyRows(lines: List<String>): List<BodyRow> {
        val rows = ArrayList<BodyRow>()
        for (line in lines) {
            val wrapped = wrapText(line, paints.body, CONTENT_WIDTH, CONTENT_WIDTH - CONTINUATION_INDENT)
            wrapped.forEachIndexed { index, text -> rows.add(BodyRow(text, indented = index > 0)) }
        }
        return rows
    }

    private fun addSection(label: String?, rows: List<BodyRow>) {
        val labelRows = if (label == null) emptyList() else wrapText(label, paints.label, CONTENT_WIDTH, CONTENT_WIDTH)
        if (labelRows.isEmpty() && rows.isEmpty()) return
        val labelHeight = labelBlockHeight(labelRows)
        val total = labelHeight + rows.size * bodyLineHeight + afterSection
        if (total <= BOTTOM - TOP + FIT_TOLERANCE) {
            if (!fits(total)) startNewPage()
            placeLabel(labelRows)
            rows.forEach { placeBodyRow(it) }
            y += afterSection
        } else {
            addOversizedSection(labelRows, rows)
        }
    }

    private fun addOversizedSection(labelRows: List<String>, rows: List<BodyRow>) {
        var start = 0
        var first = true
        while (start < rows.size) {
            val labelHeight = if (first) labelBlockHeight(labelRows) else 0f
            val remaining = rows.size - start
            val fit = floor((BOTTOM - y - labelHeight + FIT_TOLERANCE) / bodyLineHeight).toInt()
            val take: Int
            if (remaining <= fit) {
                take = remaining
            } else {
                val minimum = if (first && labelRows.isNotEmpty()) 2 else 1
                val candidate = min(fit, remaining - 2)
                if (candidate < minimum && y > TOP) {
                    startNewPage()
                    continue
                }
                take = max(1, candidate)
            }
            if (first) placeLabel(labelRows)
            for (index in start until start + take) placeBodyRow(rows[index])
            start += take
            first = false
            if (start < rows.size) startNewPage()
        }
        y += afterSection
    }

    private fun labelBlockHeight(labelRows: List<String>): Float =
        if (labelRows.isEmpty()) 0f else labelRows.size * LABEL_LINE_HEIGHT + LABEL_GAP

    private fun placeLabel(labelRows: List<String>) {
        if (labelRows.isEmpty()) return
        for (row in labelRows) placeLine(row, paints.label, LEFT, LABEL_LINE_HEIGHT)
        y += LABEL_GAP
    }

    private fun placeBodyRow(row: BodyRow) {
        val x = if (row.indented) LEFT + CONTINUATION_INDENT else LEFT
        placeLine(row.text, paints.body, x, bodyLineHeight)
    }

    // ---- page helpers -------------------------------------------------------------------------

    private fun fits(height: Float): Boolean = y + height <= BOTTOM + FIT_TOLERANCE

    private fun startNewPage() {
        page = PageContent()
        pages.add(page)
        y = TOP
    }

    private fun placeLine(text: String, paint: Paint, x: Float, height: Float) {
        page.lines.add(DrawLine(text, paint, x, y, height))
        y += height
    }

    // ---- wrapping -----------------------------------------------------------------------------

    /**
     * Greedy word wrap with [paint]. The first row may be [firstWidth] wide and the rest [nextWidth].
     * A word wider than a row is split between characters; a mark is never separated from its letter.
     */
    private fun wrapText(text: String, paint: Paint, firstWidth: Float, nextWidth: Float): List<String> {
        val words = text.replace('\t', ' ').split(' ').filter { it.isNotEmpty() }
        val rows = ArrayList<String>()
        var current = ""
        var limit = firstWidth
        for (word in words) {
            val joined = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(joined) <= limit) {
                current = joined
                continue
            }
            if (current.isNotEmpty()) {
                rows.add(current)
                current = ""
                limit = nextWidth
            }
            var rest = word
            while (paint.measureText(rest) > limit) {
                val cut = fittingLength(rest, paint, limit)
                rows.add(rest.substring(0, cut))
                rest = rest.substring(cut)
                limit = nextWidth
            }
            current = rest
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /** Wraps [pieces] joined by [separator], keeping each piece whole unless it is too wide alone. */
    private fun wrapPieces(pieces: List<String>, separator: String, paint: Paint, width: Float): List<String> {
        val rows = ArrayList<String>()
        var current = ""
        for (piece in pieces) {
            val joined = if (current.isEmpty()) piece else current + separator + piece
            if (paint.measureText(joined) <= width) {
                current = joined
                continue
            }
            if (current.isNotEmpty()) {
                rows.add(current)
                current = ""
            }
            if (paint.measureText(piece) <= width) {
                current = piece
            } else {
                val parts = wrapText(piece, paint, width, width)
                rows.addAll(parts.dropLast(1))
                current = parts.lastOrNull().orEmpty()
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /** The longest prefix of [text] (at least one character with its marks) that fits in [limit]. */
    private fun fittingLength(text: String, paint: Paint, limit: Float): Int {
        var best = 0
        var end = clusterEnd(text, 0)
        while (end <= text.length) {
            if (paint.measureText(text, 0, end) > limit) break
            best = end
            if (end == text.length) break
            end = clusterEnd(text, end)
        }
        return if (best == 0) clusterEnd(text, 0) else best
    }

    /** The end index of the character at [start] together with any combining marks that follow it. */
    private fun clusterEnd(text: String, start: Int): Int {
        var end = start + Character.charCount(text.codePointAt(start))
        while (end < text.length && isMark(text.codePointAt(end))) {
            end += Character.charCount(text.codePointAt(end))
        }
        return end
    }

    private fun isMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
