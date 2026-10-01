package com.westly.neribovault.feature.memories

import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.data.local.entity.MemoryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Most photos one memory can hold. */
internal const val MAX_PHOTOS = 12

/** Most people one memory can name. */
internal const val MAX_PEOPLE = 20

/** Longest a person's name can be, in characters. */
internal const val MAX_PERSON_LENGTH = 40

/** Most tags one memory can carry. */
internal const val MAX_TAGS = 10

/** Longest a single tag can be, in characters. */
internal const val MAX_TAG_LENGTH = 24

private val WHITESPACE = Regex("\\s+")

/**
 * Turns whatever was typed into a tag: no control characters, no leading "#",
 * single spaces, lowercase, trimmed and at most [MAX_TAG_LENGTH] characters.
 * Returns an empty string when nothing usable is left.
 */
internal fun normalizeTag(raw: String): String =
    raw.filterNot { it.isISOControl() }
        .trim()
        .removePrefix("#")
        .replace(WHITESPACE, " ")
        .lowercase()
        .take(MAX_TAG_LENGTH)
        .trim()

/**
 * Turns whatever was typed into a person's name: no control characters, single spaces,
 * trimmed and at most [MAX_PERSON_LENGTH] characters. Capitalisation is kept as typed.
 */
internal fun normalizePerson(raw: String): String =
    raw.filterNot { it.isISOControl() }
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_PERSON_LENGTH)
        .trim()

/**
 * The Material 3 date picker speaks in UTC midnight. Memories store the start of the day in the
 * device time zone. These two functions convert between them so the picked day never shifts.
 */
internal fun pickerMillisToLocalStart(pickerMillis: Long): Long {
    val date = Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** The inverse of [pickerMillisToLocalStart], for pre-selecting a stored date. */
internal fun localStartToPickerMillis(localMillis: Long): Long {
    val date = Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private val MONTH_NAMES = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** A sortable year-and-month number such as 202609, in the device time zone. */
internal fun monthKeyOf(epochMillis: Long): Int {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.year * 100 + date.monthValue
}

/** "September 2026". The list header shows it in capitals. */
internal fun monthTitleOf(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${MONTH_NAMES[date.monthValue - 1]} ${date.year}"
}

/** The quiet line under a memory's title: "12 Sep 2026 · Benin City". */
internal fun memoryMetaLine(memoryDate: Long, location: String): String {
    val place = location.trim()
    val date = formatDate(memoryDate)
    return if (place.isEmpty()) date else "$date \u00B7 $place"
}

/** "1 photo" or "3 photos". */
internal fun photoCountLabel(count: Int): String = if (count == 1) "1 photo" else "$count photos"

/**
 * A memory laid out as plain text for copying or sharing: title, date and place, people, tags
 * and the story. Anything left blank is skipped. Photos are never part of it.
 */
internal fun composeMemoryText(
    title: String,
    description: String,
    memoryDate: Long,
    location: String,
    people: List<String>,
    tags: List<String>,
): String {
    val place = location.trim()
    val header = buildList<String> {
        add(title.trim().ifEmpty { "Untitled memory" })
        add(if (place.isEmpty()) formatDateLong(memoryDate) else "${formatDateLong(memoryDate)} \u00B7 $place")
        if (people.isNotEmpty()) add("With: ${people.joinToString(", ")}")
        if (tags.isNotEmpty()) add("Tags: ${tags.joinToString(", ")}")
    }.joinToString("\n")
    val story = description.trim()
    return if (story.isEmpty()) header else "$header\n\n$story"
}

/** This memory as plain text for copying or sharing. */
internal fun MemoryEntity.toPlainText(): String = composeMemoryText(
    title = title,
    description = description,
    memoryDate = memoryDate,
    location = location,
    people = people,
    tags = tags,
)
