package com.westly.neribovault.feature.ideas

import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.data.local.entity.IdeaEntity

/** Stored category values, in the order the chips are shown. */
internal val IDEA_CATEGORIES = listOf("general", "business", "app", "content", "creative", "personal")

/** Stored status values, in the order the chips are shown. */
internal val IDEA_STATUSES = listOf("new", "exploring", "in_progress", "done", "dropped")

/** Display name for a stored category value. */
internal fun ideaCategoryLabel(value: String): String = when (value) {
    "general" -> "General"
    "business" -> "Business"
    "app" -> "App"
    "content" -> "Content"
    "creative" -> "Creative"
    "personal" -> "Personal"
    else -> value.replaceFirstChar { it.uppercase() }
}

/** Display name for a stored status value. */
internal fun ideaStatusLabel(value: String): String = when (value) {
    "new" -> "New"
    "exploring" -> "Exploring"
    "in_progress" -> "In progress"
    "done" -> "Done"
    "dropped" -> "Dropped"
    else -> value.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Badge color for a status: accent while in progress, warning when dropped. */
internal fun ideaStatusTone(value: String): BadgeTone = when (value) {
    "in_progress" -> BadgeTone.Accent
    "dropped" -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}

/** Title and description joined for copying or sharing. Skips whichever part is blank. */
internal fun composeIdeaText(title: String, description: String): String {
    val cleanTitle = title.trim()
    val cleanDescription = description.trim()
    return when {
        cleanTitle.isEmpty() -> cleanDescription
        cleanDescription.isEmpty() -> cleanTitle
        else -> "$cleanTitle\n\n$cleanDescription"
    }
}

/** This idea as plain text for copying or sharing. */
internal fun IdeaEntity.toPlainText(): String = composeIdeaText(title, description)
