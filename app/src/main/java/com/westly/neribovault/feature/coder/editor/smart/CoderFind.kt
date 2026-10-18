package com.westly.neribovault.feature.coder.editor.smart

/**
 * Everything the find bar shows. [matches] are inclusive character ranges in the open file, [current] is the
 * index of the match the editor is on (or -1), and [reveal] goes up each time the editor should scroll to it.
 */
data class FindState(
    val open: Boolean = false,
    val query: String = "",
    val replacement: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val matches: List<IntRange> = emptyList(),
    val current: Int = -1,
    val reveal: Int = 0,
)

/** Plain-text search and replace. No regular expressions. */
object CoderFind {
    /** The most matches that are found and shown; more than this and Replace all is not offered. */
    const val MAX_MATCHES = 5_000

    /**
     * Every place [query] occurs in [text], as inclusive ranges that never overlap, at most [MAX_MATCHES].
     * With [wholeWord] the characters on both sides must not be letters, digits or an underscore.
     * Works in one pass over the text.
     */
    fun findAll(text: String, query: String, caseSensitive: Boolean, wholeWord: Boolean): List<IntRange> {
        val m = query.length
        if (m == 0 || text.length < m) return emptyList()
        val fold = !caseSensitive
        val wanted = CharArray(m) { folded(query[it], fold) }
        val fail = IntArray(m)
        var k = 0
        for (i in 1 until m) {
            while (k > 0 && wanted[i] != wanted[k]) k = fail[k - 1]
            if (wanted[i] == wanted[k]) k++
            fail[i] = k
        }
        val found = ArrayList<IntRange>()
        var state = 0
        for (i in text.indices) {
            val ch = folded(text[i], fold)
            while (state > 0 && ch != wanted[state]) state = fail[state - 1]
            if (ch == wanted[state]) state++
            if (state == m) {
                val start = i - m + 1
                if (!wholeWord || isWholeWord(text, start, i + 1)) {
                    found.add(start..i)
                    if (found.size >= MAX_MATCHES) return found
                    state = 0
                } else {
                    state = fail[state - 1]
                }
            }
        }
        return found
    }

    /**
     * [text] with every range of [matches] replaced by [replacement]. Ranges must be in order; one that overlaps
     * the one before it or lies outside the text is skipped.
     */
    fun replaceAll(text: String, matches: List<IntRange>, replacement: String): String {
        if (matches.isEmpty()) return text
        val out = StringBuilder(text.length)
        var position = 0
        for (range in matches) {
            val start = range.first
            val end = range.last + 1
            if (start < position || end > text.length || end <= start) continue
            out.append(text, position, start)
            out.append(replacement)
            position = end
        }
        out.append(text, position, text.length)
        return out.toString()
    }

    /**
     * The index in [matches] to go to from [caret]. Forward is the first match that starts at or after the caret,
     * backward the last match that starts before it; both wrap around. -1 when there are no matches.
     */
    fun nextIndex(matches: List<IntRange>, caret: Int, forward: Boolean): Int {
        if (matches.isEmpty()) return -1
        if (forward) {
            for (i in matches.indices) if (matches[i].first >= caret) return i
            return 0
        }
        for (i in matches.indices.reversed()) if (matches[i].first < caret) return i
        return matches.size - 1
    }

    private fun folded(ch: Char, fold: Boolean): Char = if (fold) ch.lowercaseChar() else ch

    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private fun isWholeWord(text: String, start: Int, endExclusive: Int): Boolean {
        if (start > 0 && isWordChar(text[start - 1])) return false
        if (endExclusive < text.length && isWordChar(text[endExclusive])) return false
        return true
    }
}
