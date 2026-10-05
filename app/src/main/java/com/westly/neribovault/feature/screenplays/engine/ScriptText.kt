package com.westly.neribovault.feature.screenplays.engine

private val PARENTHESIZED = Regex("\\([^)]*\\)")

/**
 * Greedy word wrap on spaces, by [String.length]. A word goes on the current row when
 * `currentLength + 1 + wordLength <= width`; a word longer than [width] is hard-split at [width].
 * Each "\n"-separated line wraps on its own and an empty line gives one empty row.
 */
fun wrapText(text: String, width: Int): List<String> {
    val limit = if (width < 1) 1 else width
    val rows = ArrayList<String>()
    for (line in text.split('\n')) {
        wrapLine(line, limit, rows)
    }
    return rows
}

private fun wrapLine(line: String, width: Int, rows: MutableList<String>) {
    val words = line.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) {
        rows.add("")
        return
    }
    var current = ""
    for (word in words) {
        if (word.length > width) {
            if (current.isNotEmpty()) {
                rows.add(current)
                current = ""
            }
            var rest = word
            while (rest.length > width) {
                rows.add(rest.substring(0, width))
                rest = rest.substring(width)
            }
            current = rest
        } else if (current.isEmpty()) {
            current = word
        } else if (current.length + 1 + word.length <= width) {
            current = "$current $word"
        } else {
            rows.add(current)
            current = word
        }
    }
    rows.add(current)
}

/** The speaker of a character cue: the text before the first "(", trimmed ("TUNDE (V.O.)" gives "TUNDE"). */
fun speakerOf(characterText: String): String = characterText.substringBefore('(').trim()

/**
 * True when [line], ignoring everything inside parentheses, has at least one letter and is
 * already upper case.
 */
fun isUpperCaseLine(line: String): Boolean {
    val outside = PARENTHESIZED.replace(line, "")
    return outside.any { it.isLetter() } && outside == outside.uppercase()
}

/**
 * The default copyright and confidentiality notice, used when the notice is on and the saved
 * notice text is blank. A blank [author] leaves only the year on the first line.
 */
fun defaultNoticeText(author: String, year: Int): String {
    val name = author.trim()
    val first = if (name.isEmpty()) "\u00A9 $year" else "\u00A9 $year $name"
    return first + "\nAll Rights Reserved\n\n" +
        "This screenplay is confidential and is the exclusive property of the author. " +
        "It is intended for reading purposes only. No portion of this script may be performed, " +
        "reproduced, or distributed without the prior written consent of the author."
}
