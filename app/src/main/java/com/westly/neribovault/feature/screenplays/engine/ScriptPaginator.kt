package com.westly.neribovault.feature.screenplays.engine

/** One printed row of a page. [row] is 0..53 and [col] is the left column in the 75-column grid. */
data class PageRow(
    val row: Int,
    val col: Int,
    val text: String,
    val blockIndex: Int,
    val type: BlockType,
)

/** One laid-out page; [number] starts at 1. */
data class ScriptPage(val number: Int, val rows: List<PageRow>)

/** A scene heading and the page its first row lands on; [number] starts at 1. */
data class SceneInfo(val blockIndex: Int, val number: Int, val heading: String, val page: Int)

/** The result of laying a script out into pages. An empty script has zero pages. */
data class PaginatedScript(val pages: List<ScriptPage>, val scenes: List<SceneInfo>) {
    val pageCount: Int get() = pages.size
}

private val NOTE_PATTERN = Regex("\\[\\[.*?\\]\\]", RegexOption.DOT_MATCHES_ALL)
private val SPACE_RUN_PATTERN = Regex(" {2,}")

/**
 * Lays blocks out on US Letter pages: 54 rows by 75 columns, text from column 15 to 75.
 * This is the single source of truth for page layout (page counts, scene pages and the PDF).
 */
object ScriptPaginator {
    /** Removes "[[note]]" text (and collapses the double spaces this leaves). */
    fun stripNotes(text: String): String {
        if (!text.contains("[[")) return text
        val removed = NOTE_PATTERN.replace(text, "")
        if (removed == text) return text
        return SPACE_RUN_PATTERN.replace(removed, " ").split('\n').joinToString("\n") { it.trim() }
    }

    fun paginate(blocks: List<ScriptBlock>): PaginatedScript = PageLayout(buildItems(blocks)).run()
}

private const val PAGE_ROWS = 54
private const val RIGHT_EDGE = 75
private const val LEFT_MARGIN = 15
private const val CUE_COL = 37
private const val CUE_WIDTH = 38
private const val CONTD_SUFFIX = " (CONT'D)"
private const val MORE_TEXT = "(MORE)"

/** A block that will be printed: its index in the original list and its text without notes. */
private class LayoutItem(val index: Int, val type: BlockType, val text: String)

/** One row of a block before it is placed: its left column and text. */
private class Cell(val col: Int, val text: String)

private fun buildItems(blocks: List<ScriptBlock>): List<LayoutItem> {
    val result = ArrayList<LayoutItem>()
    for ((index, block) in blocks.withIndex()) {
        if (block.type == BlockType.PAGE_BREAK) {
            result.add(LayoutItem(index, block.type, ""))
        } else {
            val text = ScriptPaginator.stripNotes(block.text)
            if (text.isNotBlank()) result.add(LayoutItem(index, block.type, text))
        }
    }
    return result
}

/** Positions (in [items]) of cues that get an automatic "(CONT'D)". */
private fun findContdItems(items: List<LayoutItem>): Set<Int> {
    val result = HashSet<Int>()
    var lastSpeaker: String? = null
    for ((position, item) in items.withIndex()) {
        if (item.type == BlockType.SCENE_HEADING) {
            lastSpeaker = null
        } else if (item.type == BlockType.CHARACTER) {
            val speaker = speakerOf(item.text)
            val previous = lastSpeaker
            if (previous != null && speaker.equals(previous, ignoreCase = true)) {
                result.add(position)
            }
            lastSpeaker = speaker
        }
    }
    return result
}

private class PageLayout(private val items: List<LayoutItem>) {
    private val contdItems: Set<Int> = findContdItems(items)
    private val pages = ArrayList<ScriptPage>()
    private val scenes = ArrayList<SceneInfo>()
    private var current = ArrayList<PageRow>()
    private var row = 0

    fun run(): PaginatedScript {
        var groupCue: String? = null
        for (k in items.indices) {
            val item = items[k]
            val prev: BlockType? = if (k > 0) items[k - 1].type else null
            if (item.type == BlockType.PAGE_BREAK) {
                if (row > 0) flushPage()
                groupCue = null
            } else {
                val inGroup = (item.type == BlockType.PARENTHETICAL || item.type == BlockType.DIALOGUE) &&
                    (prev == BlockType.CHARACTER || prev == BlockType.PARENTHETICAL || prev == BlockType.DIALOGUE)
                if (item.type == BlockType.CHARACTER) {
                    groupCue = item.text
                } else if (!inGroup) {
                    groupCue = null
                }
                val cue: String? = if (item.type == BlockType.DIALOGUE && inGroup) groupCue else null
                placeItem(k, item, inGroup, cue)
            }
        }
        flushPage()
        return PaginatedScript(pages.toList(), scenes.toList())
    }

    private fun placeItem(k: Int, item: LayoutItem, inGroup: Boolean, cue: String?) {
        val cells = rowsOf(k, withContd = true)
        val rawGap = when {
            item.type == BlockType.SCENE_HEADING -> 2
            inGroup -> 0
            else -> 1
        }
        var gap = if (row == 0) 0 else rawGap
        val anchor = anchorRows(k, item, cells.size)
        if (gap + anchor > PAGE_ROWS - row && row != 0) {
            flushPage()
            gap = 0
        }
        row += gap

        if (row + cells.size <= PAGE_ROWS) {
            registerScene(item)
            for (cell in cells) addRow(cell.col, cell.text, item.index, item.type)
            return
        }
        if (item.type != BlockType.ACTION && item.type != BlockType.DIALOGUE) {
            // This kind of block cannot be split: move it to the next page whole.
            flushPage()
            registerScene(item)
            for (cell in cells) {
                if (row >= PAGE_ROWS) flushPage()
                addRow(cell.col, cell.text, item.index, item.type)
            }
            return
        }
        splitAcrossPages(item, cells, cue)
    }

    /** The number of rows that must fit together at the start of the block. */
    private fun anchorRows(k: Int, item: LayoutItem, ownRows: Int): Int {
        val next: LayoutItem? = if (k + 1 < items.size) items[k + 1] else null
        val nextRows = if (next != null) rowsOf(k + 1, withContd = false).size else 0
        return when (item.type) {
            BlockType.SCENE_HEADING ->
                if (next == null) ownRows else ownRows + 1 + minOf(2, nextRows)
            BlockType.CHARACTER ->
                if (next != null && (next.type == BlockType.PARENTHETICAL || next.type == BlockType.DIALOGUE)) {
                    ownRows + minOf(2, nextRows)
                } else {
                    ownRows
                }
            BlockType.PARENTHETICAL ->
                if (next != null && next.type == BlockType.DIALOGUE) ownRows + minOf(2, nextRows) else ownRows
            else -> minOf(ownRows, 2)
        }
    }

    /**
     * Splits an action or a speech over pages, never leaving fewer than two rows behind or ahead.
     * A speech with a cue ends the page with "(MORE)" and continues under "NAME (CONT'D)".
     */
    private fun splitAcrossPages(item: LayoutItem, cells: List<Cell>, cue: String?) {
        var rest: List<Cell> = cells
        while (true) {
            var available = PAGE_ROWS - row
            if (cue != null) available -= 1
            val firstPart = minOf(available, rest.size - 2)
            if (firstPart < 2) {
                if (row > 0) flushPage()
                if (row + rest.size <= PAGE_ROWS) {
                    for (cell in rest) addRow(cell.col, cell.text, item.index, item.type)
                } else {
                    // Still too long for one page: fill pages row by row.
                    for (cell in rest) {
                        if (row >= PAGE_ROWS) flushPage()
                        addRow(cell.col, cell.text, item.index, item.type)
                    }
                }
                return
            }
            for (cell in rest.take(firstPart)) addRow(cell.col, cell.text, item.index, item.type)
            rest = rest.drop(firstPart)
            if (cue != null) addRow(CUE_COL, MORE_TEXT, item.index, BlockType.CHARACTER)
            flushPage()
            if (cue != null) {
                for (line in wrapText(cue + CONTD_SUFFIX, CUE_WIDTH)) {
                    addRow(CUE_COL, line, item.index, BlockType.CHARACTER)
                }
            }
            if (row + rest.size <= PAGE_ROWS) {
                for (cell in rest) addRow(cell.col, cell.text, item.index, item.type)
                return
            }
        }
    }

    private fun registerScene(item: LayoutItem) {
        if (item.type != BlockType.SCENE_HEADING) return
        scenes.add(SceneInfo(item.index, scenes.size + 1, item.text, pages.size + 1))
    }

    private fun addRow(col: Int, text: String, blockIndex: Int, type: BlockType) {
        current.add(PageRow(row, col, text, blockIndex, type))
        row += 1
    }

    private fun flushPage() {
        if (current.isNotEmpty()) {
            pages.add(ScriptPage(pages.size + 1, current.toList()))
        }
        current = ArrayList()
        row = 0
    }

    /** The printed rows of item [k]; [withContd] adds the automatic "(CONT'D)" to a cue. */
    private fun rowsOf(k: Int, withContd: Boolean): List<Cell> {
        val item = items[k]
        return when (item.type) {
            BlockType.SCENE_HEADING -> wrapText(item.text.uppercase(), 60).map { Cell(LEFT_MARGIN, it) }
            BlockType.ACTION -> wrapText(item.text, 60).map { Cell(LEFT_MARGIN, it) }
            BlockType.CHARACTER -> {
                val printed = if (withContd && k in contdItems) item.text + CONTD_SUFFIX else item.text
                wrapText(printed, CUE_WIDTH).map { Cell(CUE_COL, it) }
            }
            BlockType.PARENTHETICAL ->
                wrapText("(" + item.text + ")", 24).mapIndexed { i, line -> Cell(if (i == 0) 31 else 32, line) }
            BlockType.DIALOGUE -> wrapText(item.text, 35).map { Cell(25, it) }
            BlockType.TRANSITION -> {
                val leftAligned = item.text.trim().equals("FADE IN:", ignoreCase = true)
                wrapText(item.text, 60).map { line ->
                    Cell(if (leftAligned) LEFT_MARGIN else RIGHT_EDGE - line.length, line)
                }
            }
            BlockType.PAGE_BREAK -> emptyList()
        }
    }
}
