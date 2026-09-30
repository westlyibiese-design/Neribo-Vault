package com.westly.neribovault.feature.notes

import com.westly.neribovault.data.local.entity.NoteEntity

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

/** This note as plain text for copying or sharing. */
internal fun NoteEntity.toPlainText(): String = composeNoteText(title, body)
