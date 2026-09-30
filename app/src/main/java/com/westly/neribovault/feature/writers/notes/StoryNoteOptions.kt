package com.westly.neribovault.feature.writers.notes

/** The categories a story note can have, with their display labels. */
internal object NoteCategories {
    val all: List<String> = listOf("plot", "setting", "research", "theme", "other")

    const val DEFAULT = "plot"

    fun label(category: String): String = when (category) {
        "plot" -> "Plot"
        "setting" -> "Setting"
        "research" -> "Research"
        "theme" -> "Theme"
        "other" -> "Other"
        else -> category.replaceFirstChar { it.uppercase() }
    }
}

/** The category chips above the notes list. */
enum class NoteFilter(val label: String, val category: String?) {
    All("All", null),
    Plot("Plot", "plot"),
    Setting("Setting", "setting"),
    Research("Research", "research"),
    Theme("Theme", "theme"),
    Other("Other", "other"),
}

/** Everything the note editor edits. */
internal data class NoteDraft(
    val title: String = "",
    val body: String = "",
    val category: String = NoteCategories.DEFAULT,
) {
    /** True when there is nothing worth keeping. The category alone does not count. */
    val isBlank: Boolean get() = title.isBlank() && body.isBlank()
}

/** Title and body joined for copying or sharing. Skips whichever part is blank. */
internal fun composeNoteText(title: String, body: String): String {
    val cleanTitle = title.trim()
    val cleanBody = body.trim()
    return when {
        cleanTitle.isEmpty() -> cleanBody
        cleanBody.isEmpty() -> cleanTitle
        else -> "$cleanTitle\n\n$cleanBody"
    }
}
