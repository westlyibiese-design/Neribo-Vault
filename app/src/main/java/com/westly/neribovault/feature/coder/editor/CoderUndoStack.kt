package com.westly.neribovault.feature.coder.editor

import androidx.compose.ui.text.input.TextFieldValue

/**
 * Undo and redo history of editor snapshots (text and selection), limited to [limit] steps.
 * Pure Kotlin. Deciding when a change is worth a new step is the caller's job.
 */
class CoderUndoStack(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [snapshot] as a state to return to. Any new edit clears the redo history. */
    fun push(snapshot: TextFieldValue) {
        redoStack.clear()
        if (undoStack.lastOrNull()?.text == snapshot.text) return
        undoStack.addLast(snapshot)
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    /** Returns the previous state and keeps [current] for redo, or null when there is nothing to undo. */
    fun undo(current: TextFieldValue): TextFieldValue? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        while (redoStack.size > limit) redoStack.removeFirst()
        return previous
    }

    /** Returns the state that was undone and keeps [current] for undo, or null when there is nothing to redo. */
    fun redo(current: TextFieldValue): TextFieldValue? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
