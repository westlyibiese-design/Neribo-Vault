package com.westly.neribovault.feature.church

import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal const val TYPE_SERMON = "sermon"
internal const val TYPE_MESSAGE = "message"
internal const val TYPE_BIBLE_STUDY = "bible_study"
internal const val TYPE_TEACHING = "teaching"
internal const val TYPE_PRAYER = "prayer"

/** One kind of church record: the stored [value] and the [label] people read. */
internal data class ChurchType(val value: String, val label: String)

/** The five kinds of record, in the order the chips show them. */
internal val CHURCH_TYPES: List<ChurchType> = listOf(
    ChurchType(TYPE_SERMON, "Sermon"),
    ChurchType(TYPE_MESSAGE, "Message"),
    ChurchType(TYPE_BIBLE_STUDY, "Bible study"),
    ChurchType(TYPE_TEACHING, "Teaching"),
    ChurchType(TYPE_PRAYER, "Prayer"),
)

/** The readable name of a stored type. Unknown values are tidied up rather than hidden. */
internal fun churchTypeLabel(type: String): String {
    val known = CHURCH_TYPES.firstOrNull { it.value == type }
    if (known != null) return known.label
    return type.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Record" }
}

/** Most tags one record can carry. */
internal const val MAX_TAGS = 10

/** Longest a single tag can be, in characters. */
internal const val MAX_TAG_LENGTH = 24

private val TAG_WHITESPACE = Regex("\\s+")

/**
 * Turns whatever was typed into a tag: no control characters, no leading "#",
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

/**
 * The Material 3 date picker speaks in UTC midnight. Records store the start of the day in the
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

/**
 * A record laid out as plain text for copying or sharing: title, date, speaker, scripture,
 * summary and notes. Anything left blank is skipped.
 */
internal fun composeChurchText(
    title: String,
    type: String,
    recordDate: Long,
    speaker: String,
    church: String,
    scriptureRefs: String,
    summary: String,
    notes: String,
): String {
    val cleanSpeaker = speaker.trim()
    val cleanChurch = church.trim()
    val header = buildList<String> {
        add(title.trim().ifEmpty { "Untitled" })
        add("${formatDate(recordDate)} \u00B7 ${churchTypeLabel(type)}")
        when {
            cleanSpeaker.isNotEmpty() && cleanChurch.isNotEmpty() ->
                add("Speaker: $cleanSpeaker \u00B7 $cleanChurch")
            cleanSpeaker.isNotEmpty() -> add("Speaker: $cleanSpeaker")
            cleanChurch.isNotEmpty() -> add("Church: $cleanChurch")
        }
        if (scriptureRefs.isNotBlank()) add("Scripture: ${scriptureRefs.trim()}")
    }.joinToString("\n")
    val sections = buildList<String> {
        add(header)
        if (summary.isNotBlank()) add("Summary: ${summary.trim()}")
        if (notes.isNotBlank()) add(notes.trim())
    }
    return sections.joinToString("\n\n")
}

/** This record as plain text for copying or sharing. */
internal fun ChurchRecordEntity.toPlainText(): String = composeChurchText(
    title = title,
    type = type,
    recordDate = recordDate,
    speaker = speaker,
    church = church,
    scriptureRefs = scriptureRefs,
    summary = summary,
    notes = notes,
)
