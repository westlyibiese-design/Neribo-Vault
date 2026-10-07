package com.westly.neribovault.feature.lyrics.engine

/** Totals for a song. [syllables] is an estimate. [longestLine] is in characters. */
data class LyricsStats(
    val sections: Int,
    val lines: Int,
    val words: Int,
    val syllables: Int,
    val longestLine: Int,
)

/** Counting and analysis over parsed sections. */
object LyricsAnalysis {

    private val spaces = Regex("\\s+")
    private val trailingPunctuation = Regex("[\\p{P}\\s]+$")

    private fun linesOf(section: SongSection): List<String> =
        section.text.split('\n').filter { it.isNotBlank() }

    fun stats(sections: List<SongSection>): LyricsStats {
        var lines = 0
        var words = 0
        var syllables = 0
        var longest = 0
        for (section in sections) {
            for (line in linesOf(section)) {
                lines += 1
                words += line.split(spaces).count { it.isNotEmpty() }
                syllables += SyllableCounter.countLine(line)
                longest = maxOf(longest, line.length)
            }
        }
        return LyricsStats(sections.size, lines, words, syllables, longest)
    }

    /** Estimated syllables of every non-blank line, per section. */
    fun lineSyllables(sections: List<SongSection>): List<List<Int>> =
        sections.map { section -> linesOf(section).map { SyllableCounter.countLine(it) } }

    /**
     * 0-based indexes of lines far from the section's median. Needs three or more lines; a line is an
     * outlier when it differs from the lower median by 3 or more syllables.
     */
    fun outlierLines(counts: List<Int>): Set<Int> {
        if (counts.size < 3) return emptySet()
        val median = counts.sorted()[(counts.size - 1) / 2]
        val result = LinkedHashSet<Int>()
        counts.forEachIndexed { index, count ->
            if (kotlin.math.abs(count - median) >= 3) result.add(index)
        }
        return result
    }

    /** The most repeated line (normalized) and how often it appears; null unless it appears at least twice. */
    fun mostRepeatedLine(sections: List<SongSection>): Pair<String, Int>? {
        val counts = LinkedHashMap<String, Int>()
        for (section in sections) {
            for (line in linesOf(section)) {
                val key = line.trim().lowercase().replace(spaces, " ").replace(trailingPunctuation, "")
                if (key.isEmpty()) continue
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        var best: Pair<String, Int>? = null
        for ((key, count) in counts) {
            val current = best
            if (current == null || count > current.second) best = key to count
        }
        return best?.takeIf { it.second >= 2 }
    }
}
