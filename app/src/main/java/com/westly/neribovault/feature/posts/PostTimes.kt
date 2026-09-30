package com.westly.neribovault.feature.posts

import com.westly.neribovault.core.util.formatTime
import com.westly.neribovault.data.local.entity.SocialPostEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

private val DAYS_SHORT = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val MONTHS_SHORT = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private const val DEFAULT_HOUR = 18
private const val DEFAULT_MINUTE = 30

private fun zone(): ZoneId = ZoneId.systemDefault()

private fun zoned(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone())

private fun yearSuffix(date: LocalDate): String =
    if (date.year == LocalDate.now(zone()).year) "" else " ${date.year}"

/** "Sat 3 Oct, 6:30 PM" (the year is added when it is not the current year). */
fun formatPostSchedule(epochMillis: Long): String {
    val date = zoned(epochMillis).toLocalDate()
    val day = DAYS_SHORT[date.dayOfWeek.value - 1]
    val month = MONTHS_SHORT[date.monthValue - 1]
    return "$day ${date.dayOfMonth} $month${yearSuffix(date)}, ${formatTime(epochMillis)}"
}

/** "Posted 29 Sep" (the year is added when it is not the current year). */
fun formatPostedOn(epochMillis: Long): String {
    val date = zoned(epochMillis).toLocalDate()
    return "Posted ${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}${yearSuffix(date)}"
}

/** 6:30 PM on the day after [nowMillis], in the device time zone. */
fun defaultScheduleMillis(nowMillis: Long = System.currentTimeMillis()): Long =
    zoned(nowMillis).toLocalDate()
        .plusDays(1)
        .atTime(DEFAULT_HOUR, DEFAULT_MINUTE)
        .atZone(zone())
        .toInstant()
        .toEpochMilli()

/** Hour of day (0 to 23) of that moment in the device time zone. */
fun hourOf(epochMillis: Long): Int = zoned(epochMillis).hour

/** Minute (0 to 59) of that moment in the device time zone. */
fun minuteOf(epochMillis: Long): Int = zoned(epochMillis).minute

/** The Material 3 date picker speaks in UTC midnight; this is the value for that local day. */
fun toPickerMillis(epochMillis: Long): Long =
    zoned(epochMillis).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Combines the day the date picker returned (UTC midnight) with a local time of day. */
fun fromPickerMillis(pickerMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(pickerMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atTime(hour, minute)
        .atZone(zone())
        .toInstant()
        .toEpochMilli()

/** The groups on the Upcoming tab, in the order they are shown. */
enum class UpcomingBucket(val header: String) {
    Overdue("OVERDUE"),
    Today("TODAY"),
    Tomorrow("TOMORROW"),
    ThisWeek("THIS WEEK"),
    Later("LATER"),
}

/** One header and its posts on the Upcoming tab. */
data class UpcomingGroup(val bucket: UpcomingBucket, val posts: List<SocialPostEntity>)

/**
 * Which group a scheduled time belongs to at [nowMillis]. A time that has already passed is
 * overdue, even earlier today. "This week" runs from the day after tomorrow to Sunday, because
 * the week starts on Monday.
 */
fun bucketFor(scheduledAt: Long, nowMillis: Long): UpcomingBucket {
    if (scheduledAt < nowMillis) return UpcomingBucket.Overdue
    val today = zoned(nowMillis).toLocalDate()
    val date = zoned(scheduledAt).toLocalDate()
    val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return when {
        date == today -> UpcomingBucket.Today
        date == today.plusDays(1) -> UpcomingBucket.Tomorrow
        !date.isAfter(endOfWeek) -> UpcomingBucket.ThisWeek
        else -> UpcomingBucket.Later
    }
}

/** Groups scheduled [posts] (already sorted by time) into the non-empty Upcoming groups. */
fun groupUpcoming(posts: List<SocialPostEntity>, nowMillis: Long): List<UpcomingGroup> {
    val byBucket = posts
        .mapNotNull { post -> post.scheduledAt?.let { at -> post to at } }
        .groupBy(
            keySelector = { pair -> bucketFor(pair.second, nowMillis) },
            valueTransform = { pair -> pair.first },
        )
    return UpcomingBucket.values().mapNotNull { bucket ->
        byBucket[bucket]?.let { list -> UpcomingGroup(bucket, list) }
    }
}
