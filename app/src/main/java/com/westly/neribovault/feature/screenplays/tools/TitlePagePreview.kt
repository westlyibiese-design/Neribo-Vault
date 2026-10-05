package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.screenplays.engine.wrapText

/** The page is 85 columns by 66 rows (8.5 by 11 inch at 10 characters and 6 lines per inch). */
private const val PAGE_COLS = 85f
private const val PAGE_ROWS = 66f

/** Text rows of the 54-row grid sit this many page rows from the top (1 inch). */
private const val TOP_OFFSET_ROWS = 6

/** US Letter, in inches. The card keeps this shape (not columns over rows). */
private const val PAGE_WIDTH_INCH = 8.5f
private const val PAGE_HEIGHT_INCH = 11f

private const val LEFT_COL = 15
private const val CENTER_COL = 45
private const val TITLE_START_ROW = 16
private const val TITLE_WIDTH = 50
private const val CONTACT_WIDTH = 40
private const val NOTICE_WIDTH = 60
private const val NOTICE_START_ROW = 40
private const val LAST_ROW = 53
private const val GRID_ROWS = 54

/** One monospace character is 0.6 em wide. */
private const val MONO_ADVANCE = 0.6f

/** Footer size: 8 pt on a 612 pt wide page. */
private const val FOOTER_PT = 8f
private const val PAGE_WIDTH_PT = 612f

/** Footer distance from the bottom edge: half an inch on a 792 pt tall page. */
private const val FOOTER_BOTTOM_PT = 36f
private const val PAGE_HEIGHT_PT = 792f

internal const val PDF_FOOTER = "Written with Neribo Vault \u00B7 \u00A9 NERIBO GROUP"
private val FOOTER_GRAY = Color(0xFF666666)

/** One line of text at a grid position: [row] 0..53 and [col] 0..75. */
internal data class GridText(val row: Int, val col: Int, val text: String)

/**
 * The title page rows: the title centered from row 16, "written by" and the author below it, and
 * the contact block bottom-anchored so that its last row is row 53. A blank title prints UNTITLED.
 */
internal fun titlePageRows(title: String, author: String, contact: String): List<GridText> {
    val rows = ArrayList<GridText>()
    val titleText = title.trim().ifEmpty { "UNTITLED" }
    var row = TITLE_START_ROW
    for (line in wrapText(titleText, TITLE_WIDTH)) {
        rows.add(GridText(row, centeredCol(line), line))
        row += 1
    }
    val name = author.trim()
    if (name.isNotEmpty()) {
        row += 1
        val label = "written by"
        rows.add(GridText(row, centeredCol(label), label))
        row += 2
        for (line in wrapText(name, TITLE_WIDTH)) {
            rows.add(GridText(row, centeredCol(line), line))
            row += 1
        }
    }
    val contactText = contact.trim()
    if (contactText.isNotEmpty()) {
        val lines = wrapText(contactText, CONTACT_WIDTH).takeLast(GRID_ROWS)
        val first = LAST_ROW - (lines.size - 1)
        for ((i, line) in lines.withIndex()) {
            rows.add(GridText(first + i, LEFT_COL, line))
        }
    }
    return rows.filter { it.row in 0..LAST_ROW }
}

/** The copyright page rows: left aligned from row 40, or higher when the text would pass row 53. */
internal fun noticePageRows(notice: String): List<GridText> {
    val text = notice.trimEnd()
    if (text.isBlank()) return emptyList()
    val lines = wrapText(text, NOTICE_WIDTH).takeLast(GRID_ROWS)
    val first = if (NOTICE_START_ROW + lines.size > GRID_ROWS) GRID_ROWS - lines.size else NOTICE_START_ROW
    return lines.mapIndexed { i, line -> GridText(first + i, LEFT_COL, line) }
}

private fun centeredCol(line: String): Int = (CENTER_COL - line.length / 2).coerceAtLeast(0)

/**
 * A live preview of the printed title page and, when [showNotice] is on, the copyright page.
 * The paper stays white in dark mode.
 */
@Composable
fun TitlePagePreview(
    title: String,
    author: String,
    contact: String,
    showNotice: Boolean,
    notice: String,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        SectionHeader("Title page")
        PageCard(rows = titlePageRows(title, author, contact))
        val noticeRows = if (showNotice) noticePageRows(notice) else emptyList()
        if (noticeRows.isNotEmpty()) {
            SectionHeader("Copyright page")
            PageCard(rows = noticeRows)
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun PageCard(rows: List<GridText>) {
    val measurer = rememberTextMeasurer()
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PAGE_WIDTH_INCH / PAGE_HEIGHT_INCH)
            .background(Color.White)
            .border(1.dp, hairline),
    ) {
        val cellWidth = size.width / PAGE_COLS
        val rowHeight = size.height / PAGE_ROWS
        val bodyStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = (cellWidth / MONO_ADVANCE).toSp(),
            color = Color.Black,
        )
        for (item in rows) {
            if (item.text.isNotEmpty()) {
                drawText(
                    textMeasurer = measurer,
                    text = item.text,
                    topLeft = Offset(item.col * cellWidth, (TOP_OFFSET_ROWS + item.row) * rowHeight),
                    style = bodyStyle,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
        }
        val footerStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = (size.width * FOOTER_PT / PAGE_WIDTH_PT).toSp(),
            color = FOOTER_GRAY,
        )
        val footerWidth = measurer.measure(PDF_FOOTER, footerStyle).size.width.toFloat()
        val footerTop = size.height * (1f - (FOOTER_BOTTOM_PT + FOOTER_PT) / PAGE_HEIGHT_PT)
        drawText(
            textMeasurer = measurer,
            text = PDF_FOOTER,
            topLeft = Offset((size.width - footerWidth) / 2f, footerTop),
            style = footerStyle,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}
