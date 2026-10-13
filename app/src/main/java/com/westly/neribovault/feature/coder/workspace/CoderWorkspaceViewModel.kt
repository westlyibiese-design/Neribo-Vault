package com.westly.neribovault.feature.coder.workspace

import android.os.SystemClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.CoderUndoStack
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the text in the editor has reached the disk. */
enum class CoderSaveStatus { Saved, Saving, Failed }

/** Everything the workspace screen shows. [openPath] is null in Files mode and set in Editor mode. */
data class CoderWorkspaceUiState(
    val project: CoderProjectEntity? = null,
    val entries: List<CoderEntry> = emptyList(),
    val openPath: String? = null,
    val saveStatus: CoderSaveStatus = CoderSaveStatus.Saved,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isLoading: Boolean = true,
    val message: String? = null,
    /** The file the preview is showing, or null when the preview is closed. */
    val previewEntry: String? = null,
)

private enum class ChangeKind { None, Typing, Deleting }

/** Where two texts differ: [start] is the first changed index, followed by how much was removed and added. */
private class TextChange(val start: Int, val oldLength: Int, val newLength: Int, val isSingleNewline: Boolean)

private const val AUTOSAVE_DELAY_MS = 800L
private const val UNDO_PAUSE_MS = 800L
private const val BIG_CHANGE_CHARS = 20
private const val INDENT_UNIT = "  "

private const val MSG_TOO_BIG_TO_OPEN = "This file is bigger than 300 KB, so it can't be edited here."
private const val MSG_NOT_TEXT = "This file isn't plain text, so it can't be edited here."
private const val MSG_CANNOT_OPEN = "Couldn't open this file."
private const val MSG_TOO_BIG_TO_SAVE = "This file is over 300 KB and can't be saved. Remove some text."
private const val MSG_SAVE_FAILED = "Couldn't save. Your text is still here. Try again."
private const val MSG_BAD_NAME = "That name can't be used. Avoid slashes and special characters."
private const val MSG_EXISTS = "A file or folder with that name already exists."
private const val MSG_LIMIT = "This project has reached its size or file limit."
private const val MSG_FAILED = "Couldn't complete that. Please try again."
private const val MSG_PREVIEW_NOT_STARTED = "Couldn't save, so the preview was not started."
private const val MSG_BAD_PROJECT_NAME = "Project names can't be empty or longer than 60 characters."

/**
 * State and actions of one project's workspace: the file list, the open file's text, undo and redo,
 * autosave and the file operations. The editor text is only ever changed here, and everything that
 * touches the disk runs on `Dispatchers.IO`.
 */
class CoderWorkspaceViewModel(
    private val projectId: String,
    private val projects: CoderProjectsRepository,
    private val files: CoderFileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CoderWorkspaceUiState())
    val state: StateFlow<CoderWorkspaceUiState> = _state.asStateFlow()

    private val _editorValue = MutableStateFlow(TextFieldValue(""))

    /** The open file's text and selection. */
    val editorValue: StateFlow<TextFieldValue> = _editorValue.asStateFlow()

    private val undoStack = CoderUndoStack()
    private val saveMutex = Mutex()
    private var lastSavedText = ""
    private var usesCrlf = false
    private var lastPushAt = 0L
    private var lastKind = ChangeKind.None
    private var autosaveJob: Job? = null
    private var refreshJob: Job? = null
    private var entriesLoaded = false
    private var projectLoaded = false

    init {
        viewModelScope.launch {
            projects.observeById(projectId).collect { project ->
                projectLoaded = true
                _state.update { it.copy(project = project, isLoading = !entriesLoaded) }
            }
        }
        refreshEntries()
    }

    // ---- Files list ------------------------------------------------------------------------

    /** Reads the project's folder again. Called on start and after every file operation. */
    fun refreshEntries() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { files.listAll(projectId) }
            entriesLoaded = true
            _state.update { it.copy(entries = list, isLoading = !projectLoaded) }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---- Preview ---------------------------------------------------------------------------

    /** Saves the open file, then shows [entryPath] in the preview. When saving fails nothing starts, so the preview never shows old text. */
    fun startPreview(entryPath: String) {
        viewModelScope.launch {
            if (_state.value.previewEntry != null) return@launch
            if (!saveNow()) {
                postMessage(MSG_PREVIEW_NOT_STARTED)
                return@launch
            }
            _state.update { it.copy(previewEntry = entryPath) }
        }
    }

    /** Closes the preview and returns to where the person was. */
    fun stopPreview() {
        _state.update { it.copy(previewEntry = null) }
    }

    // ---- Open and close --------------------------------------------------------------------

    /** Opens [path] in the editor, or explains why it can't be opened. */
    fun openFile(path: String) {
        viewModelScope.launch {
            val current = _state.value.openPath
            if (current != null && current != path && !saveNow()) return@launch
            when (val result = withContext(Dispatchers.IO) { files.read(projectId, path) }) {
                is CoderReadResult.Text -> showFile(path, result)
                CoderReadResult.TooLarge -> postMessage(MSG_TOO_BIG_TO_OPEN)
                CoderReadResult.Binary -> postMessage(MSG_NOT_TEXT)
                CoderReadResult.Missing, CoderReadResult.Failed -> postMessage(MSG_CANNOT_OPEN)
            }
        }
    }

    /** Saves the open file and returns to the file list. The editor stays open when saving fails, so no text is lost. */
    fun closeFile() {
        viewModelScope.launch {
            if (_state.value.openPath == null) return@launch
            if (saveNow()) resetEditor()
        }
    }

    private fun showFile(path: String, result: CoderReadResult.Text) {
        autosaveJob?.cancel()
        lastSavedText = result.text
        usesCrlf = result.usesCrlf
        undoStack.clear()
        lastPushAt = 0L
        lastKind = ChangeKind.None
        _editorValue.value = TextFieldValue(result.text, TextRange(0))
        _state.update {
            it.copy(openPath = path, saveStatus = CoderSaveStatus.Saved, canUndo = false, canRedo = false)
        }
    }

    private fun resetEditor() {
        autosaveJob?.cancel()
        undoStack.clear()
        lastSavedText = ""
        usesCrlf = false
        lastPushAt = 0L
        lastKind = ChangeKind.None
        _editorValue.value = TextFieldValue("")
        _state.update {
            it.copy(openPath = null, saveStatus = CoderSaveStatus.Saved, canUndo = false, canRedo = false)
        }
    }

    // ---- Typing ----------------------------------------------------------------------------

    /** Takes what the keyboard produced, adds auto-indent after Enter and decides whether this starts a new undo step. */
    fun onEditorChange(value: TextFieldValue) {
        val path = _state.value.openPath ?: return
        val old = _editorValue.value
        if (value.text == old.text) {
            _editorValue.value = value
            return
        }
        val change = describeChange(old.text, value.text)
        val newlineAt = newlineInsertionIndex(old.text, value) ?: if (change.isSingleNewline) change.start else -1
        val applied = if (newlineAt >= 0) {
            withAutoIndent(old.text, value, newlineAt, CoderLanguage.fromPath(path))
        } else {
            value
        }
        val kind = if (value.text.length < old.text.length) ChangeKind.Deleting else ChangeKind.Typing
        val now = SystemClock.elapsedRealtime()
        val large = change.oldLength >= BIG_CHANGE_CHARS || change.newLength >= BIG_CHANGE_CHARS
        val startsStep = lastPushAt == 0L ||
            now - lastPushAt > UNDO_PAUSE_MS ||
            kind != lastKind ||
            large ||
            newlineAt >= 0
        if (startsStep) {
            undoStack.push(snapshot(old))
            lastPushAt = now
        }
        lastKind = kind
        _editorValue.value = applied
        afterEdit()
    }

    /** Inserts [text] at the cursor (after the selection, if any) as exactly one undo step. */
    fun insertAtCursor(text: String) {
        val old = openEditorValue() ?: return
        if (text.isEmpty()) return
        val at = old.selection.max.coerceIn(0, old.text.length)
        applyStep(old.text.substring(0, at) + text + old.text.substring(at), TextRange(at + text.length))
    }

    /** Replaces the selected text (or inserts at the cursor) as exactly one undo step. */
    fun replaceSelection(text: String) {
        val old = openEditorValue() ?: return
        val start = old.selection.min.coerceIn(0, old.text.length)
        val end = old.selection.max.coerceIn(start, old.text.length)
        applyStep(old.text.substring(0, start) + text + old.text.substring(end), TextRange(start + text.length))
    }

    /** Replaces the whole file as exactly one undo step; the cursor goes to the start. */
    fun replaceAll(text: String) {
        if (openEditorValue() == null) return
        applyStep(text, TextRange(0))
    }

    fun undo() {
        val previous = undoStack.undo(snapshot(_editorValue.value)) ?: return
        applyHistory(previous)
    }

    fun redo() {
        val next = undoStack.redo(snapshot(_editorValue.value)) ?: return
        applyHistory(next)
    }

    private fun openEditorValue(): TextFieldValue? =
        if (_state.value.openPath == null) null else _editorValue.value

    private fun applyStep(newText: String, selection: TextRange) {
        val old = _editorValue.value
        if (newText == old.text) {
            _editorValue.value = TextFieldValue(newText, selection)
            return
        }
        undoStack.push(snapshot(old))
        lastPushAt = SystemClock.elapsedRealtime()
        lastKind = ChangeKind.None
        _editorValue.value = TextFieldValue(newText, selection)
        afterEdit()
    }

    private fun applyHistory(value: TextFieldValue) {
        lastPushAt = 0L
        lastKind = ChangeKind.None
        _editorValue.value = value
        afterEdit()
    }

    private fun snapshot(value: TextFieldValue): TextFieldValue = TextFieldValue(value.text, value.selection)

    private fun afterEdit() {
        _state.update {
            it.copy(
                canUndo = undoStack.canUndo,
                canRedo = undoStack.canRedo,
                saveStatus = if (it.saveStatus == CoderSaveStatus.Saved) CoderSaveStatus.Saving else it.saveStatus,
            )
        }
        scheduleAutosave()
    }

    // ---- Saving ----------------------------------------------------------------------------

    /** Writes the open file now. True when nothing needed saving or the write succeeded. */
    suspend fun saveNow(): Boolean {
        autosaveJob?.cancel()
        return performSave(loud = true)
    }

    /** Saves in the background without blocking the caller; used when the screen stops or closes. */
    fun flush() {
        autosaveJob?.cancel()
        viewModelScope.launch { performSave(loud = false) }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            // The write runs in its own job so that the next keystroke cannot cancel it half way.
            viewModelScope.launch { performSave(loud = false) }
        }
    }

    private suspend fun performSave(loud: Boolean): Boolean {
        return saveMutex.withLock {
            val path = _state.value.openPath ?: return@withLock true
            val text = _editorValue.value.text
            if (text == lastSavedText) {
                setSaveStatus(CoderSaveStatus.Saved)
                return@withLock true
            }
            val wasFailed = _state.value.saveStatus == CoderSaveStatus.Failed
            setSaveStatus(CoderSaveStatus.Saving)
            val crlf = usesCrlf
            val result = withContext(NonCancellable + Dispatchers.IO) { files.write(projectId, path, text, crlf) }
            if (result == CoderWriteResult.Saved) {
                lastSavedText = text
                setSaveStatus(if (_editorValue.value.text == text) CoderSaveStatus.Saved else CoderSaveStatus.Saving)
                touchProject()
                true
            } else {
                setSaveStatus(CoderSaveStatus.Failed)
                if (loud || !wasFailed) postMessage(saveFailureMessage(result))
                false
            }
        }
    }

    private suspend fun touchProject() {
        try {
            projects.touch(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The list order is cosmetic; a failed touch must never turn a good save into a failure.
        }
    }

    private fun setSaveStatus(status: CoderSaveStatus) {
        _state.update { if (it.saveStatus == status) it else it.copy(saveStatus = status) }
    }

    private fun saveFailureMessage(result: CoderWriteResult): String = when (result) {
        CoderWriteResult.TooLarge -> MSG_TOO_BIG_TO_SAVE
        CoderWriteResult.LimitReached -> MSG_LIMIT
        else -> MSG_SAVE_FAILED
    }

    // ---- File operations -------------------------------------------------------------------

    fun createFile(path: String, text: String = "") {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { files.createFile(projectId, path, text) }
            postMessage(resultMessage(result, "Created file"))
            refreshEntries()
        }
    }

    fun createFolder(path: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { files.createFolder(projectId, path) }
            postMessage(resultMessage(result, "Created folder"))
            refreshEntries()
        }
    }

    /** Renames a file or folder. When it holds the open file, that file is saved and the editor closes first. */
    fun renameEntry(from: String, to: String) {
        viewModelScope.launch {
            if (!closeEditorIfInside(from, mustSave = true)) return@launch
            val result = withContext(Dispatchers.IO) { files.rename(projectId, from, to) }
            postMessage(resultMessage(result, "Renamed"))
            refreshEntries()
        }
    }

    /** Deletes a file or folder. When it holds the open file, the editor closes first. */
    fun deleteEntry(path: String) {
        viewModelScope.launch {
            closeEditorIfInside(path, mustSave = false)
            val deleted = withContext(Dispatchers.IO) { files.delete(projectId, path) }
            postMessage(if (deleted) "Deleted" else MSG_FAILED)
            refreshEntries()
        }
    }

    fun renameProject(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.length > CoderLimits.MAX_PROJECT_NAME_LENGTH) {
                postMessage(MSG_BAD_PROJECT_NAME)
                return@launch
            }
            projects.rename(projectId, trimmed)
            postMessage("Renamed")
        }
    }

    /**
     * Saves and closes the editor when [path] is the open file or a folder that contains it.
     * Returns false only when [mustSave] is set and the text could not be saved (the editor stays open).
     */
    private suspend fun closeEditorIfInside(path: String, mustSave: Boolean): Boolean {
        val open = _state.value.openPath ?: return true
        if (open != path && !open.startsWith("$path/")) return true
        autosaveJob?.cancel()
        val saved = performSave(loud = mustSave)
        if (mustSave && !saved) return false
        resetEditor()
        return true
    }

    private fun resultMessage(result: CoderWriteResult, success: String): String = when (result) {
        CoderWriteResult.Saved -> success
        CoderWriteResult.InvalidPath -> MSG_BAD_NAME
        CoderWriteResult.AlreadyExists -> MSG_EXISTS
        CoderWriteResult.TooLarge, CoderWriteResult.LimitReached -> MSG_LIMIT
        CoderWriteResult.Failed -> MSG_FAILED
    }

    private fun postMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    override fun onCleared() {
        val path = _state.value.openPath
        val text = _editorValue.value.text
        if (path != null && text != lastSavedText) {
            val crlf = usesCrlf
            // Last chance to keep unsaved text: finish the write outside the scope that is being cleared.
            CoroutineScope(Dispatchers.IO).launch { files.write(projectId, path, text, crlf) }
        }
        super.onCleared()
    }
}

/** Finds the changed part between two texts by comparing from both ends. */
private fun describeChange(old: String, updated: String): TextChange {
    val shortest = minOf(old.length, updated.length)
    var prefix = 0
    while (prefix < shortest && old[prefix] == updated[prefix]) prefix++
    var suffix = 0
    val maxSuffix = shortest - prefix
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == updated[updated.length - 1 - suffix]) suffix++
    val oldLength = old.length - prefix - suffix
    val newLength = updated.length - prefix - suffix
    val isNewline = oldLength == 0 && newLength == 1 && updated[prefix] == '\n'
    return TextChange(prefix, oldLength, newLength, isNewline)
}

/** The index where the keyboard inserted one line break, or null when this change is not exactly that. */
private fun newlineInsertionIndex(old: String, updated: TextFieldValue): Int? {
    val text = updated.text
    if (text.length != old.length + 1 || !updated.selection.collapsed) return null
    val at = updated.selection.start - 1
    if (at < 0 || at >= text.length || text[at] != '\n') return null
    if (!text.regionMatches(0, old, 0, at)) return null
    if (!text.regionMatches(at + 1, old, at, old.length - at)) return null
    return at
}

/**
 * Copies the previous line's leading spaces after the new line break, plus two more when that line
 * opens a block. The line break at [at] and the rest of the text stay as the keyboard made them.
 */
private fun withAutoIndent(old: String, updated: TextFieldValue, at: Int, language: CoderLanguage): TextFieldValue {
    val lineStart = if (at == 0) 0 else old.lastIndexOf('\n', at - 1) + 1
    val previousLine = old.substring(lineStart, at)
    var indentEnd = 0
    while (indentEnd < previousLine.length && (previousLine[indentEnd] == ' ' || previousLine[indentEnd] == '\t')) indentEnd++
    var indent = previousLine.substring(0, indentEnd)
    if (opensBlock(previousLine, language)) indent += INDENT_UNIT
    if (indent.isEmpty()) return updated
    val text = updated.text
    val indented = text.substring(0, at + 1) + indent + text.substring(at + 1)
    return TextFieldValue(indented, TextRange(at + 1 + indent.length))
}

private fun opensBlock(line: String, language: CoderLanguage): Boolean {
    val trimmed = line.trimEnd()
    if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(")) return true
    return (language == CoderLanguage.Python || language == CoderLanguage.Yaml) && trimmed.endsWith(":")
}
