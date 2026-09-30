package com.westly.neribovault.feature.diary

import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Most tags one diary entry can carry. */
internal const val MAX_TAGS = 10

/** Longest a single tag can be, in characters. */
internal const val MAX_TAG_LENGTH = 24

private val TAG_WHITESPACE = Regex("\\s+")

/**
 * Turns whatever the user typed into a tag: no control characters, no leading "#",
 * single spaces, lowercase, trimmed and at most [MAX_TAG_LENGTH] characters.
 */
internal fun normalizeTag(raw: String): String =
    raw.filterNot { it.isISOControl() }
        .trim()
        .removePrefix("#")
        .replace(TAG_WHITESPACE, " ")
        .lowercase()
        .take(MAX_TAG_LENGTH)
        .trim()

/** The five moods an entry can carry, stored as [key] and shown as [label]. */
enum class DiaryMood(val key: String, val label: String) {
    Joyful("joyful", "Joyful"),
    Calm("calm", "Calm"),
    Okay("okay", "Okay"),
    Tired("tired", "Tired"),
    Low("low", "Low"),
    ;

    companion object {
        /** The display label for a stored mood key, or null for no mood or an unknown key. */
        fun labelFor(key: String?): String? = values().firstOrNull { it.key == key }?.label
    }
}

internal fun zone(): ZoneId = ZoneId.systemDefault()

/** The calendar day an entry-date (start-of-day millis) falls on in the device time zone. */
internal fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone()).toLocalDate()

/** Start-of-day millis of this day in the device time zone. */
internal fun LocalDate.toStartOfDayMillis(): Long =
    atStartOfDay(zone()).toInstant().toEpochMilli()

/** "September 2026" */
internal fun YearMonth.title(): String =
    "${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $year"

/**
 * Length of the writing streak: consecutive days with an entry, ending today or yesterday.
 * Zero when there is no entry today or yesterday.
 */
internal fun computeStreak(entryDays: Set<LocalDate>, today: LocalDate): Int {
    var day = when {
        today in entryDays -> today
        today.minusDays(1) in entryDays -> today.minusDays(1)
        else -> return 0
    }
    var count = 0
    while (day in entryDays) {
        count++
        day = day.minusDays(1)
    }
    return count
}

/** The text of an entry for copying and sharing: the long date, the title if any, then the body. */
internal fun composeEntryText(entryDate: Long, title: String, body: String): String =
    listOf(formatDateLong(entryDate), title.trim(), body.trim())
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

internal fun DiaryEntryEntity.toPlainText(): String = composeEntryText(entryDate, title, body)

/** The heading of an entry card: the title, or the first line of the body when there is none. */
internal fun DiaryEntryEntity.headline(): String {
    val trimmedTitle = title.trim()
    if (trimmedTitle.isNotEmpty()) return trimmedTitle
    return body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Untitled entry"
}

/** The text under the heading: the body, minus its first line when that line was used as the heading. */
internal fun DiaryEntryEntity.previewSource(): String {
    if (title.isNotBlank()) return body
    val lines = body.lines()
    val firstIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstIndex < 0) return ""
    return lines.drop(firstIndex + 1).joinToString("\n")
}
