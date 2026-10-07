package com.westly.neribovault.feature.lyrics.engine

/** Reads and writes the Lyrics text format: sections as paragraphs, labels in square brackets. */
object LyricsFormat {

    private val labelLine = Regex("^\\[(.*)\\]$")
    private val verseLabel = Regex("^verse( \\d+)?$")
    private val spaces = Regex("\\s+")

    /** Parses a song's content into sections. Never throws; never drops text. */
    fun parse(content: String): List<SongSection> {
        val lines = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trimEnd() }
        val sections = ArrayList<SongSection>()
        val paragraph = ArrayList<String>()
        for (line in lines) {
            if (line.isEmpty()) {
                flush(paragraph, sections)
            } else {
                paragraph.add(line)
            }
        }
        flush(paragraph, sections)
        return sections
    }

    private fun flush(paragraph: MutableList<String>, sections: MutableList<SongSection>) {
        if (paragraph.isEmpty()) return
        sections.add(sectionOf(paragraph.toList()))
        paragraph.clear()
    }

    private fun sectionOf(paragraph: List<String>): SongSection {
        val first = paragraph.first()
        val match = labelLine.matchEntire(first.trim())
        val inner = match?.groupValues?.get(1)?.trim()
        // A bracket line with an empty label, or with a "]" inside it, is kept as plain text, so nothing is lost.
        if (inner != null && inner.isNotEmpty() && !inner.contains(']') && !first.startsWith("\\")) {
            val text = paragraph.drop(1).joinToString("\n")
            return classify(inner, text)
        }
        val lines = paragraph.toMutableList()
        val lead = first.length - first.trimStart().length
        if (first.startsWith("\\[", startIndex = lead)) {
            lines[0] = first.removeRange(lead, lead + 1)
        }
        return SongSection(SectionType.OTHER, "", lines.joinToString("\n"))
    }

    private fun classify(inner: String, text: String): SongSection {
        val key = inner.replace(spaces, " ").lowercase()
        val type = when {
            verseLabel.matches(key) -> SectionType.VERSE
            key == "pre-chorus" || key == "pre chorus" || key == "prechorus" -> SectionType.PRE_CHORUS
            key == "intro" -> SectionType.INTRO
            key == "chorus" -> SectionType.CHORUS
            key == "bridge" -> SectionType.BRIDGE
            key == "hook" -> SectionType.HOOK
            key == "interlude" -> SectionType.INTERLUDE
            key == "outro" -> SectionType.OUTRO
            else -> null
        }
        return if (type != null) {
            SongSection(type, "", text)
        } else {
            SongSection(SectionType.OTHER, inner, text)
        }
    }

    /**
     * Writes sections as Lyrics text; parse(serialize(x)) == x for sections produced by parse or by
     * the editor. Ends with exactly one "\n" (or is "" for no sections).
     */
    fun serialize(sections: List<SongSection>): String {
        val paragraphs = ArrayList<String>()
        var verseNumber = 0
        for (section in sections) {
            if (section.type == SectionType.VERSE) verseNumber += 1
            val textLines = section.text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            val header: String? = when (section.type) {
                SectionType.INTRO -> "[Intro]"
                SectionType.VERSE -> "[Verse $verseNumber]"
                SectionType.PRE_CHORUS -> "[Pre-Chorus]"
                SectionType.CHORUS -> "[Chorus]"
                SectionType.BRIDGE -> "[Bridge]"
                SectionType.HOOK -> "[Hook]"
                SectionType.INTERLUDE -> "[Interlude]"
                SectionType.OUTRO -> "[Outro]"
                SectionType.OTHER -> {
                    val clean = cleanLabel(section.label)
                    if (clean.isEmpty()) null else "[$clean]"
                }
            }
            if (header == null) {
                if (textLines.isEmpty()) continue
                val first = textLines[0]
                val lead = first.length - first.trimStart().length
                if (first.startsWith("[", startIndex = lead)) {
                    textLines[0] = first.substring(0, lead) + "\\" + first.substring(lead)
                }
                paragraphs.add(textLines.joinToString("\n"))
            } else {
                paragraphs.add((listOf(header) + textLines).joinToString("\n"))
            }
        }
        if (paragraphs.isEmpty()) return ""
        return paragraphs.joinToString("\n\n") + "\n"
    }

    /** The printed label of the section at [index]: "Intro", "Verse 2", ..., or the custom label ("Section" when blank). */
    fun labelAt(sections: List<SongSection>, index: Int): String {
        val section = sections[index]
        return when (section.type) {
            SectionType.INTRO -> "Intro"
            SectionType.VERSE -> "Verse " + (sections.subList(0, index + 1).count { it.type == SectionType.VERSE })
            SectionType.PRE_CHORUS -> "Pre-Chorus"
            SectionType.CHORUS -> "Chorus"
            SectionType.BRIDGE -> "Bridge"
            SectionType.HOOK -> "Hook"
            SectionType.INTERLUDE -> "Interlude"
            SectionType.OUTRO -> "Outro"
            SectionType.OTHER -> cleanLabel(section.label).ifEmpty { "Section" }
        }
    }

    /** A custom label as it may be written: no line breaks, no "]", trimmed. */
    private fun cleanLabel(label: String): String =
        label.replace('\n', ' ').replace('\r', ' ').replace("]", "").trim()
}
