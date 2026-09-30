package com.westly.neribovault.feature.posts

import java.util.Locale

private val HASHTAG_SEPARATORS = Regex("[\\s,#]+")
private const val MAX_HASHTAG_LENGTH = 100

/** Lower-cases a hashtag and keeps only letters, digits and underscores (so no `#`). */
fun normalizeHashtag(raw: String): String =
    raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(MAX_HASHTAG_LENGTH)

/** Splits typed or pasted text such as "#lagos #harmattan" into normalised hashtags. */
fun splitHashtagInput(raw: String): List<String> =
    raw.split(HASHTAG_SEPARATORS).map { normalizeHashtag(it) }.filter { it.isNotEmpty() }

/** "#lagos #harmattan" */
fun hashtagsLine(hashtags: List<String>): String =
    hashtags.joinToString(separator = " ") { "#$it" }

/** The caption followed by a blank line and the hashtags, ready to paste into an app. */
fun composePostText(caption: String, hashtags: List<String>): String {
    val text = caption.trimEnd()
    if (hashtags.isEmpty()) return text
    if (text.isEmpty()) return hashtagsLine(hashtags)
    return text + "\n\n" + hashtagsLine(hashtags)
}

/** Number of characters as a person counts them: an emoji counts once. */
fun captionLength(caption: String): Int = caption.codePointCount(0, caption.length)

/** "1,240" */
fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)
