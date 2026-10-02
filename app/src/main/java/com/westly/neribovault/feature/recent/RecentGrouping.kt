package com.westly.neribovault.feature.recent

import com.westly.neribovault.core.search.IndexedItem
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How many items the Recent screen shows at most. */
const val RECENT_LIMIT = 50

/** The day groups of the Recent screen, newest first. */
enum class RecentBucket(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    EarlierThisWeek("Earlier this week"),
    Earlier("Earlier"),
}

/** One group of the Recent screen. */
class RecentSection(val bucket: RecentBucket, val items: List<IndexedItem>)

/**
 * The group a change made at [updatedAt] belongs to, judged by calendar days in [zone] as seen
 * from [now]. A time in the future counts as today.
 */
fun bucketFor(updatedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): RecentBucket {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(day, today)
    return when {
        daysAgo <= 0L -> RecentBucket.Today
        daysAgo == 1L -> RecentBucket.Yesterday
        daysAgo <= 6L -> RecentBucket.EarlierThisWeek
        else -> RecentBucket.Earlier
    }
}

/** The [limit] most recently changed items, newest first. */
fun latestItems(items: List<IndexedItem>, limit: Int = RECENT_LIMIT): List<IndexedItem> =
    items.sortedByDescending { it.updatedAt }.take(limit)

/** Splits [items] into day groups, newest first. Groups with nothing in them are left out. */
fun groupRecent(
    items: List<IndexedItem>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecentSection> {
    val byBucket = HashMap<RecentBucket, MutableList<IndexedItem>>()
    for (item in items) {
        byBucket.getOrPut(bucketFor(item.updatedAt, now, zone)) { ArrayList() }.add(item)
    }
    val sections = ArrayList<RecentSection>()
    for (bucket in RecentBucket.values()) {
        val list = byBucket[bucket]
        if (list != null && list.isNotEmpty()) sections.add(RecentSection(bucket, list))
    }
    return sections
}
