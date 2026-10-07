package com.westly.neribovault.feature.lyrics.editor

import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.SectionType
import com.westly.neribovault.feature.lyrics.engine.SongSection

/** The result of splitting a section at a blank line: the new list and the section to focus. */
data class SplitOutcome(val sections: List<EditorSection>, val focusId: String)

/** Pure editing rules for the song editor: no Android, Compose or coroutine code. */
object EditorRules {
    /** Invisible first character of every editable field; removing it means "Backspace at the start". */
    const val SENTINEL: Char = '\u200B'
    const val SENTINEL_STRING: String = "\u200B"

    /** The label a new "Other" section gets. */
    const val DEFAULT_OTHER_LABEL = "Section"

    /** The longest custom label. */
    const val MAX_LABEL_LENGTH = 30

    private val blankLine = Regex("\n[ \t]*\n")
    private val leadingBlankLines = Regex("^(?:[ \t]*\n)+")
    private val reservedLabel = Regex(
        "^(?:verse(?: \\d+)?|pre-chorus|pre chorus|prechorus|intro|chorus|bridge|hook|interlude|outro)$",
    )
    private val spaces = Regex("\\s+")

    // ---- next type, labels ----

    /** The type of the section created when a blank line ends a section of [type]. */
    fun nextType(type: SectionType): SectionType = when (type) {
        SectionType.INTRO -> SectionType.VERSE
        SectionType.VERSE -> SectionType.CHORUS
        SectionType.PRE_CHORUS -> SectionType.CHORUS
        SectionType.CHORUS -> SectionType.VERSE
        SectionType.BRIDGE -> SectionType.CHORUS
        SectionType.HOOK -> SectionType.VERSE
        SectionType.INTERLUDE -> SectionType.VERSE
        SectionType.OUTRO -> SectionType.OTHER
        SectionType.OTHER -> SectionType.VERSE
    }

    /** The label stored for a new section of [type]: "Section" for Other, "" for every other type. */
    fun defaultLabel(type: SectionType): String =
        if (type == SectionType.OTHER) DEFAULT_OTHER_LABEL else ""

    /** The printed labels of all sections, through [LyricsFormat.labelAt]. */
    fun labelsOf(sections: List<EditorSection>): List<String> {
        val plain = sections.map { SongSection(it.type, it.label, it.text) }
        return plain.indices.map { LyricsFormat.labelAt(plain, it) }
    }

    /** The text shown on a type chip: the printed label in upper case. */
    fun chipText(label: String): String = label.uppercase()

    /** Checks a custom label. Returns an error message, or null when the label is fine. */
    fun labelError(raw: String): String? {
        val label = raw.trim()
        return when {
            label.isEmpty() -> "Enter a label"
            label.length > MAX_LABEL_LENGTH -> "Use $MAX_LABEL_LENGTH characters or fewer"
            label.contains(']') -> "Please leave out the ] character"
            reservedLabel.matches(label.replace(spaces, " ").lowercase()) ->
                "That is a built-in section. Pick it from the list instead"
            else -> null
        }
    }

    /** A label cleaned for storage (call only after [labelError] returned null). */
    fun cleanLabel(raw: String): String =
        raw.replace("\n", " ").replace("\r", " ").replace("]", "").trim()

    // ---- text rules ----

    /** Turns the raw text of a field into what the section stores: no zero-width sentinel. */
    fun stripSentinel(raw: String): String = raw.replace(SENTINEL_STRING, "")

    /** Works out what a change of a field's raw value means (both raw values carry the sentinel). */
    fun classifyEdit(oldRaw: String, newRaw: String): FieldEdit {
        if (oldRaw.startsWith(SENTINEL) && !newRaw.startsWith(SENTINEL) && newRaw == oldRaw.substring(1)) {
            return FieldEdit.BackspaceAtStart
        }
        return FieldEdit.Typed(stripSentinel(newRaw))
    }

    /** True when [text] contains an empty line (two line breaks in a row, spaces between them allowed). */
    fun hasBlankLine(text: String): Boolean = blankLine.containsMatchIn(text)

    /**
     * Splits [text] at its first empty line. Returns the part before it and the part after it
     * (leading empty lines removed; blank becomes ""), or null when there is no empty line.
     */
    private fun splitAtFirstBlank(text: String): Pair<String, String>? {
        val match = blankLine.find(text) ?: return null
        val before = text.substring(0, match.range.first)
        val rest = text.substring(match.range.last + 1).replace(leadingBlankLines, "")
        return before to (if (rest.isBlank()) "" else rest)
    }

    // ---- new sections ----

    /** The text of the nearest earlier chorus with text before position [insertIndex], or "". */
    fun prefilledChorusText(sections: List<EditorSection>, insertIndex: Int): String {
        var i = minOf(insertIndex, sections.size) - 1
        while (i >= 0) {
            val s = sections[i]
            if (s.type == SectionType.CHORUS && s.text.isNotBlank()) return s.text
            i--
        }
        return ""
    }

    /** Creates a section; an empty chorus is pre-filled from the nearest earlier chorus. */
    fun createSection(
        id: String,
        type: SectionType,
        label: String,
        text: String,
        before: List<EditorSection>,
        insertIndex: Int,
    ): EditorSection {
        val shownText = if (type == SectionType.CHORUS && text.isEmpty()) {
            prefilledChorusText(before, insertIndex)
        } else {
            text
        }
        return EditorSection(id, type, if (type == SectionType.OTHER) label else "", shownText)
    }

    /**
     * Applies the "Enter twice" rule to the section at [index], whose new text is [newText] and
     * contains an empty line. The part before the first empty line stays; the rest becomes new
     * sections below (more than one when the text holds several empty lines, for example after a
     * paste). Returns null when [newText] has no empty line.
     */
    fun splitAtBlankLine(
        sections: List<EditorSection>,
        index: Int,
        newText: String,
        newId: () -> String,
    ): SplitOutcome? {
        val first = splitAtFirstBlank(newText) ?: return null
        val result = sections.toMutableList()
        result[index] = result[index].copy(text = first.first)
        var type = nextType(result[index].type)
        var rest = first.second
        var insertAt = index + 1
        var focusId = ""
        while (true) {
            val more = splitAtFirstBlank(rest)
            val partText = more?.first ?: rest
            val created = createSection(
                id = newId(),
                type = type,
                label = defaultLabel(type),
                text = partText,
                before = result,
                insertIndex = insertAt,
            )
            result.add(insertAt, created)
            if (focusId.isEmpty()) focusId = created.id
            insertAt++
            if (more == null) break
            rest = more.second
            type = nextType(type)
        }
        return SplitOutcome(result, focusId)
    }

    // ---- moving ----

    /** Returns a copy of [sections] with the section at [from] moved to [to], or null when impossible. */
    fun moved(sections: List<EditorSection>, from: Int, to: Int): List<EditorSection>? {
        if (from !in sections.indices || to !in sections.indices || from == to) return null
        val copy = sections.toMutableList()
        val item = copy.removeAt(from)
        copy.add(to, item)
        return copy
    }

    // ---- jump mapping ----

    /** True when the section is written by [LyricsFormat.serialize] (an Other with no label and no text is not). */
    fun isWritten(section: EditorSection): Boolean {
        if (section.type != SectionType.OTHER) return true
        return cleanLabel(section.label).isNotEmpty() || section.text.isNotBlank()
    }

    /**
     * Maps [savedIndex], an index into the parsed saved song, to the in-memory list by counting
     * the sections that will be written. Clamped to the last written section.
     */
    fun jumpTargetIndex(sections: List<EditorSection>, savedIndex: Int): Int {
        if (sections.isEmpty()) return 0
        var written = 0
        var lastWritten = -1
        for (i in sections.indices) {
            if (!isWritten(sections[i])) continue
            if (written == savedIndex) return i
            written++
            lastWritten = i
        }
        return if (lastWritten >= 0) lastWritten else sections.lastIndex
    }
}
