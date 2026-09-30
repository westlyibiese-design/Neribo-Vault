package com.westly.neribovault.feature.writers

import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import java.text.NumberFormat
import java.util.Locale

/** Genres and statuses a story can have, with their display labels. */
object StoryOptions {
    val genres: List<String> = listOf(
        "Drama",
        "Romance",
        "Thriller",
        "Historical",
        "Folklore",
        "Faith",
        "Comedy",
        "Fantasy",
        "Other",
    )

    val statuses: List<String> = listOf("idea", "drafting", "revising", "complete")

    const val DEFAULT_GENRE = "Drama"
    const val DEFAULT_STATUS = "idea"

    fun statusLabel(status: String): String = when (status) {
        "idea" -> "Idea"
        "drafting" -> "Drafting"
        "revising" -> "Revising"
        "complete" -> "Complete"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    fun statusTone(status: String): BadgeTone = when (status) {
        "drafting" -> BadgeTone.Accent
        "revising" -> BadgeTone.Warning
        else -> BadgeTone.Neutral
    }
}

/** A whole number with thousands separators, for example 34,500. */
internal fun formatStoryCount(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

/** "12 chapters · 34,500 words", or "No chapters yet". */
internal fun storyStatsLine(chapterCount: Int, wordCount: Int): String {
    if (chapterCount == 0) return "No chapters yet"
    val chapters = if (chapterCount == 1) "1 chapter" else "${formatStoryCount(chapterCount)} chapters"
    return "$chapters \u00B7 ${wordCountLabel(wordCount)}"
}

/** "1 word" or "1,240 words". */
internal fun wordCountLabel(wordCount: Int): String =
    if (wordCount == 1) "1 word" else "${formatStoryCount(wordCount)} words"

/** Chapter title and body joined for copying. Skips whichever part is blank. */
internal fun composeChapterText(title: String, body: String): String {
    val cleanTitle = title.trim()
    val cleanBody = body.trim()
    return when {
        cleanTitle.isEmpty() -> cleanBody
        cleanBody.isEmpty() -> cleanTitle
        else -> "$cleanTitle\n\n$cleanBody"
    }
}

/**
 * The whole story as one plain-text document: the story title, then every chapter's title and
 * body in order, separated by blank lines. A chapter without a title is headed "Chapter N".
 */
internal fun buildStoryExport(storyTitle: String, chapters: List<StoryChapterEntity>): String {
    val sections = mutableListOf(storyTitle.trim().ifBlank { "Untitled story" })
    chapters.forEachIndexed { index, chapter ->
        val heading = chapter.title.trim().ifBlank { "Chapter ${index + 1}" }
        val body = chapter.body.trim()
        sections += if (body.isEmpty()) heading else "$heading\n\n$body"
    }
    return sections.joinToString(separator = "\n\n\n")
}
