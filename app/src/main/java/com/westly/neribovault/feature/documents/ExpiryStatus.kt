package com.westly.neribovault.feature.documents

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where a document stands against its expiry date. */
enum class ExpiryState { NoExpiry, Valid, ExpiringSoon, Expired }

/**
 * Calendar days from the day of [now] to the day of [expiryDate] in the device time zone.
 * Negative when the expiry day is already behind us. Kept here (rather than using the shared
 * `daysUntil`) so [now] can be passed in and the rules stay unit-testable.
 */
internal fun daysUntilExpiry(expiryDate: Long, now: Long = System.currentTimeMillis()): Int {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val target = Instant.ofEpochMilli(expiryDate).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(today, target).toInt()
}

/**
 * The single source of truth for expiry rules, used by the list, detail, sorting and the reminder
 * scheduler. [ExpiryState.NoExpiry] when there is no date, [ExpiryState.Expired] when the expiry
 * day is before today, [ExpiryState.ExpiringSoon] when 0 to [remindDaysBefore] days remain
 * (today counts), otherwise [ExpiryState.Valid].
 */
fun expiryState(
    expiryDate: Long?,
    remindDaysBefore: Int,
    now: Long = System.currentTimeMillis(),
): ExpiryState {
    if (expiryDate == null) return ExpiryState.NoExpiry
    val days = daysUntilExpiry(expiryDate, now)
    return when {
        days < 0 -> ExpiryState.Expired
        days <= remindDaysBefore -> ExpiryState.ExpiringSoon
        else -> ExpiryState.Valid
    }
}

private fun dayCount(days: Int): String = if (days == 1) "1 day" else "$days days"

/** "Expires in 45 days", "Expires today", "Expired 3 days ago" or "No expiry". */
fun expiryLabel(expiryDate: Long?, now: Long = System.currentTimeMillis()): String {
    if (expiryDate == null) return "No expiry"
    val days = daysUntilExpiry(expiryDate, now)
    return when {
        days < 0 -> "Expired ${dayCount(-days)} ago"
        days == 0 -> "Expires today"
        else -> "Expires in ${dayCount(days)}"
    }
}
