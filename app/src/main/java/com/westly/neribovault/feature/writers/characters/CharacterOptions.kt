package com.westly.neribovault.feature.writers.characters

import com.westly.neribovault.core.ui.components.BadgeTone

/** The roles a character can play, with their display labels and badge tones. */
internal object CharacterRoles {
    val all: List<String> = listOf("protagonist", "antagonist", "supporting", "minor")

    const val DEFAULT = "supporting"

    fun label(role: String): String = when (role) {
        "protagonist" -> "Protagonist"
        "antagonist" -> "Antagonist"
        "supporting" -> "Supporting"
        "minor" -> "Minor"
        else -> role.replaceFirstChar { it.uppercase() }
    }

    fun tone(role: String): BadgeTone = when (role) {
        "protagonist" -> BadgeTone.Accent
        "antagonist" -> BadgeTone.Warning
        else -> BadgeTone.Neutral
    }
}

/** The most traits one character can carry. */
internal const val MAX_TRAITS = 12

/** The longest a single trait can be. */
internal const val MAX_TRAIT_LENGTH = 30

/** Everything the character editor edits. */
internal data class CharacterDraft(
    val name: String = "",
    val role: String = CharacterRoles.DEFAULT,
    val description: String = "",
    val traits: List<String> = emptyList(),
    val backstory: String = "",
) {
    /** True when there is nothing worth keeping. The role alone does not count. */
    val isBlank: Boolean
        get() = name.isBlank() && description.isBlank() && traits.isEmpty() && backstory.isBlank()
}

/** The character as a plain-text sheet, skipping every part that is empty. */
internal fun buildCharacterSheet(draft: CharacterDraft): String {
    val sections = mutableListOf<String>()
    sections += draft.name.trim().ifBlank { "Unnamed character" }
    sections += "Role: ${CharacterRoles.label(draft.role)}"
    val description = draft.description.trim()
    if (description.isNotEmpty()) sections += "Description\n$description"
    if (draft.traits.isNotEmpty()) sections += "Traits\n${draft.traits.joinToString(", ")}"
    val backstory = draft.backstory.trim()
    if (backstory.isNotEmpty()) sections += "Backstory\n$backstory"
    return sections.joinToString(separator = "\n\n")
}
