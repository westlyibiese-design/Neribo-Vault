package com.westly.neribovault.feature.lyrics.editor

import com.westly.neribovault.feature.lyrics.engine.SectionType

/**
 * One section in the editor. [id] is a random id that stays stable for the whole session.
 * [label] is only used when [type] is [SectionType.OTHER]; [text] never contains a blank line
 * (except for a moment while the user is typing, before the split rule runs).
 */
data class EditorSection(val id: String, val type: SectionType, val label: String, val text: String)

/** The whole section list plus the focused section, as stored in the undo history. */
data class EditorSnapshot(val sections: List<EditorSection>, val focusedId: String?)

/**
 * Asks the row of [sectionId] to take focus. [cursor] is an offset in the section's text.
 * [token] changes for every new request.
 */
data class FocusTarget(val sectionId: String, val cursor: Int, val token: Long)

/** Asks the row of [sectionId] to flash its focus bar once. */
data class FlashTarget(val sectionId: String, val token: Long)

/** What a change in a section's text field means. */
sealed interface FieldEdit {
    /** Ordinary typing, deleting or pasting. [text] has no sentinel. */
    data class Typed(val text: String) : FieldEdit

    /** Backspace was pressed with the cursor at the very start of the section. */
    object BackspaceAtStart : FieldEdit
}
