package com.westly.neribovault.feature.screenplays.export

/** Fixed numbers for the PDF page: US Letter, 12 pt monospace, 10 characters and 6 lines per inch. */
internal object PdfLayoutConstants {
    const val PAGE_WIDTH_PT = 612
    const val PAGE_HEIGHT_PT = 792

    /** One column is 7.2 pt wide and one row is 12 pt tall. */
    const val COL_WIDTH_PT = 7.2f
    const val ROW_HEIGHT_PT = 12f

    /** The grid starts 1 inch (72 pt) from the top edge. */
    const val TOP_MARGIN_PT = 72f

    const val GRID_ROWS = 54
    const val LAST_ROW = 53

    const val TEXT_SIZE_PT = 12f
    const val FOOTER_SIZE_PT = 8f

    /** Page numbers sit half an inch from the top and end at column 75 (540 pt). */
    const val PAGE_NUMBER_TOP_PT = 36f
    const val PAGE_NUMBER_RIGHT_PT = 540f

    const val FOOTER_CENTER_X_PT = 306f
    const val FOOTER_BASELINE_PT = 760f
    const val FOOTER_COLOR = 0xFF666666.toInt()

    const val FOOTER_TEXT = "Written with Neribo Vault \u00B7 \u00A9 NERIBO GROUP"

    const val LEFT_COL = 15
    const val CENTER_COL = 45
    const val TITLE_START_ROW = 16
    const val TITLE_WIDTH = 50
    const val CONTACT_WIDTH = 40
    const val NOTICE_WIDTH = 60
    const val NOTICE_START_ROW = 40

    const val UNTITLED = "UNTITLED"
    const val WRITTEN_BY = "written by"
}
