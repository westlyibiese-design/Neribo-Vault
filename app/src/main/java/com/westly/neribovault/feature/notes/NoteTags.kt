package com.westly.neribovault.feature.notes

/** Most tags one note can carry. */
internal const val MAX_TAGS = 10

/** Longest a single tag can be, in characters. */
internal const val MAX_TAG_LENGTH = 24

private val TAG_WHITESPACE = Regex("\\s+")

/**
 * Turns whatever the user typed into a tag: no control characters, no leading "#",
 * single spaces, lowercase, trimmed and at most [MAX_TAG_LENGTH] characters.
 * Returns an empty string when nothing usable is left.
 */
internal fun normalizeTag(raw: String): String =
    raw.filterNot { it.isISOControl() }
        .trim()
        .removePrefix("#")
        .replace(TAG_WHITESPACE, " ")
        .lowercase()
        .take(MAX_TAG_LENGTH)
        .trim()
