package com.westly.neribovault.feature.coder.files

import com.westly.neribovault.feature.coder.editor.smart.CoderFind

/**
 * One place where the search text was found. [line] is 1-based. [snippet] is the trimmed line (or a window of it) of at
 * most [CoderProjectSearch.MAX_SNIPPET_CHARS] characters; [highlightStart] until [highlightEnd] (end exclusive) is the match
 * inside it. [cutStart] and [cutEnd] say that the line goes on before or after the snippet, so the screen can add "…".
 */
data class SearchHit(
    val line: Int,
    val snippet: String,
    val highlightStart: Int,
    val highlightEnd: Int,
    val cutStart: Boolean,
    val cutEnd: Boolean,
)

/** The hits found inside one file, in the order they appear in it. */
data class FileHits(val path: String, val hits: List<SearchHit>)

/**
 * What a search found. [refused] is true when the query was not allowed (nothing was searched). [nameMatches] are the
 * paths of files whose name contains the query; [files] hold the text matches; [textMatchCount] is how many text matches
 * are listed in all; [truncated] is true when more matches exist than are listed.
 */
data class SearchResults(
    val query: String,
    val refused: Boolean,
    val nameMatches: List<String>,
    val files: List<FileHits>,
    val textMatchCount: Int,
    val truncated: Boolean,
) {
    /** File name matches plus text matches, as listed. */
    val totalCount: Int get() = nameMatches.size + textMatchCount
}

/**
 * The search across all files of a project. Pure: it works on the (path, text) pairs it is given and knows nothing about
 * the disk, so the debug self-test checks every rule. The pairs are read one at a time, so the caller may produce the text
 * lazily; a file that cannot be searched (a picture, a file that is too big) is given with an empty text, and then only its
 * name is looked at.
 */
object CoderProjectSearch {
    /** The shortest query that is searched. */
    const val MIN_QUERY_CHARS = 2

    /** The most file name matches that are listed. */
    const val MAX_NAME_MATCHES = 20

    /** The most text matches that are listed in all. */
    const val MAX_TEXT_MATCHES = 200

    /** The most text matches listed for one file. */
    const val MAX_PER_FILE = 20

    /** The longest snippet, in characters. */
    const val MAX_SNIPPET_CHARS = 120

    /** True when [query] may be searched: at least 2 characters, not only spaces and on one line. */
    fun isQueryAllowed(query: String): Boolean =
        query.length >= MIN_QUERY_CHARS && query.isNotBlank() && '\n' !in query && '\r' !in query

    /** False for the app's own `.coder*` folders and for anything inside `node_modules` or `.git`: they are never searched or listed. */
    fun isSearchable(path: String): Boolean {
        if (path.isEmpty() || isCoderHiddenPath(path)) return false
        return path.split('/').none { it == "node_modules" || it == ".git" }
    }

    /**
     * Looks for [query] in the file names (always ignoring case, at most [MAX_NAME_MATCHES]) and in the texts (at most
     * [maxResults] matches in all and [MAX_PER_FILE] per file; [caseSensitive] and [wholeWord] apply here). The order of
     * [files] is kept. Matches never overlap and never cross a line. A refused query gives an empty result with
     * [SearchResults.refused] set.
     */
    fun search(
        files: Sequence<Pair<String, String>>,
        query: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        maxResults: Int = MAX_TEXT_MATCHES,
    ): SearchResults {
        if (!isQueryAllowed(query) || maxResults <= 0) {
            return SearchResults(query, true, emptyList(), emptyList(), 0, false)
        }
        val names = ArrayList<String>()
        val groups = ArrayList<FileHits>()
        var textCount = 0
        var moreExists = false
        val iterator = files.iterator()
        while (iterator.hasNext()) {
            val (path, text) = iterator.next()
            if (!isSearchable(path)) continue
            if (names.size < MAX_NAME_MATCHES && CoderPaths.nameOf(path).contains(query, ignoreCase = true)) names.add(path)
            if (text.isNotEmpty() && (textCount < maxResults || !moreExists)) {
                val ranges = CoderFind.findAll(text, query, caseSensitive, wholeWord)
                if (ranges.isNotEmpty()) {
                    val room = maxResults - textCount
                    if (room <= 0) {
                        // The list is full; this file only proves that there is more.
                        moreExists = true
                    } else {
                        val shown = ranges.take(minOf(room, MAX_PER_FILE))
                        if (ranges.size > shown.size) moreExists = true
                        groups.add(FileHits(path, hitsFor(text, shown)))
                        textCount += shown.size
                    }
                }
            }
            if (textCount >= maxResults && moreExists && names.size >= MAX_NAME_MATCHES) break
        }
        return SearchResults(query, false, names, groups, textCount, moreExists)
    }

    /** The offset where the 1-based [line] starts in [text]. A line past the end gives the start of the last line; line 1 or less gives 0. */
    fun lineStartOffset(text: String, line: Int): Int {
        if (line <= 1) return 0
        var current = 1
        for (index in text.indices) {
            if (text[index] == '\n') {
                current++
                if (current == line) return index + 1
            }
        }
        return text.lastIndexOf('\n') + 1
    }

    /** The hits for [ranges] (inclusive character ranges in ascending order), with their line numbers. One pass over the text. */
    private fun hitsFor(text: String, ranges: List<IntRange>): List<SearchHit> {
        val out = ArrayList<SearchHit>(ranges.size)
        var line = 1
        var scanned = 0
        for (range in ranges) {
            val start = range.first
            while (scanned < start) {
                if (text[scanned] == '\n') line++
                scanned++
            }
            out.add(hitAt(text, line, start, range.last + 1))
        }
        return out
    }

    private fun hitAt(text: String, line: Int, start: Int, end: Int): SearchHit {
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        var lineEnd = text.indexOf('\n', start)
        if (lineEnd < 0) lineEnd = text.length
        val matchEnd = minOf(end, lineEnd)
        if (lineEnd > matchEnd && text[lineEnd - 1] == '\r') lineEnd--
        // Indentation and trailing spaces are trimmed, but never into the match itself.
        var from = lineStart
        while (from < start && (text[from] == ' ' || text[from] == '\t')) from++
        var to = lineEnd
        while (to > matchEnd && (text[to - 1] == ' ' || text[to - 1] == '\t')) to--
        var windowStart = from
        var windowEnd = to
        if (to - from > MAX_SNIPPET_CHARS) {
            val matchLength = matchEnd - start
            if (matchLength >= MAX_SNIPPET_CHARS) {
                windowStart = start
                windowEnd = start + MAX_SNIPPET_CHARS
            } else {
                windowStart = maxOf(from, start - (MAX_SNIPPET_CHARS - matchLength) / 2)
                windowEnd = windowStart + MAX_SNIPPET_CHARS
                if (windowEnd > to) {
                    windowEnd = to
                    windowStart = maxOf(from, windowEnd - MAX_SNIPPET_CHARS)
                }
            }
            // Never cut a character that is written with two chars in half.
            if (windowStart > from && windowStart < windowEnd && text[windowStart].isLowSurrogate()) windowStart++
            if (windowEnd < to && windowEnd > windowStart && text[windowEnd - 1].isHighSurrogate()) windowEnd--
        }
        val highlightStart = (start - windowStart).coerceAtLeast(0)
        val highlightEnd = (minOf(matchEnd, windowEnd) - windowStart).coerceIn(highlightStart, windowEnd - windowStart)
        return SearchHit(
            line = line,
            snippet = text.substring(windowStart, windowEnd),
            highlightStart = highlightStart,
            highlightEnd = highlightEnd,
            cutStart = windowStart > from,
            cutEnd = windowEnd < to,
        )
    }
}
