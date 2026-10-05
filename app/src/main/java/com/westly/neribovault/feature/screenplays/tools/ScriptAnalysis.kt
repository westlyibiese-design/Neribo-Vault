package com.westly.neribovault.feature.screenplays.tools

import com.westly.neribovault.core.util.countWords
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.PaginatedScript
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import com.westly.neribovault.feature.screenplays.engine.ScriptPaginator
import com.westly.neribovault.feature.screenplays.engine.speakerOf
import java.util.Locale

private const val PAGE_ROWS = 54
private const val SHORT_SCRIPT_ROWS = 30

/** One speaker: how many cues they have and in how many scenes they speak. */
data class SpeakerStat(val name: String, val speeches: Int, val scenes: Int)

/** The scene that takes the most printed rows. */
data class LongestScene(val number: Int, val heading: String, val rows: Int)

/** Everything the Script stats screen shows. */
data class ScriptStats(
    val pages: Int,
    val scenes: Int,
    val words: Int,
    val characters: Int,
    val speeches: Int,
    val runningTime: String,
    val dialoguePercent: Int,
    val actionPercent: Int,
    val longestScene: LongestScene?,
    val topSpeaker: SpeakerStat?,
) {
    /** True when there is at least one printed page. */
    val hasContent: Boolean get() = pages > 0
}

/** Pure statistics over a block list. */
object ScriptAnalysis {

    fun analyze(blocks: List<ScriptBlock>): ScriptStats {
        val paginated = ScriptPaginator.paginate(blocks)
        val speakers = speakerStats(blocks)
        val rows = paginated.pages.flatMap { it.rows }
        val dialogueRows = rows.count { isDialogueType(it.type) }
        val actionRows = rows.size - dialogueRows
        var dialoguePercent = percentOf(dialogueRows, rows.size)
        var actionPercent = percentOf(actionRows, rows.size)
        val remainder = if (rows.isEmpty()) 0 else 100 - (dialoguePercent + actionPercent)
        if (remainder != 0) {
            // Rounding left a gap or an overlap: the larger share absorbs it.
            if (dialogueRows >= actionRows) dialoguePercent += remainder else actionPercent += remainder
        }
        return ScriptStats(
            pages = paginated.pageCount,
            scenes = paginated.scenes.size,
            words = blocks.sumOf { countWords(ScriptPaginator.stripNotes(it.text)) },
            characters = speakers.size,
            speeches = blocks.count { it.type == BlockType.CHARACTER },
            runningTime = runningTime(paginated.pageCount, rows.size),
            dialoguePercent = dialoguePercent,
            actionPercent = actionPercent,
            longestScene = longestScene(paginated),
            topSpeaker = speakers.firstOrNull(),
        )
    }

    /**
     * One entry per distinct speaker (case-insensitive), most speeches first and then by name.
     * Cues before the first scene heading count as one extra group, so "scenes" is at least 1.
     */
    fun speakerStats(blocks: List<ScriptBlock>): List<SpeakerStat> {
        val speeches = LinkedHashMap<String, Int>()
        val groups = HashMap<String, MutableSet<Int>>()
        var group = 0
        for (block in blocks) {
            if (block.type == BlockType.SCENE_HEADING && ScriptPaginator.stripNotes(block.text).isNotBlank()) {
                group += 1
            } else if (block.type == BlockType.CHARACTER) {
                val name = speakerOf(block.text).uppercase()
                if (name.isNotEmpty()) {
                    speeches[name] = (speeches[name] ?: 0) + 1
                    groups.getOrPut(name) { HashSet() }.add(group)
                }
            }
        }
        return speeches.map { (name, count) -> SpeakerStat(name, count, groups[name]?.size ?: 0) }
            .sortedWith(compareByDescending<SpeakerStat> { it.speeches }.thenBy { it.name })
    }

    private fun isDialogueType(type: BlockType): Boolean =
        type == BlockType.CHARACTER || type == BlockType.PARENTHETICAL || type == BlockType.DIALOGUE

    /** Whole-number percentage of [part] in [total], rounded half up. */
    private fun percentOf(part: Int, total: Int): Int =
        if (total == 0) 0 else (part * 200 + total) / (total * 2)

    private fun runningTime(pageCount: Int, printedRows: Int): String =
        if (pageCount == 0 || printedRows < SHORT_SCRIPT_ROWS) "Under a minute" else "About $pageCount min"

    private fun longestScene(paginated: PaginatedScript): LongestScene? {
        val scenes = paginated.scenes
        if (scenes.isEmpty()) return null
        val rows = paginated.pages.flatMap { it.rows }
        var best: LongestScene? = null
        for ((position, scene) in scenes.withIndex()) {
            val end = if (position + 1 < scenes.size) scenes[position + 1].blockIndex else Int.MAX_VALUE
            val count = rows.count { it.blockIndex >= scene.blockIndex && it.blockIndex < end }
            val current = best
            if (current == null || count > current.rows) {
                best = LongestScene(scene.number, scene.heading, count)
            }
        }
        return best
    }

    /** "2 pages", "1.5 pages" or "12 lines": a scene's length from its printed rows. */
    fun lengthLabel(rows: Int): String {
        if (rows < PAGE_ROWS) return if (rows == 1) "1 line" else "$rows lines"
        if (rows % PAGE_ROWS == 0) {
            val whole = rows / PAGE_ROWS
            return if (whole == 1) "1 page" else "$whole pages"
        }
        return String.format(Locale.US, "%.1f pages", rows / PAGE_ROWS.toDouble())
    }
}
