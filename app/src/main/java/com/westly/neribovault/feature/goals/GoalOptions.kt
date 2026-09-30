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

/** A grounded example for the title field, matched to the chosen category. */
internal fun goalTitleHint(category: String): String = when (category) {
    "finance" -> "Save \u20A6500,000 for a laptop"
    "spiritual" -> "Read the Bible through by December"
    "career" -> "Ship the first version of my app"
    "health" -> "Walk for 30 minutes every morning"
    "learning" -> "Finish a course in UI design"
    "relationships" -> "Call my mother every Sunday"
    else -> "Learn to cook a proper pot of egusi"
}
