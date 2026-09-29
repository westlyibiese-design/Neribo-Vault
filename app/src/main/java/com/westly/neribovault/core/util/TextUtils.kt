package com.westly.neribovault.core.util

private val WHITESPACE = Regex("\\s+")

/** Number of words in [text]. Blank text has zero words. */
fun countWords(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.split(WHITESPACE).size
}

/** A whitespace-collapsed preview of [text], with an ellipsis if it was cut. */
fun snippet(text: String, maxChars: Int = 140): String {
    val collapsed = text.replace(WHITESPACE, " ").trim()
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars).trimEnd() + "…"
}
