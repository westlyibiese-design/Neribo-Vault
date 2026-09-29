package com.westly.neribovault.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

private val MONTHS_SHORT = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val MONTHS_LONG = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** Indexed by [java.time.DayOfWeek.getValue] minus one (Monday first). */
private val DAYS_LONG = arrayOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS

private fun zoneOf(): ZoneId = ZoneId.systemDefault()

private fun zoned(epochMillis: Long): ZonedDateTime =
    Instant.ofEpochMilli(epochMillis).atZone(zoneOf())

private fun dayMonth(date: LocalDate): String =
    "${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"

/** "29 Sep 2026" */
fun formatDate(epochMillis: Long): String {
    val date = zoned(epochMillis).toLocalDate()
    return "${dayMonth(date)} ${date.year}"
}

/** "Tuesday, 29 September 2026" */
fun formatDateLong(epochMillis: Long): String {
    val date = zoned(epochMillis).toLocalDate()
    val dayName = DAYS_LONG[date.dayOfWeek.value - 1]
    val monthName = MONTHS_LONG[date.monthValue - 1]
    return "$dayName, ${date.dayOfMonth} $monthName ${date.year}"
}

/** "5:08 PM" */
fun formatTime(epochMillis: Long): String {
    val time = zoned(epochMillis).toLocalTime()
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    val minutes = time.minute.toString().padStart(2, '0')
    val suffix = if (time.hour < 12) "AM" else "PM"
    return "$hour12:$minutes $suffix"
}

/** "29 Sep 2026, 5:08 PM" */
fun formatDateTime(epochMillis: Long): String =
    "${formatDate(epochMillis)}, ${formatTime(epochMillis)}"

/** "Just now", "12 min ago", "3 hr ago", "Yesterday", "3 Sep", "3 Sep 2025". */
fun formatRelative(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMillis
    if (diff in 0 until MINUTE_MS) return "Just now"
    val today = zoned(now).toLocalDate()
    val date = zoned(epochMillis).toLocalDate()
    if (diff >= 0 && date == today) {
        return if (diff < HOUR_MS) {
            "${diff / MINUTE_MS} min ago"
        } else {
            "${diff / HOUR_MS} hr ago"
        }
    }
    if (date == today.minusDays(1)) return "Yesterday"
    return if (date.year == today.year) dayMonth(date) else "${dayMonth(date)} ${date.year}"
}

/** Start of that day (00:00) in the device time zone, as epoch milliseconds. */
fun startOfDayMillis(epochMillis: Long): Long {
    val zone = zoneOf()
    return Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

/** Calendar days from today to that moment. Negative when it is in the past. */
fun daysUntil(epochMillis: Long): Int {
    val today = LocalDate.now(zoneOf())
    val target = zoned(epochMillis).toLocalDate()
    return ChronoUnit.DAYS.between(today, target).toInt()
}
