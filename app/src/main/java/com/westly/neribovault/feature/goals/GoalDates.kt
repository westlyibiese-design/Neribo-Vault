package com.westly.neribovault.feature.goals

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Material 3 date picker speaks in UTC midnight. Goals store the start of the day in the
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
