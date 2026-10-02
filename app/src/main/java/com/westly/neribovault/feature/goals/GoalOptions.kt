package com.westly.neribovault.feature.goals

/** Stored category values, in the order the chips are shown. */
internal val GOAL_CATEGORIES = listOf(
    "personal",
    "spiritual",
    "career",
    "health",
    "finance",
    "learning",
    "relationships",
)

/** Stored status values, in the order the status control shows them. */
internal val GOAL_STATUSES = listOf("active", "paused", "completed")

/** Display name for a stored category value. */
internal fun goalCategoryLabel(value: String): String = when (value) {
    "personal" -> "Personal"
    "spiritual" -> "Spiritual"
    "career" -> "Career"
    "health" -> "Health"
    "finance" -> "Finance"
    "learning" -> "Learning"
    "relationships" -> "Relationships"
    else -> value.replaceFirstChar { it.uppercase() }
}

/** Display name for a stored status value. */
internal fun goalStatusLabel(value: String): String = when (value) {
    "active" -> "Active"
    "paused" -> "Paused"
    "completed" -> "Completed"
    else -> value.replaceFirstChar { it.uppercase() }
}

/** A short instruction for the title field. No sample goals: a hint must never look like real content. */
@Suppress("UNUSED_PARAMETER")
internal fun goalTitleHint(category: String): String = "Name your goal"
