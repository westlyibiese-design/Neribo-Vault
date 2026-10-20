package com.westly.neribovault.feature.coder.workspace

import android.os.SystemClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.CoderPrefs
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.CoderUndoStack
import com.westly.neribovault.feature.coder.editor.smart.CoderAutoClose
import com.westly.neribovault.feature.coder.editor.smart.CoderFind
import com.westly.neribovault.feature.coder.editor.smart.CoderIssue
import com.westly.neribovault.feature.coder.editor.smart.CoderLinter
import com.westly.neribovault.feature.coder.editor.smart.CoderSuggestionEngine
import com.westly.neribovault.feature.coder.editor.smart.FindState
import com.westly.neribovault.feature.coder.editor.smart.Suggestion
import com.westly.neribovault.feature.coder.files.CoderConflictChoice
import com.westly.neribovault.feature.coder.files.CoderCopyResult
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileCopy
import com.westly.neribovault.feature.coder.files.CoderRecentFiles
import com.westly.neribovault.feature.coder.files.isCoderHiddenPath
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderProjectSearch
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.files.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the text in the editor has reached the disk. */
enum class CoderSaveStatus { Saved, Saving, Failed }

/**
 * Everything the workspace screen shows. [openPath] is the active tab's path, or null when no file is open.
 * [editorVisible] says whether the editor (true) or the file list (false) is on screen; the tabs stay open
 * while the file list shows.
 */
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
    /** The open files in the order they were opened. */
    val tabs: List<TabInfo> = emptyList(),
    /** True while the editor is on screen; false while the file list shows (open tabs are kept). */
    val editorVisible: Boolean = false,
)

/** One tab: its [path], the text shown on it, whether it has [dirty] (unsaved) text and whether its last save failed. */
data class TabInfo(
    val path: String,
    val label: String,
    val dirty: Boolean,
    val saveFailed: Boolean = false,
)

private enum class ChangeKind { None, Typing, Deleting }

/** Where two texts differ: [start] is the first changed index, followed by how much was removed and added. */
private class TextChange(val start: Int, val oldLength: Int, val newLength: Int, val isSingleNewline: Boolean)

/** Everything one open file keeps while it is a tab: its text and cursor, its own undo history and its save state. */
private class TabState(var path: String, var value: TextFieldValue, var lastSavedText: String, var usesCrlf: Boolean) {
    val undo = CoderUndoStack()
    var lastPushAt = 0L
    var lastKind = ChangeKind.None
    var scrollX = 0
    var scrollY = 0
    var saveFailed = false
    var writing = false
    var autosaveJob: Job? = null

    /** True while the text differs from what is on the disk. */
    val dirty: Boolean get() = value.text != lastSavedText
}

private const val AUTOSAVE_DELAY_MS = 800L
private const val UNDO_PAUSE_MS = 800L
private const val BIG_CHANGE_CHARS = 20
private const val INDENT_UNIT = "  "
private const val MAX_RECENT_SUGGESTIONS = 20
private const val LINT_DELAY_MS = 600L
private const val MAX_QUERY_CHARS = 200
private const val SEARCH_PAUSE_MS = 400L

/** The most characters one change may add and still count as typing (a keyboard word completion); more is a paste. */
private const val MAX_TYPED_CHUNK = 12

/**
 * Gboard and other keyboards keep the word being typed as "composing text". When this is true, suggestions
 * still follow such typing (they never change the text). Auto-close never acts while text is composing.
 */
private const val SUGGEST_WHILE_COMPOSING = true

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
private const val MSG_TOO_MANY_MATCHES = "There are too many matches to replace at once. Search for something longer."
private const val MSG_NOTHING_TO_REPLACE = "Nothing changed. The replacement is the same as the text."
private const val MSG_FILE_GONE = "This file is no longer there."
private const val MSG_MOVE_EXISTS = "A file with that name is already there."
private const val MSG_MOVE_INTO_ITSELF = "A folder can't be moved into itself."
private const val MSG_MOVE_FAILED = "Couldn't move that. Please try again."
private const val MSG_FOLDER_GONE = "That folder is no longer there."
private const val MSG_NO_SPACE = "Not enough space on this phone."

/** One file to copy into the project: where it comes from, where it goes and whether it replaces what is there. */
private class CopyTask(val picked: CoderPickedFile, val path: String, val overwrite: Boolean)

/** What copying a list of files did. [noSpace] is true when the phone ran out of room and the rest was not tried. */
private class CopyOutcome(
    val added: Int,
    val notes: List<String>,
    val firstImagePath: String?,
    val noSpace: Boolean,
)

/**
 * State and actions of one project's workspace: the file list, the open files (one tab each, with its own
 * text, undo history and save state), autosave, the file operations and the smart editor (suggestions,
 * auto-close, mistake underlines and find and replace, all for the active tab). The editor text is only ever
 * changed here, and everything that touches the disk runs on `Dispatchers.IO`.
 */
class CoderWorkspaceViewModel(
    private val projectId: String,
    private val projects: CoderProjectsRepository,
    private val files: CoderFileStore,
    private val prefs: CoderPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(CoderWorkspaceUiState())
    val state: StateFlow<CoderWorkspaceUiState> = _state.asStateFlow()

    private val _editorValue = MutableStateFlow(TextFieldValue(""))

    /** The active tab's text and selection. */
    val editorValue: StateFlow<TextFieldValue> = _editorValue.asStateFlow()

    private val tabStates = LinkedHashMap<String, TabState>()
    private var tabs = CoderTabs(emptyList(), null)
    private var active: TabState? = null
    private val saveMutex = Mutex()
    private var refreshJob: Job? = null
    private var entriesLoaded = false
    private var projectLoaded = false

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())

    /** The ideas shown above the extra keys. Empty when there is nothing to offer. */
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private var suggestionStart = 0
    private var suggestionCaret = -1
    private val recentLabels = ArrayList<String>()
    private var suggestionsOn = true
    private var autoCloseOn = true

    private val _issues = MutableStateFlow<List<CoderIssue>>(emptyList())

    /** The mistakes to underline in the open file. Checked 600 ms after the last change; empty when the switch is off. */
    val issues: StateFlow<List<CoderIssue>> = _issues.asStateFlow()

    private var lintJob: Job? = null
    private var lintOn = true

    /**
     * When the open file last changed, on the `SystemClock.elapsedRealtime` clock; 0 until the first change.
     * The tip host reads it to stay quiet while the person types. It is not screen state: nothing redraws when it changes.
     */
    @Volatile
    var lastTypedAtMillis: Long = 0L
        private set

    /** How many characters were typed since this screen opened (keystrokes and short completions, never a paste). Read by the tip host. */
    @Volatile
    var typedCharCount: Int = 0
        private set

    private val _find = MutableStateFlow(FindState())

    /** The find and replace bar: its texts, switches and the matches in the open file. */
    val find: StateFlow<FindState> = _find.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<String>>(emptyList())

    /** The last 5 files that were opened, most recent first; the file drawer shows them. Files that are gone are dropped. */
    val recentFiles: StateFlow<List<String>> = _recentFiles.asStateFlow()

    private val recentMutex = Mutex()

    private val _jump = MutableStateFlow(CoderJump())

    /** The newest request to scroll the editor to a place (from a search result); [CoderJump.tick] is 0 when there is none. */
    val jump: StateFlow<CoderJump> = _jump.asStateFlow()

    private var jumpCounter = 0

    private val _projectSearch = MutableStateFlow(ProjectSearchState())

    /** The search across all files of the project. */
    val projectSearch: StateFlow<ProjectSearchState> = _projectSearch.asStateFlow()

    private var searchJob: Job? = null

    private val _binaryFile = MutableStateFlow<String?>(null)

    /** The file that cannot be edited and was tapped; the screen shows its sheet. Null when no sheet is wanted. */
    val binaryFile: StateFlow<String?> = _binaryFile.asStateFlow()

    private val _importProgress = MutableStateFlow<CoderImportProgress?>(null)

    /** How far adding files has come; null when nothing is being added. */
    val importProgress: StateFlow<CoderImportProgress?> = _importProgress.asStateFlow()

    private val _importReport = MutableStateFlow<CoderAddReport?>(null)

    /** What the last add did, until it is dismissed. */
    val importReport: StateFlow<CoderAddReport?> = _importReport.asStateFlow()

    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.suggestionsEnabled.collect { on ->
                suggestionsOn = on
                if (!on) clearSuggestions()
            }
        }
        viewModelScope.launch {
            prefs.autoCloseEnabled.collect { on -> autoCloseOn = on }
        }
        viewModelScope.launch {
            prefs.lintEnabled.collect { on ->
                lintOn = on
                if (on) scheduleLint() else clearIssues()
            }
        }
        viewModelScope.launch {
            projects.observeById(projectId).collect { project ->
                projectLoaded = true
                _state.update { it.copy(project = project, isLoading = !entriesLoaded) }
            }
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { CoderRecentFiles.load(files, projectId) }
            _recentFiles.update { CoderRecentFiles.merge(it, saved) }
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
            val present = list.mapTo(HashSet<String>()) { it.path }
            _recentFiles.update { CoderRecentFiles.dropMissing(it, present) }
            closeVanishedTabs(list)
        }
    }

    /**
     * Closes the tabs whose file was removed outside the app. A tab with unsaved text stays open, because its
     * next save writes the file again; only the disk itself decides, not the list, which may be one change old.
     */
    private suspend fun closeVanishedTabs(list: List<CoderEntry>) {
        if (tabStates.isEmpty()) return
        val present = list.mapTo(HashSet<String>()) { it.path }
        val missing = tabs.paths.filter { it !in present && tabStates[it]?.dirty == false }
        if (missing.isEmpty()) return
        val gone = withContext(Dispatchers.IO) { missing.filter { files.resolve(projectId, it)?.exists() != true } }
        val closing = gone.filter { tabStates[it]?.dirty == false }
        if (closing.isEmpty()) return
        var updated = tabs
        for (path in closing) updated = CoderTabLogic.close(updated, path)
        commitTabs(updated, closing)
        postMessage(MSG_FILE_GONE)
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

    // ---- Tabs ------------------------------------------------------------------------------

    /**
     * Opens [path] in a tab and shows the editor, or explains why it can't be opened. A file that is already
     * open only becomes the active tab; it is not read from the disk again.
     */
    fun openFile(path: String): Job {
        return viewModelScope.launch { openTab(path, loud = true, show = true, binarySheet = true) }
    }

    /**
     * Opens [path] through [openFile] (so it is put on the recent list like any other opened file) and, when its editor
     * is showing, moves the cursor to the start of the 1-based [line] and asks the editor to scroll there.
     */
    fun openFileAt(path: String, line: Int) {
        viewModelScope.launch {
            openFile(path).join()
            val tab = active ?: return@launch
            if (tab.path != path || !_state.value.editorVisible) return@launch
            val text = tab.value.text
            val offset = CoderProjectSearch.lineStartOffset(text, line).coerceIn(0, text.length)
            clearSuggestions()
            setActiveValue(TextFieldValue(text, TextRange(offset)))
            jumpCounter++
            _jump.value = CoderJump(path, jumpCounter, offset)
        }
    }

    /** Called by the editor once it has scrolled for the request with [tick], so coming back to the tab later does not jump again. */
    fun consumeJump(tick: Int) {
        if (_jump.value.tick == tick) _jump.value = CoderJump()
    }

    /** Hides the sheet of a file that cannot be edited. */
    fun closeBinarySheet() {
        _binaryFile.value = null
    }

    /** Saves the active tab and closes it. The tab stays open when saving fails, so no text is lost. With no tab left the screen shows the file list. */
    fun closeFile() {
        val tab = active ?: return
        closeTab(tab.path)
    }

    /** Makes [path] the active tab. Its text, cursor and undo history are already in memory. */
    fun activateTab(path: String) {
        val tab = tabStates[path] ?: return
        makeActive(tab, show = true)
    }

    /** Saves and closes the tab [path]. With [discard] it closes even when the save fails; the screen only asks for that after confirming. */
    fun closeTab(path: String, discard: Boolean = false) {
        viewModelScope.launch {
            val tab = tabStates[path] ?: return@launch
            if (!saveBeforeClosing(listOf(tab), discard)) return@launch
            commitTabs(CoderTabLogic.close(tabs, path), listOf(path))
        }
    }

    /** Saves and closes every tab except [path]. */
    fun closeOtherTabs(path: String, discard: Boolean = false) {
        viewModelScope.launch {
            if (path !in tabStates) return@launch
            val others = tabs.paths.filter { it != path }.mapNotNull { tabStates[it] }
            if (!saveBeforeClosing(others, discard)) return@launch
            commitTabs(CoderTabLogic.closeOthers(tabs, path), tabs.paths.filter { it != path })
        }
    }

    /** Saves and closes every tab; the screen then shows the file list. */
    fun closeAllTabs(discard: Boolean = false) {
        viewModelScope.launch {
            val all = tabs.paths.mapNotNull { tabStates[it] }
            if (!saveBeforeClosing(all, discard)) return@launch
            commitTabs(CoderTabLogic.closeAll(tabs), tabs.paths.toList())
        }
    }

    /** Remembers where the tab [path] is scrolled to, so the editor can come back to the same place. Changes no state. */
    fun updateScroll(path: String, x: Int, y: Int) {
        val tab = tabStates[path] ?: return
        tab.scrollX = x
        tab.scrollY = y
    }

    /** Where the tab [path] was last scrolled to, as (x, y) in pixels; (0, 0) for an unknown tab. */
    fun scrollOf(path: String): Pair<Int, Int> {
        val tab = tabStates[path] ?: return Pair(0, 0)
        return Pair(tab.scrollX, tab.scrollY)
    }

    /** Saves every tab and shows the file list. The tabs stay open. When a save fails the editor stays, so no text is lost. */
    fun showFiles() {
        viewModelScope.launch {
            if (saveNow()) _state.update { it.copy(editorVisible = false) }
        }
    }

    /**
     * Opens the tabs that were open before the app was stopped, in their old order, and makes [activePath]
     * active. A file that is missing or can't be opened is skipped without a message. Does nothing when tabs are open.
     */
    suspend fun restoreTabs(paths: List<String>, activePath: String?, show: Boolean) {
        if (tabs.paths.isNotEmpty()) return
        for (path in paths.take(CODER_MAX_TABS)) openTab(path, loud = false, show = false)
        val target = activePath?.let { tabStates[it] }
        if (target != null) makeActive(target, show = false)
        _state.update { it.copy(editorVisible = show && tabs.paths.isNotEmpty()) }
    }

    /**
     * Opens [path] as a tab, or activates it when it is already open. False when it could not be opened. A file that
     * is shown (not one restored quietly after the app stopped) is put on the recent list, so every way of opening
     * a file is recorded here. With [binarySheet] a file that cannot be edited asks for its sheet instead of a message.
     */
    private suspend fun openTab(path: String, loud: Boolean, show: Boolean, binarySheet: Boolean = false): Boolean {
        val opened = openTabCore(path, loud, show, binarySheet)
        if (opened && show) recordRecent(path)
        return opened
    }

    private suspend fun openTabCore(path: String, loud: Boolean, show: Boolean, binarySheet: Boolean): Boolean {
        val existing = tabStates[path]
        if (existing != null) {
            makeActive(existing, show)
            return true
        }
        if (!CoderPaths.isValidPath(path)) {
            if (loud) postMessage(MSG_CANNOT_OPEN)
            return false
        }
        if (binarySheet) {
            val size = _state.value.entries.firstOrNull { it.path == path }?.sizeBytes ?: 0L
            val kind = coderFileKindOf(path, size)
            if (kind == CoderFileKind.Image || kind == CoderFileKind.Other) return showBinarySheet(path)
        }
        val message = when (val result = withContext(Dispatchers.IO) { files.read(projectId, path) }) {
            is CoderReadResult.Text -> return addTab(path, result, loud, show)
            CoderReadResult.TooLarge -> if (binarySheet) return showBinarySheet(path) else MSG_TOO_BIG_TO_OPEN
            CoderReadResult.Binary -> if (binarySheet) return showBinarySheet(path) else MSG_NOT_TEXT
            CoderReadResult.Missing, CoderReadResult.Failed -> MSG_CANNOT_OPEN
        }
        if (loud) postMessage(message)
        return false
    }

    private fun showBinarySheet(path: String): Boolean {
        _binaryFile.value = path
        return false
    }

    /** Puts [path] first on the recent list and saves the list in the background. */
    private fun recordRecent(path: String) {
        val updated = CoderRecentFiles.record(_recentFiles.value, path)
        if (updated == _recentFiles.value) return
        _recentFiles.value = updated
        saveRecent(updated)
    }

    /** Writes [list] to the hidden recent file. Writes wait for each other, so the last list always wins. */
    private fun saveRecent(list: List<String>) {
        viewModelScope.launch {
            recentMutex.withLock { withContext(Dispatchers.IO) { CoderRecentFiles.save(files, projectId, list) } }
        }
    }

    /** Adds a tab for a file that was just read. When 12 tabs are open the least recently used clean one makes room. */
    private fun addTab(path: String, result: CoderReadResult.Text, loud: Boolean, show: Boolean): Boolean {
        val existing = tabStates[path]
        if (existing != null) {
            makeActive(existing, show)
            return true
        }
        return when (val outcome = CoderTabLogic.open(tabs, path) { p -> tabStates[p]?.dirty == true }) {
            is OpenOutcome.Refused -> {
                if (loud) postMessage(outcome.reason)
                false
            }
            is OpenOutcome.Activated -> {
                tabStates[path]?.let { makeActive(it, show) }
                true
            }
            is OpenOutcome.Opened -> {
                outcome.closedPath?.let { dropTab(it) }
                val tab = TabState(path, TextFieldValue(result.text, TextRange(0)), result.text, result.usesCrlf)
                tabStates[path] = tab
                tabs = outcome.tabs
                makeActive(tab, show)
                true
            }
        }
    }

    /**
     * Saves [list] before its tabs close. False when a save failed (a message says so) and the tabs must stay.
     * With [discard] every save is still tried, quietly, but a failure no longer keeps a tab open.
     */
    private suspend fun saveBeforeClosing(list: List<TabState>, discard: Boolean): Boolean {
        for (tab in list) {
            tab.autosaveJob?.cancel()
            val saved = performSave(tab, loud = !discard)
            if (!discard && (!saved || tab.dirty)) return false
        }
        return true
    }

    private fun dropTab(path: String) {
        tabStates.remove(path)?.autosaveJob?.cancel()
    }

    /** Makes [tab] the active one; with [show] the editor is shown. Suggestions, underlines and find are worked out again for it. */
    private fun makeActive(tab: TabState, show: Boolean) {
        tabs = CoderTabLogic.activate(tabs, tab.path)
        val changed = switchTo(tab)
        if (show) _state.update { it.copy(editorVisible = true) }
        publishTabs()
        if (changed) afterSwitch()
    }

    /** Puts the tab list in [newTabs] after [removed] were closed, and moves to the tab that is active in it. */
    private fun commitTabs(newTabs: CoderTabs, removed: Collection<String>) {
        for (path in removed) dropTab(path)
        tabs = newTabs
        val next = newTabs.active?.let { tabStates[it] }
        val changed = switchTo(next)
        if (next != null) tabs = CoderTabLogic.activate(tabs, next.path)
        publishTabs()
        if (changed) afterSwitch()
    }

    /** Points the editor at [tab] (or at nothing). The text comes from memory; the disk is not read. True when the active tab changed. */
    private fun switchTo(tab: TabState?): Boolean {
        if (active === tab) return false
        clearSuggestions()
        clearIssues()
        active = tab
        _editorValue.value = tab?.value ?: TextFieldValue("")
        _find.update { if (tab == null) FindState() else it.copy(matches = emptyList(), current = -1) }
        return true
    }

    private fun afterSwitch() {
        if (active == null) return
        if (_find.value.open) recomputeMatches(select = false)
        scheduleLint()
    }

    /** Copies the tab list, the dots, the save status and the undo buttons into the screen state. */
    private fun publishTabs() {
        val labels = CoderTabLogic.labels(tabs.paths)
        val infos = tabs.paths.map { path ->
            val tab = tabStates[path]
            TabInfo(path, labels[path] ?: CoderPaths.nameOf(path), tab?.dirty == true, tab?.saveFailed == true)
        }
        val current = active
        val status = aggregateSaveStatus(
            anyWriting = tabStates.values.any { it.writing },
            activeFailed = current?.saveFailed == true,
            activePending = current?.dirty == true,
        )
        _state.update {
            it.copy(
                tabs = infos,
                openPath = tabs.active,
                saveStatus = status,
                canUndo = current?.undo?.canUndo == true,
                canRedo = current?.undo?.canRedo == true,
                editorVisible = it.editorVisible && infos.isNotEmpty(),
            )
        }
    }

    /** Sets the active tab's text and selection, in memory and for the screen. */
    private fun setActiveValue(value: TextFieldValue) {
        active?.value = value
        _editorValue.value = value
    }

    // ---- Typing ----------------------------------------------------------------------------

    /**
     * Takes what the keyboard produced, applies auto-close for a single typed character, adds auto-indent after
     * Enter and decides whether this starts a new undo step. Also refreshes the suggestions.
     */
    fun onEditorChange(value: TextFieldValue) {
        val tab = active ?: return
        val path = tab.path
        val old = _editorValue.value
        if (value.text == old.text) {
            setActiveValue(value)
            dropSuggestionsWhenCaretMoved(value)
            return
        }
        val language = CoderLanguage.fromPath(path)
        val edit = singleEditKind(old, value)
        val smart = if (autoCloseOn && edit != SingleEdit.None) CoderAutoClose.apply(old, value, language) else null
        if (smart != null && smart.text == old.text) {
            // Typing a closing bracket or quote that is already there only moves the caret.
            setActiveValue(smart)
            clearSuggestions()
            return
        }
        val change = describeChange(old.text, value.text)
        val addedChars = change.newLength - change.oldLength
        if (addedChars in 1..MAX_TYPED_CHUNK) typedCharCount += addedChars
        val newlineAt = newlineInsertionIndex(old.text, value) ?: if (change.isSingleNewline) change.start else -1
        val applied = when {
            smart != null -> smart
            newlineAt >= 0 -> withAutoIndent(old.text, value, newlineAt, language)
            else -> value
        }
        val kind = if (value.text.length < old.text.length) ChangeKind.Deleting else ChangeKind.Typing
        val now = SystemClock.elapsedRealtime()
        val large = change.oldLength >= BIG_CHANGE_CHARS || change.newLength >= BIG_CHANGE_CHARS
        val startsStep = tab.lastPushAt == 0L ||
            now - tab.lastPushAt > UNDO_PAUSE_MS ||
            kind != tab.lastKind ||
            large ||
            newlineAt >= 0
        if (startsStep) {
            tab.undo.push(snapshot(old))
            tab.lastPushAt = now
        }
        tab.lastKind = kind
        setActiveValue(applied)
        afterEdit()
        onTextChanged(old.text, applied.text)
        refreshSuggestions(edit, value, applied, language)
    }

    // ---- Suggestions -----------------------------------------------------------------------

    /**
     * Replaces the typed word with [s] as exactly one undo step and puts the caret where the suggestion says.
     * Ignored when the caret has moved since the suggestions were made. Remembers the label among the last 20 accepted.
     */
    fun acceptSuggestion(s: Suggestion) {
        val current = openEditorValue() ?: return
        val caret = current.selection.start
        val stale = !current.selection.collapsed || caret != suggestionCaret ||
            suggestionStart < 0 || suggestionStart > caret || caret > current.text.length
        if (stale) {
            clearSuggestions()
            return
        }
        replaceRangeWithCaret(suggestionStart, caret, s.insertText, s.caretOffset)
        recentLabels.remove(s.label)
        recentLabels.add(s.label)
        while (recentLabels.size > MAX_RECENT_SUGGESTIONS) recentLabels.removeAt(0)
        clearSuggestions()
    }

    private fun refreshSuggestions(edit: SingleEdit, typed: TextFieldValue, applied: TextFieldValue, language: CoderLanguage) {
        val composing = typed.composition
        val compositionOk = composing == null || (SUGGEST_WHILE_COMPOSING && composing.end == typed.selection.end)
        if (!suggestionsOn || edit == SingleEdit.None || !compositionOk || !applied.selection.collapsed) {
            clearSuggestions()
            return
        }
        val result = CoderSuggestionEngine.suggestFor(applied, language, recentLabels)
        if (result.items.isEmpty()) {
            clearSuggestions()
            return
        }
        suggestionStart = result.replaceStart
        suggestionCaret = applied.selection.start
        _suggestions.value = result.items
    }

    private fun dropSuggestionsWhenCaretMoved(value: TextFieldValue) {
        if (_suggestions.value.isEmpty()) return
        if (!value.selection.collapsed || value.selection.start != suggestionCaret) clearSuggestions()
    }

    private fun clearSuggestions() {
        suggestionCaret = -1
        if (_suggestions.value.isNotEmpty()) _suggestions.value = emptyList()
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

    /**
     * Replaces the characters from [start] up to [end] of the open file with [text] as exactly one undo step.
     * Ignored when no file is open or the range does not fit the current text.
     */
    fun replaceRange(start: Int, end: Int, text: String) {
        replaceRangeWithCaret(start, end, text, text.length)
    }

    /** Like [replaceRange], but the caret ends [caretInText] characters into the inserted [text]. */
    private fun replaceRangeWithCaret(start: Int, end: Int, text: String, caretInText: Int) {
        val old = openEditorValue() ?: return
        if (start < 0 || end < start || end > old.text.length) return
        val caret = start + caretInText.coerceIn(0, text.length)
        applyStep(old.text.substring(0, start) + text + old.text.substring(end), TextRange(caret))
    }

    /**
     * Opens [path] (or activates its tab) and replaces all of its text with [text] as one undo step. False when
     * the file could not be opened; a calm message says why and nothing is replaced.
     */
    suspend fun openAndReplaceAll(path: String, text: String): Boolean {
        if (!CoderPaths.isValidPath(path)) {
            postMessage(MSG_CANNOT_OPEN)
            return false
        }
        if (active?.path != path || !_state.value.editorVisible) {
            if (!openTab(path, loud = true, show = true)) return false
        }
        replaceAll(text)
        return true
    }

    fun undo() {
        val tab = active ?: return
        val previous = tab.undo.undo(snapshot(_editorValue.value)) ?: return
        applyHistory(previous)
    }

    fun redo() {
        val tab = active ?: return
        val next = tab.undo.redo(snapshot(_editorValue.value)) ?: return
        applyHistory(next)
    }

    private fun openEditorValue(): TextFieldValue? =
        if (active == null) null else _editorValue.value

    private fun applyStep(newText: String, selection: TextRange) {
        val tab = active ?: return
        clearSuggestions()
        val old = _editorValue.value
        if (newText == old.text) {
            setActiveValue(TextFieldValue(newText, selection))
            return
        }
        tab.undo.push(snapshot(old))
        tab.lastPushAt = SystemClock.elapsedRealtime()
        tab.lastKind = ChangeKind.None
        setActiveValue(TextFieldValue(newText, selection))
        afterEdit()
        onTextChanged(old.text, newText)
    }

    private fun applyHistory(value: TextFieldValue) {
        val tab = active ?: return
        clearSuggestions()
        val oldText = _editorValue.value.text
        tab.lastPushAt = 0L
        tab.lastKind = ChangeKind.None
        setActiveValue(value)
        afterEdit()
        if (oldText != value.text) onTextChanged(oldText, value.text)
    }

    private fun snapshot(value: TextFieldValue): TextFieldValue = TextFieldValue(value.text, value.selection)

    private fun afterEdit() {
        publishTabs()
        active?.let { scheduleAutosave(it) }
    }

    // ---- Mistake underlines ----------------------------------------------------------------

    /** Called after every change of the open file's text: keeps the underlines in place, checks again later and refreshes the find matches. */
    private fun onTextChanged(oldText: String, newText: String) {
        lastTypedAtMillis = SystemClock.elapsedRealtime()
        val change = describeChange(oldText, newText)
        shiftIssues(change.start, change.oldLength, change.newLength)
        scheduleLint()
        if (_find.value.open) recomputeMatches(select = false)
    }

    /** Moves the underlines that lie after the change, and drops the ones the change touched. */
    private fun shiftIssues(changeStart: Int, oldLength: Int, newLength: Int) {
        val current = _issues.value
        if (current.isEmpty()) return
        val delta = newLength - oldLength
        val changeEnd = changeStart + oldLength
        val kept = ArrayList<CoderIssue>(current.size)
        for (issue in current) {
            if (issue.end <= changeStart) {
                kept.add(issue)
            } else if (issue.start >= changeEnd) {
                kept.add(if (delta == 0) issue else issue.copy(start = issue.start + delta, end = issue.end + delta))
            }
        }
        _issues.value = kept
    }

    /** Checks the open file once the person has paused for 600 ms. Does nothing (and clears) when underlines are off or the file is too long. */
    private fun scheduleLint() {
        lintJob?.cancel()
        val path = active?.path
        val text = _editorValue.value.text
        if (!lintOn || path == null || text.length > CoderLinter.MAX_CHARS) {
            clearIssues()
            return
        }
        val language = CoderLanguage.fromPath(path)
        lintJob = viewModelScope.launch {
            delay(LINT_DELAY_MS)
            val found = withContext(Dispatchers.Default) { CoderLinter.lint(text, language) }
            if (active?.path == path && _editorValue.value.text == text) _issues.value = found
        }
    }

    private fun clearIssues() {
        lintJob?.cancel()
        if (_issues.value.isNotEmpty()) _issues.value = emptyList()
    }

    // ---- Search across all files -----------------------------------------------------------

    /** Opens the project search. A search typed earlier is kept and shown again. */
    fun openProjectSearch() {
        _projectSearch.update { it.copy(open = true) }
        scheduleProjectSearch(pause = false)
    }

    /** Closes the project search and stops a running scan. The query stays for next time. */
    fun closeProjectSearch() {
        searchJob?.cancel()
        _projectSearch.update { it.copy(open = false, searching = false) }
    }

    fun setProjectSearchQuery(query: String) {
        _projectSearch.update { it.copy(query = query.take(MAX_QUERY_CHARS)) }
        scheduleProjectSearch(pause = true)
    }

    fun toggleProjectSearchCase() {
        _projectSearch.update { it.copy(caseSensitive = !it.caseSensitive) }
        scheduleProjectSearch(pause = true)
    }

    fun toggleProjectSearchWholeWord() {
        _projectSearch.update { it.copy(wholeWord = !it.wholeWord) }
        scheduleProjectSearch(pause = true)
    }

    /**
     * Starts a search for the current query after a 400 ms pause (every call cancels the one before). The scan runs on
     * `Dispatchers.Default`. Open files with unsaved text are searched with that text, so line numbers match the editor.
     */
    private fun scheduleProjectSearch(pause: Boolean) {
        searchJob?.cancel()
        val request = _projectSearch.value
        if (!CoderProjectSearch.isQueryAllowed(request.query)) {
            _projectSearch.update { it.copy(results = null, searching = false) }
            return
        }
        _projectSearch.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            if (pause) delay(SEARCH_PAUSE_MS)
            val dirty = HashMap<String, String>()
            for (tab in tabStates.values) if (tab.dirty) dirty[tab.path] = tab.value.text
            val found = withContext(Dispatchers.Default) {
                scanProject(this, request.query, request.caseSensitive, request.wholeWord, dirty)
            }
            _projectSearch.update {
                if (it.query == request.query && it.caseSensitive == request.caseSensitive && it.wholeWord == request.wholeWord) {
                    it.copy(results = found, searching = false)
                } else {
                    it
                }
            }
        }
    }

    private fun scanProject(scope: CoroutineScope, query: String, caseSensitive: Boolean, wholeWord: Boolean, dirty: Map<String, String>): SearchResults {
        val list = files.listAll(projectId).filter { !it.isDirectory && CoderProjectSearch.isSearchable(it.path) }
        val sequence = list.asSequence().map { entry ->
            scope.ensureActive()
            Pair(entry.path, dirty[entry.path] ?: readSearchText(entry))
        }
        return CoderProjectSearch.search(sequence, query, caseSensitive, wholeWord, CoderProjectSearch.MAX_TEXT_MATCHES)
    }

    /** The text of a file for searching, or "" when it is not an editable text file (so only its name is searched). */
    private fun readSearchText(entry: CoderEntry): String {
        val kind = coderFileKindOf(entry.path, entry.sizeBytes)
        if (kind == CoderFileKind.Image || kind == CoderFileKind.Other) return ""
        return (files.read(projectId, entry.path) as? CoderReadResult.Text)?.text ?: ""
    }

    // ---- Find and replace ------------------------------------------------------------------

    /** Opens the find bar. A search typed earlier in this file is still there. */
    fun openFind() {
        if (active == null) return
        _find.update { it.copy(open = true) }
        recomputeMatches(select = false)
    }

    /** Closes the find bar and removes the match tints. The query is kept until another file opens. */
    fun closeFind() {
        _find.update { it.copy(open = false, matches = emptyList(), current = -1) }
    }

    /** Sets what to look for and moves to the nearest match. */
    fun setQuery(query: String) {
        _find.update { it.copy(query = query.take(MAX_QUERY_CHARS)) }
        recomputeMatches(select = true)
    }

    fun setReplacement(replacement: String) {
        _find.update { it.copy(replacement = replacement.take(MAX_QUERY_CHARS)) }
    }

    /** Switches between matching upper and lower case exactly and ignoring it. */
    fun toggleCase() {
        _find.update { it.copy(caseSensitive = !it.caseSensitive) }
        recomputeMatches(select = true)
    }

    /** Switches whole-word matching on or off. */
    fun toggleWholeWord() {
        _find.update { it.copy(wholeWord = !it.wholeWord) }
        recomputeMatches(select = true)
    }

    /** Goes to the next match, wrapping around at the end. */
    fun next() {
        step(forward = true)
    }

    /** Goes to the previous match, wrapping around at the start. */
    fun previous() {
        step(forward = false)
    }

    /** Replaces the current match as exactly one undo step, then moves to the next one. */
    fun replaceCurrent() {
        val state = _find.value
        val range = state.matches.getOrNull(state.current) ?: return
        val text = openEditorValue()?.text ?: return
        val end = range.last + 1
        if (range.first < 0 || end > text.length) return
        if (text.substring(range.first, end) == state.replacement) {
            step(forward = true)
            return
        }
        replaceRange(range.first, end, state.replacement)
        selectCurrentMatch()
    }

    /** Replaces every match as exactly one undo step. Not offered when the match list was cut at its limit. */
    fun replaceAllMatches() {
        val state = _find.value
        if (state.matches.isEmpty()) return
        if (state.matches.size >= CoderFind.MAX_MATCHES) {
            postMessage(MSG_TOO_MANY_MATCHES)
            return
        }
        val old = openEditorValue()?.text ?: return
        val updated = CoderFind.replaceAll(old, state.matches, state.replacement)
        if (updated == old) {
            postMessage(MSG_NOTHING_TO_REPLACE)
            return
        }
        val count = state.matches.size
        applyReplaceAll(updated)
        postMessage(if (count == 1) "Replaced 1 match" else "Replaced $count matches")
    }

    /** Puts [newText] in place of the whole file as exactly one undo step. The caret stays where it was, or at the end when the text got shorter. */
    fun applyReplaceAll(newText: String) {
        val old = openEditorValue() ?: return
        applyStep(newText, TextRange(old.selection.min.coerceIn(0, newText.length)))
    }

    /** Finds the matches again for the current text and settings; with [select] the editor also moves to the nearest one. */
    private fun recomputeMatches(select: Boolean) {
        val state = _find.value
        val value = openEditorValue()
        if (!state.open || state.query.isEmpty() || value == null) {
            _find.update { it.copy(matches = emptyList(), current = -1) }
            return
        }
        val matches = CoderFind.findAll(value.text, state.query, state.caseSensitive, state.wholeWord)
        val current = CoderFind.nextIndex(matches, value.selection.min, true)
        _find.update { it.copy(matches = matches, current = current) }
        if (select) selectCurrentMatch()
    }

    private fun step(forward: Boolean) {
        val state = _find.value
        val value = openEditorValue() ?: return
        if (state.matches.isEmpty()) return
        val range = state.matches.getOrNull(state.current)
        val caret = if (range != null && value.selection.min == range.first && value.selection.max == range.last + 1) {
            if (forward) range.last + 1 else range.first
        } else if (forward) {
            value.selection.max
        } else {
            value.selection.min
        }
        _find.update { it.copy(current = CoderFind.nextIndex(state.matches, caret, forward)) }
        selectCurrentMatch()
    }

    /** Selects the current match so the editor shows it, and asks the editor to scroll there. The text is not changed. */
    private fun selectCurrentMatch() {
        val state = _find.value
        val range = state.matches.getOrNull(state.current) ?: return
        val value = openEditorValue() ?: return
        val end = range.last + 1
        if (range.first < 0 || end > value.text.length) return
        clearSuggestions()
        setActiveValue(TextFieldValue(value.text, TextRange(range.first, end)))
        _find.update { it.copy(reveal = it.reveal + 1) }
    }

    private fun clearFind() {
        _find.value = FindState()
    }

    // ---- Saving ----------------------------------------------------------------------------

    /** Writes every tab with unsaved text now. True when nothing needed saving or every write succeeded. */
    suspend fun saveNow(): Boolean {
        var allSaved = true
        for (tab in tabStates.values.toList()) {
            tab.autosaveJob?.cancel()
            if (!performSave(tab, loud = true)) allSaved = false
        }
        return allSaved
    }

    /** Saves every tab in the background without blocking the caller; used when the screen stops or closes. */
    fun flush() {
        for (tab in tabStates.values.toList()) {
            tab.autosaveJob?.cancel()
            viewModelScope.launch { performSave(tab, loud = false) }
        }
    }

    private fun scheduleAutosave(tab: TabState) {
        tab.autosaveJob?.cancel()
        tab.autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            // The write runs in its own job so that the next keystroke cannot cancel it half way.
            viewModelScope.launch { performSave(tab, loud = false) }
        }
    }

    /**
     * Writes [tab] when its text changed. Writes are one at a time, and a tab that was closed or deleted in the
     * meantime is skipped, so a deleted file is never written back.
     */
    private suspend fun performSave(tab: TabState, loud: Boolean): Boolean {
        return saveMutex.withLock {
            if (tabStates[tab.path] !== tab) return@withLock true
            val path = tab.path
            val text = tab.value.text
            if (text == tab.lastSavedText) {
                tab.saveFailed = false
                publishTabs()
                return@withLock true
            }
            val wasFailed = tab.saveFailed
            val crlf = tab.usesCrlf
            tab.writing = true
            publishTabs()
            val result = try {
                withContext(NonCancellable + Dispatchers.IO) { files.write(projectId, path, text, crlf) }
            } finally {
                tab.writing = false
            }
            if (result == CoderWriteResult.Saved) {
                tab.lastSavedText = text
                tab.saveFailed = false
                publishTabs()
                touchProject()
                true
            } else {
                tab.saveFailed = true
                publishTabs()
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

    /**
     * Renames a file or folder. Open tabs under it are saved first; then they follow the new name and keep their
     * text, undo history and place. The rename runs while no save can write, so nothing lands under the old name.
     */
    fun renameEntry(from: String, to: String) {
        viewModelScope.launch {
            val affected = tabs.paths.filter { CoderTabLogic.isInside(it, from) }.mapNotNull { tabStates[it] }
            for (tab in affected) {
                tab.autosaveJob?.cancel()
                if (!performSave(tab, loud = true)) return@launch
            }
            val result = saveMutex.withLock {
                val outcome = withContext(Dispatchers.IO) { files.rename(projectId, from, to) }
                if (outcome == CoderWriteResult.Saved) renameTabs(from, to)
                outcome
            }
            postMessage(resultMessage(result, "Renamed"))
            refreshEntries()
        }
    }

    /** Deletes a file or folder. Open tabs under it close without another save. */
    fun deleteEntry(path: String) {
        viewModelScope.launch {
            val deleted = saveMutex.withLock {
                val victims = tabs.paths.filter { CoderTabLogic.isInside(it, path) }
                if (victims.isNotEmpty()) commitTabs(CoderTabLogic.removed(tabs, path), victims)
                withContext(Dispatchers.IO) { files.delete(projectId, path) }
            }
            postMessage(if (deleted) "Deleted" else MSG_FAILED)
            refreshEntries()
        }
    }

    /** Gives the tabs under [from] their new paths. Called while the save lock is held. */
    private fun renameTabs(from: String, to: String) {
        val moved = ArrayList<TabState>()
        for (tab in tabStates.values.toList()) {
            val newPath = CoderTabLogic.mapPath(tab.path, from, to)
            if (newPath == tab.path) continue
            tabStates.remove(tab.path)
            tab.path = newPath
            moved.add(tab)
        }
        for (tab in moved) tabStates[tab.path] = tab
        tabs = CoderTabLogic.rename(tabs, from, to)
        val renamedRecent = CoderRecentFiles.renamed(_recentFiles.value, from, to)
        if (renamedRecent != _recentFiles.value) {
            _recentFiles.value = renamedRecent
            saveRecent(renamedRecent)
        }
        publishTabs()
        if (moved.any { it === active }) scheduleLint()
    }

    /**
     * Moves a file or folder into the folder [newParent] ("" is the top of the project). Open tabs under it are saved
     * first and then follow the new place, like a rename. Nothing is overwritten.
     */
    fun moveEntry(from: String, newParent: String) {
        viewModelScope.launch {
            if (!CoderFileCopy.canMoveInto(from, newParent)) {
                postMessage(MSG_MOVE_INTO_ITSELF)
                return@launch
            }
            val to = CoderFileCopy.movedPath(from, newParent)
            val affected = tabs.paths.filter { CoderTabLogic.isInside(it, from) }.mapNotNull { tabStates[it] }
            for (tab in affected) {
                tab.autosaveJob?.cancel()
                if (!performSave(tab, loud = true)) return@launch
            }
            val result = saveMutex.withLock {
                val outcome = withContext(Dispatchers.IO) { files.moveEntry(projectId, from, newParent) }
                if (outcome == CoderWriteResult.Saved && to != from) renameTabs(from, to)
                outcome
            }
            postMessage(
                when (result) {
                    CoderWriteResult.Saved -> "Moved"
                    CoderWriteResult.AlreadyExists -> MSG_MOVE_EXISTS
                    CoderWriteResult.InvalidPath -> MSG_BAD_NAME
                    else -> MSG_MOVE_FAILED
                },
            )
            refreshEntries()
        }
    }

    /**
     * Copies the picked files into [folder] ("" is the top of the project). Names are cleaned first; a name that is
     * already used follows [choice] for all such files (a name used twice inside the batch is always kept apart).
     * Progress and the report are in [importProgress] and [importReport]; [cancelImport] stops it and removes the
     * file being copied. Ignored while another add runs.
     */
    fun importFiles(picked: List<CoderPickedFile>, folder: String, choice: CoderConflictChoice) {
        if (picked.isEmpty() || importJob?.isActive == true) return
        importJob = viewModelScope.launch { runImport(picked, folder, choice) }
    }

    /** Overwrites the file at [path] with [picked]. Open tabs on it are closed first; the old file stays when the copy fails. */
    fun replaceFile(path: String, picked: CoderPickedFile) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            val outcome = copyAll(listOf(CopyTask(picked, path, true)), showProgress = false, folder = CoderPaths.parentOf(path))
            refreshEntries()
            postMessage(
                when {
                    outcome.added == 1 -> "File replaced"
                    outcome.noSpace -> MSG_NO_SPACE
                    else -> outcome.notes.firstOrNull() ?: MSG_FAILED
                },
            )
        }
    }

    /** Stops adding files. The file being copied is removed; files that were already added stay. */
    fun cancelImport() {
        importJob?.cancel()
    }

    /** Hides the report of the last add. */
    fun dismissImportReport() {
        _importReport.value = null
    }

    private suspend fun runImport(picked: List<CoderPickedFile>, folder: String, choice: CoderConflictChoice) {
        val listing = withContext(Dispatchers.IO) { files.listAll(projectId) }
        val folderOk = folder.isEmpty() ||
            (CoderPaths.isValidPath(folder) && !isCoderHiddenPath(folder) && listing.any { it.isDirectory && it.path == folder })
        if (!folderOk) {
            postMessage(MSG_FOLDER_GONE)
            return
        }
        val inFolder = listing.filter { CoderPaths.parentOf(it.path) == folder }
        val onDisk = inFolder.mapTo(HashSet<String>()) { CoderPaths.nameOf(it.path) }
        val folders = inFolder.filter { it.isDirectory }.mapTo(HashSet<String>()) { CoderPaths.nameOf(it.path) }
        val assigned = HashSet<String>()
        val notes = ArrayList<String>()
        val tasks = ArrayList<CopyTask>()
        for (file in picked) {
            val clean = CoderFileCopy.sanitizeName(file.displayName)
            val plan = CoderFileCopy.planName(onDisk, assigned, clean, choice)
            val name = plan.name
            if (name == null) {
                notes.add("$clean: the name is already used")
                continue
            }
            val path = CoderPaths.join(folder, name)
            val size = file.sizeBytes
            when {
                plan.replaces && name in folders -> notes.add("$clean: a folder has that name")
                isCoderHiddenPath(path) -> notes.add("$clean: the name can't be used")
                size != null && size > CoderFileCopy.MAX_FILE_BYTES -> notes.add("$clean: bigger than 20 MB")
                else -> {
                    assigned.add(name)
                    tasks.add(CopyTask(file, path, plan.replaces))
                }
            }
        }
        val outcome = try {
            copyAll(tasks, showProgress = true, folder = folder)
        } catch (e: CancellationException) {
            postMessage("Adding stopped. Files that were already added stay.")
            throw e
        } finally {
            _importProgress.value = null
            refreshEntries()
        }
        val spaceNote = if (outcome.noSpace) listOf(MSG_NO_SPACE) else emptyList<String>()
        val allNotes = notes + outcome.notes + spaceNote
        _importReport.value = CoderAddReport(
            added = outcome.added,
            skipped = picked.size - outcome.added,
            folder = folder,
            notes = allNotes,
            firstImagePath = outcome.firstImagePath,
        )
    }

    /**
     * Copies each task in turn. A replaced file's open tabs close first, so an old autosave can never write over the
     * new file. Stops at once when cancelled (the copy being made is removed) and when the phone is out of space.
     */
    private suspend fun copyAll(tasks: List<CopyTask>, showProgress: Boolean, folder: String): CopyOutcome {
        var added = 0
        var firstImage: String? = null
        var noSpace = false
        val notes = ArrayList<String>()
        for ((index, task) in tasks.withIndex()) {
            if (showProgress) {
                _importProgress.value = CoderImportProgress(index, tasks.size, CoderPaths.nameOf(task.path), folder)
            }
            if (task.overwrite) closeTabsAt(task.path)
            val result = withContext(Dispatchers.IO) {
                val scope = this
                val stream = task.picked.open()
                if (stream == null) {
                    CoderCopyResult.Failed
                } else {
                    stream.use { files.copyIn(projectId, task.path, it, task.overwrite, shouldContinue = { scope.isActive }) }
                }
            }
            val label = CoderPaths.nameOf(task.path)
            when (result) {
                is CoderCopyResult.Copied -> {
                    added++
                    if (firstImage == null && CoderFileCopy.isImageName(label)) firstImage = task.path
                }
                CoderCopyResult.TooLarge -> notes.add("$label: bigger than 20 MB")
                CoderCopyResult.LimitReached -> notes.add("$label: the project is full (50 MB or 2,000 files)")
                CoderCopyResult.AlreadyExists -> notes.add("$label: the name is already used")
                CoderCopyResult.InvalidPath -> notes.add("$label: the name can't be used")
                CoderCopyResult.Cancelled, CoderCopyResult.Failed -> notes.add("$label: couldn't be read")
                CoderCopyResult.NoSpace -> noSpace = true
            }
            if (noSpace) break
        }
        if (added > 0) touchProject()
        return CopyOutcome(added, notes, firstImage, noSpace)
    }

    /** Closes the tabs at [path] (or inside it) without saving them. Used before a file is overwritten from outside. */
    private suspend fun closeTabsAt(path: String) {
        saveMutex.withLock {
            val victims = tabs.paths.filter { CoderTabLogic.isInside(it, path) }
            if (victims.isNotEmpty()) commitTabs(CoderTabLogic.removed(tabs, path), victims)
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
        for (tab in tabStates.values) {
            if (!tab.dirty) continue
            val path = tab.path
            val text = tab.value.text
            val crlf = tab.usesCrlf
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

private enum class SingleEdit { None, Insert, Delete }

/**
 * Tells a plain keystroke from every other change. [SingleEdit.Insert] means [updated] is [old] plus exactly one
 * character typed at the caret; [SingleEdit.Delete] means exactly one character before the caret was removed
 * (Backspace). Nothing may be selected before or after. Paste, several characters, replacing a selection and
 * every other change give [SingleEdit.None]. Whether the keyboard still has text composing is checked by the callers.
 */
private fun singleEditKind(old: TextFieldValue, updated: TextFieldValue): SingleEdit {
    if (!old.selection.collapsed || !updated.selection.collapsed) return SingleEdit.None
    val before = old.text
    val after = updated.text
    val caretBefore = old.selection.start
    val caretAfter = updated.selection.start
    if (caretBefore < 0 || caretBefore > before.length) return SingleEdit.None
    if (after.length == before.length + 1) {
        if (caretAfter != caretBefore + 1) return SingleEdit.None
        if (!after.regionMatches(0, before, 0, caretBefore)) return SingleEdit.None
        if (!after.regionMatches(caretBefore + 1, before, caretBefore, before.length - caretBefore)) return SingleEdit.None
        return SingleEdit.Insert
    }
    if (after.length == before.length - 1) {
        if (caretBefore < 1 || caretAfter != caretBefore - 1) return SingleEdit.None
        if (!after.regionMatches(0, before, 0, caretAfter)) return SingleEdit.None
        if (!after.regionMatches(caretAfter, before, caretBefore, before.length - caretBefore)) return SingleEdit.None
        return SingleEdit.Delete
    }
    return SingleEdit.None
}
