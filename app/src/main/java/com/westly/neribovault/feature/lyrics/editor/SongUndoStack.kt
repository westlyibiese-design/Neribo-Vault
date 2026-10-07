package com.westly.neribovault.feature.lyrics.editor

/**
 * Undo and redo history of whole-song snapshots, limited to [limit] steps.
 * Pure Kotlin so it is easy to reason about.
 */
class SongUndoStack(private val limit: Int = 100) {
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [snapshot] as a state to return to. Any new edit clears the redo history. */
    fun push(snapshot: EditorSnapshot) {
        redoStack.clear()
        if (undoStack.lastOrNull()?.sections == snapshot.sections) return
        undoStack.addLast(snapshot)
        while (undoStack.size > limit) undoStack.removeFirst()
    }

    /** Returns the previous state and keeps [current] for redo, or null when there is nothing to undo. */
    fun undo(current: EditorSnapshot): EditorSnapshot? {
        if (undoStack.isEmpty()) return null
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        while (redoStack.size > limit) redoStack.removeFirst()
        return previous
    }

    /** Returns the state that was undone and keeps [current] for undo, or null when there is nothing to redo. */
    fun redo(current: EditorSnapshot): EditorSnapshot? {
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
