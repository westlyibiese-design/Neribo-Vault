package com.westly.neribovault.feature.ideas

/** Most tags one idea can carry. */
internal const val MAX_IDEA_TAGS = 10

/** Longest a single tag can be, in characters. */
internal const val MAX_IDEA_TAG_LENGTH = 24

private val TAG_WHITESPACE = Regex("\\s+")

/**
 * Turns whatever the user typed into a tag: no control characters, no leading "#",
 * single spaces, lowercase, trimmed and at most [MAX_IDEA_TAG_LENGTH] characters.
 * Returns an empty string when nothing usable is left.
 */
internal fun normalizeIdeaTag(raw: String): String =
    raw.filterNot { it.isISOControl() }
        .trim()
        .removePrefix("#")
        .replace(TAG_WHITESPACE, " ")
        .lowercase()
        .take(MAX_IDEA_TAG_LENGTH)
        .trim()
