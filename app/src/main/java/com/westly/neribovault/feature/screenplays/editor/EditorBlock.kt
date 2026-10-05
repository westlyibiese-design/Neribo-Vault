package com.westly.neribovault.feature.screenplays.editor

import com.westly.neribovault.feature.screenplays.engine.BlockType

/** One paragraph in the editor. [id] is stable for the whole session; [text] follows the rules of [BlockType]. */
data class EditorBlock(val id: String, val type: BlockType, val text: String)

/** A block is blank when it has no text and is not a page break. Blank blocks are never saved. */
val EditorBlock.isBlank: Boolean
    get() = type != BlockType.PAGE_BREAK && text.isBlank()

/** The whole block list plus the focused block, as stored in the undo history. */
data class EditorSnapshot(val blocks: List<EditorBlock>, val focusedId: String?)

/**
 * Asks the row of [blockId] to take focus. [cursor] is an offset in the block's text.
 * [token] changes for every new request. [textFocus] is false for page breaks, which have no text field.
 */
data class FocusTarget(
    val blockId: String,
    val cursor: Int,
    val token: Long,
    val textFocus: Boolean = true,
)

/** Asks the row of [blockId] to flash its focus bar once. */
data class FlashTarget(val blockId: String, val token: Long)
