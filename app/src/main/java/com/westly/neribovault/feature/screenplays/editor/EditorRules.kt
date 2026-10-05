package com.westly.neribovault.feature.screenplays.editor

import com.westly.neribovault.feature.screenplays.engine.BlockType
import java.util.Locale

/** One tappable suggestion: [label] is shown on the chip, [newText] replaces the focused block's text. */
data class Suggestion(val label: String, val newText: String)

/** What a change in a block's text field means. */
sealed interface FieldEdit {
    /** Ordinary typing, deleting or pasting. [text] has no sentinel. */
    data class Typed(val text: String) : FieldEdit

    /** Enter was pressed: the text is split into [before] and [after] the cursor. */
    data class Enter(val before: String, val after: String) : FieldEdit

    /** Backspace was pressed with the cursor at the very start of the block. */
    object BackspaceAtStart : FieldEdit
}

/** The result of splitting a block with Enter. */
data class SplitResult(val blocks: List<EditorBlock>, val focusId: String, val cursor: Int)

/** Pure editing rules: no Android, Compose or coroutine code. */
object EditorRules {
    /** Invisible first character of every editable field; removing it means "Backspace at the start". */
    const val SENTINEL: Char = '\u200B'
    const val SENTINEL_STRING: String = "\u200B"

    private val SCENE_STARTS = listOf("INT. ", "EXT. ", "EST. ", "INT./EXT. ", "I/E ")
    private val SCENE_PREFIX = Regex(
        "^(INT\\.?/EXT\\.?|I/E\\.?|INT\\.?|EXT\\.?|EST\\.?)(?:\\s+|\$)",
        RegexOption.IGNORE_CASE,
    )
    private val TIMES = listOf(
        "DAY", "NIGHT", "MORNING", "AFTERNOON", "EVENING",
        "DAWN", "DUSK", "LATER", "CONTINUOUS", "SAME TIME",
    )
    private val PARENTHETICALS = listOf(
        "beat", "pause", "quietly", "whispering", "laughing", "angrily", "sarcastic", "into phone",
    )
    private val TRANSITIONS = listOf(
        "CUT TO:", "SMASH CUT TO:", "DISSOLVE TO:", "MATCH CUT TO:", "FADE OUT.", "FADE TO BLACK.", "FADE IN:",
    )

    // ---- text rules ----

    /** Upper-cases scene headings, characters and transitions; other text is left as typed. */
    fun transformText(type: BlockType, text: String): String = when (type) {
        BlockType.SCENE_HEADING, BlockType.CHARACTER, BlockType.TRANSITION -> text.uppercase(Locale.ROOT)
        BlockType.ACTION, BlockType.PARENTHETICAL, BlockType.DIALOGUE, BlockType.PAGE_BREAK -> text
    }

    /** True when [text] starts like a scene heading ("INT. ", "EXT. ", "EST. ", "INT./EXT. ", "I/E "). */
    fun startsSceneHeading(text: String): Boolean = SCENE_STARTS.any { text.startsWith(it, ignoreCase = true) }

    /** The text a block gets when its type changes from [from] to [to]. */
    fun convertText(text: String, from: BlockType, to: BlockType): String {
        var result = text
        if (to == BlockType.PARENTHETICAL && result.length >= 2 && result.startsWith("(") && result.endsWith(")")) {
            result = result.substring(1, result.length - 1)
        }
        if (to != BlockType.ACTION && to != BlockType.DIALOGUE) result = result.replace('\n', ' ')
        if (from == to) return result
        return transformText(to, result)
    }

    /** Tidies text for a block of [type]: single line unless the type allows line breaks, then case rules. */
    private fun tidy(type: BlockType, text: String): String {
        val flat = if (type == BlockType.ACTION || type == BlockType.DIALOGUE) text else text.replace('\n', ' ')
        return transformText(type, flat)
    }

    // ---- Enter, chips ----

    /** The type of the block created when Enter is pressed in a block of [type]. */
    fun nextType(type: BlockType): BlockType = when (type) {
        BlockType.SCENE_HEADING -> BlockType.ACTION
        BlockType.ACTION -> BlockType.ACTION
        BlockType.CHARACTER -> BlockType.DIALOGUE
        BlockType.PARENTHETICAL -> BlockType.DIALOGUE
        BlockType.DIALOGUE -> BlockType.CHARACTER
        BlockType.TRANSITION -> BlockType.SCENE_HEADING
        BlockType.PAGE_BREAK -> BlockType.ACTION
    }

    /** Whether the element-bar chip for [target] may be used when the previous block has [previousType]. */
    fun isChipEnabled(target: BlockType, previousType: BlockType?): Boolean = when (target) {
        BlockType.DIALOGUE -> previousType == BlockType.CHARACTER || previousType == BlockType.PARENTHETICAL
        BlockType.PARENTHETICAL ->
            previousType == BlockType.CHARACTER ||
                previousType == BlockType.PARENTHETICAL ||
                previousType == BlockType.DIALOGUE
        BlockType.SCENE_HEADING, BlockType.ACTION, BlockType.CHARACTER,
        BlockType.TRANSITION, BlockType.PAGE_BREAK -> true
    }

    /**
     * Works out what a change of a field's raw value means. [oldRaw] and [newRaw] both carry the
     * leading [SENTINEL] (the new one is missing it after a Backspace at the start). [cursor] is
     * the new selection start in raw coordinates.
     */
    fun classifyEdit(oldRaw: String, newRaw: String, cursor: Int): FieldEdit {
        val hadSentinel = oldRaw.startsWith(SENTINEL)
        val hasSentinel = newRaw.startsWith(SENTINEL)
        if (hadSentinel && !hasSentinel && newRaw == oldRaw.substring(1)) return FieldEdit.BackspaceAtStart

        val offset = if (hasSentinel) 1 else 0
        val newClean = newRaw.replace(SENTINEL_STRING, "")
        val oldClean = oldRaw.replace(SENTINEL_STRING, "")
        val added = newClean.count { it == '\n' } - oldClean.count { it == '\n' }
        if (added <= 0) return FieldEdit.Typed(newClean)

        var index = cursor - offset - 1
        if (index !in newClean.indices || newClean[index] != '\n') {
            index = firstDifference(oldClean, newClean)
            if (index !in newClean.indices || newClean[index] != '\n') index = newClean.indexOf('\n')
        }
        return FieldEdit.Enter(
            before = newClean.substring(0, index),
            after = newClean.substring(index + 1),
        )
    }

    private fun firstDifference(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        for (i in 0 until limit) {
            if (a[i] != b[i]) return i
        }
        return limit
    }

    /**
     * Splits the block at [index] into [before] (stays) and [after] (a new block of the next type).
     * When the new block would be a Dialogue right above another Dialogue, [after] is joined onto
     * that Dialogue instead, so two Dialogue blocks never sit next to each other.
     */
    fun splitBlock(
        blocks: List<EditorBlock>,
        index: Int,
        before: String,
        after: String,
        newBlockId: String,
    ): SplitResult {
        val current = blocks[index]
        val newType = nextType(current.type)
        val beforeText = tidy(current.type, before.trimEnd())
        val afterText = tidy(newType, after.trimStart())
        val updated = blocks.toMutableList()
        updated[index] = current.copy(text = beforeText)

        val next = blocks.getOrNull(index + 1)
        if (newType == BlockType.DIALOGUE && next != null && next.type == BlockType.DIALOGUE) {
            val merged = if (afterText.isBlank()) next.text else afterText + " " + next.text
            updated[index + 1] = next.copy(text = merged)
            return SplitResult(updated, next.id, if (afterText.isBlank()) 0 else afterText.length)
        }
        updated.add(index + 1, EditorBlock(newBlockId, newType, afterText))
        return SplitResult(updated, newBlockId, 0)
    }

    /**
     * Turns any Parenthetical or Dialogue that no longer follows a valid block into an Action,
     * starting at [from]. The text is kept. Stops at the first block that is still valid.
     */
    fun repairOrphans(blocks: List<EditorBlock>, from: Int): List<EditorBlock> {
        val result = blocks.toMutableList()
        var i = from.coerceAtLeast(0)
        while (i < result.size) {
            val block = result[i]
            if (block.type != BlockType.DIALOGUE && block.type != BlockType.PARENTHETICAL) break
            val previous = if (i > 0) result[i - 1].type else null
            val valid = if (block.type == BlockType.DIALOGUE) {
                previous == BlockType.CHARACTER || previous == BlockType.PARENTHETICAL
            } else {
                previous == BlockType.CHARACTER ||
                    previous == BlockType.PARENTHETICAL ||
                    previous == BlockType.DIALOGUE
            }
            if (valid) break
            result[i] = block.copy(type = BlockType.ACTION)
            i++
        }
        return result
    }

    /**
     * Maps a block index of the saved script (blank blocks are not saved) to an index in [blocks].
     * Clamps to the last saved block.
     */
    fun jumpTargetIndex(blocks: List<EditorBlock>, savedIndex: Int): Int {
        val wanted = savedIndex.coerceAtLeast(0)
        var count = 0
        var lastSaved = -1
        for (i in blocks.indices) {
            if (blocks[i].isBlank) continue
            if (count == wanted) return i
            lastSaved = i
            count++
        }
        return if (lastSaved >= 0) lastSaved else 0
    }

    // ---- suggestions ----

    /** The suggestion chips for the block with id [focusedId]. */
    fun suggestions(blocks: List<EditorBlock>, focusedId: String?): List<Suggestion> {
        val index = blocks.indexOfFirst { it.id == focusedId }
        if (index < 0) return emptyList()
        return when (blocks[index].type) {
            BlockType.SCENE_HEADING -> sceneSuggestions(blocks, index)
            BlockType.CHARACTER -> characterSuggestions(blocks, index)
            BlockType.PARENTHETICAL -> PARENTHETICALS.map { Suggestion(it, it) }
            BlockType.TRANSITION -> TRANSITIONS.map { Suggestion(it, it) }
            BlockType.ACTION, BlockType.DIALOGUE, BlockType.PAGE_BREAK -> emptyList()
        }
    }

    private class SceneParts(
        val prefix: String,
        val location: String,
        val hasDash: Boolean,
        val time: String,
    )

    private fun parseScene(text: String): SceneParts? {
        val match = SCENE_PREFIX.find(text) ?: return null
        val prefix = match.groupValues[1]
        val rest = text.substring(match.range.last + 1)
        val dash = rest.indexOf(" - ")
        if (dash >= 0) {
            return SceneParts(prefix, rest.substring(0, dash).trim(), true, rest.substring(dash + 3).trim())
        }
        val trimmedRest = rest.trimEnd()
        if (trimmedRest.endsWith(" -")) {
            return SceneParts(prefix, trimmedRest.dropLast(2).trim(), true, "")
        }
        return SceneParts(prefix, rest.trim(), false, "")
    }

    private fun sceneSuggestions(blocks: List<EditorBlock>, index: Int): List<Suggestion> {
        val text = blocks[index].text
        if (text.isBlank()) {
            return listOf(
                Suggestion("INT.", "INT. "),
                Suggestion("EXT.", "EXT. "),
                Suggestion("INT./EXT.", "INT./EXT. "),
            )
        }
        val parts = parseScene(text) ?: return emptyList()
        val out = ArrayList<Suggestion>()
        if (!parts.hasDash) {
            knownLocations(blocks, index)
                .filter { it != parts.location && it.startsWith(parts.location) }
                .take(8)
                .forEach { out.add(Suggestion(it, parts.prefix + " " + it)) }
        }
        if (parts.location.isNotBlank()) {
            val typedTime = parts.time.uppercase(Locale.ROOT)
            TIMES
                .filter { typedTime.isEmpty() || it.startsWith(typedTime) }
                .forEach { out.add(Suggestion(it, parts.prefix + " " + parts.location + " - " + it)) }
        }
        return out
    }

    /** Locations of the other scene headings, the latest in the script first, without repeats. */
    private fun knownLocations(blocks: List<EditorBlock>, focusIndex: Int): List<String> {
        val seen = LinkedHashSet<String>()
        for (i in blocks.indices.reversed()) {
            if (i == focusIndex) continue
            val block = blocks[i]
            if (block.type != BlockType.SCENE_HEADING) continue
            val parts = parseScene(block.text) ?: continue
            if (parts.location.isNotBlank()) seen.add(parts.location)
        }
        return seen.toList()
    }

    private fun characterSuggestions(blocks: List<EditorBlock>, index: Int): List<Suggestion> {
        val text = blocks[index].text
        if (text.contains('(')) return emptyList()
        val typed = text.trim()
        val counts = HashMap<String, Int>()
        val lastSeen = HashMap<String, Int>()
        for (i in blocks.indices) {
            if (i == index) continue
            val block = blocks[i]
            if (block.type != BlockType.CHARACTER) continue
            val name = block.text.substringBefore('(').trim()
            if (name.isEmpty()) continue
            counts[name] = (counts[name] ?: 0) + 1
            lastSeen[name] = i
        }
        val names = counts.keys
            .sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }
                    .thenByDescending { lastSeen[it] ?: -1 },
            )
            .filter { it != typed && (typed.isEmpty() || it.startsWith(typed)) }
            .take(10)
        val out = ArrayList<Suggestion>()
        names.forEach { out.add(Suggestion(it, it)) }
        if (typed.isNotEmpty()) {
            out.add(Suggestion("(V.O.)", "$typed (V.O.)"))
            out.add(Suggestion("(O.S.)", "$typed (O.S.)"))
        }
        return out
    }
}
