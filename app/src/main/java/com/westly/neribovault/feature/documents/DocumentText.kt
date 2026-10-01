package com.westly.neribovault.feature.documents

import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Suggested categories, used as chips in the editor and as list filters when in use. */
internal val DOCUMENT_CATEGORIES: List<String> = listOf(
    "National ID",
    "Driver's licence",
    "International passport",
    "Voter's card",
    "Birth certificate",
    "Academic certificate",
    "Insurance",
    "Tenancy agreement",
    "Business registration",
    "Tax (TIN)",
    "Medical",
    "Other",
)

/** The "Remind me" choices, in days before the expiry date. */
internal val REMINDER_CHOICES: List<Int> = listOf(7, 14, 30, 60, 90)

/** The reminder lead time a new document starts with. */
internal const val DEFAULT_REMIND_DAYS = 30

/** Longest title, category and issuer the editor accepts. */
internal const val MAX_TEXT_LENGTH = 120

/** True when the attachment at [path] is a PDF (the type is read from the extension). */
internal fun isPdfPath(path: String): Boolean = path.endsWith(".pdf", ignoreCase = true)

/** "30 days before". */
internal fun reminderLabel(days: Int): String = if (days == 1) "1 day before" else "$days days before"

/** "Identity · Federal Road Safety Corps", skipping whichever half is blank. */
internal fun documentMetaLine(category: String, issuer: String): String =
    listOf(category.trim(), issuer.trim()).filter { it.isNotEmpty() }.joinToString(" \u00B7 ")

/**
 * The Material 3 date picker speaks in UTC midnight. Documents store the start of the day in the
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

/**
 * A document's details as plain text for copying. The attachment itself (and its file path) is
 * never part of it.
 */
internal fun PersonalDocumentEntity.toPlainText(): String {
    val lines = mutableListOf<String>()
    lines.add(title.trim().ifEmpty { "Untitled document" })
    if (category.isNotBlank()) lines.add("Category: ${category.trim()}")
    if (issuer.isNotBlank()) lines.add("Issuer: ${issuer.trim()}")
    issueDate?.let { lines.add("Issued: ${formatDate(it)}") }
    expiryDate?.let {
        lines.add("Expires: ${formatDate(it)} (${expiryLabel(it)})")
        lines.add("Reminder: ${reminderLabel(remindDaysBefore)}")
    }
    val header = lines.joinToString("\n")
    val extra = notes.trim()
    return if (extra.isEmpty()) header else "$header\n\n$extra"
}
