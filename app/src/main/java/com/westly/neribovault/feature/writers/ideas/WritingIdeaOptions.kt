package com.westly.neribovault.feature.writers.ideas

import com.westly.neribovault.core.ui.components.BadgeTone

/** Genres, statuses and display labels for writing ideas. */
internal object IdeaOptions {
    /** Same values as a story's genre. */
    val genres: List<String> = listOf(
        "Drama",
        "Romance",
        "Thriller",
        "Historical",
        "Folklore",
        "Faith",
        "Comedy",
        "Fantasy",
        "Other",
    )

    val statuses: List<String> = listOf("spark", "developing", "used")

    const val DEFAULT_STATUS = "spark"

    fun statusLabel(status: String): String = when (status) {
        "spark" -> "Spark"
        "developing" -> "Developing"
        "used" -> "Used"
        else -> status.replaceFirstChar { it.uppercase() }
    }

    fun statusTone(status: String): BadgeTone = when (status) {
        "spark" -> BadgeTone.Accent
        "developing" -> BadgeTone.Warning
        else -> BadgeTone.Neutral
    }
}

/** The status chips above the ideas list. */
enum class IdeaFilter(val label: String, val status: String?) {
    All("All", null),
    Spark("Spark", "spark"),
    Developing("Developing", "developing"),
    Used("Used", "used"),
}

/** Everything the idea editor edits. */
internal data class IdeaDraft(
    val title: String = "",
    val body: String = "",
    val genre: String? = null,
    val status: String = IdeaOptions.DEFAULT_STATUS,
) {
    /** True when there is nothing worth keeping. Genre and status alone do not count. */
    val isBlank: Boolean get() = title.isBlank() && body.isBlank()
}

/** Title and body joined for copying. Skips whichever part is blank. */
internal fun composeIdeaText(title: String, body: String): String {
    val cleanTitle = title.trim()
    val cleanBody = body.trim()
    return when {
        cleanTitle.isEmpty() -> cleanBody
        cleanBody.isEmpty() -> cleanTitle
        else -> "$cleanTitle\n\n$cleanBody"
    }
}
