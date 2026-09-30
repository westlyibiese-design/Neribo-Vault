package com.westly.neribovault.feature.writers.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.StoryNoteEntity
import com.westly.neribovault.data.repository.StoryNotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val NEW_ID = "new"
private const val SAVE_DEBOUNCE_MS = 600L

/**
 * Hands the id of a note deleted inside the editor back to the Notes tab, which shows the
 * "Moved to Recently deleted" snackbar with Undo when it comes back on screen.
 */
internal object NoteUndoBus {
    val deletedId = MutableStateFlow<String?>(null)
}

/** What the Notes tab draws. */
data class StoryNotesTabUiState(
    val filter: NoteFilter = NoteFilter.All,
    /** The notes that match [filter], newest first. */
    val notes: List<StoryNoteEntity> = emptyList(),
    /** How many notes the story has in total, whatever the filter. */
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
)

/** State and actions for the Notes tab of one story. */
class StoryNotesTabViewModel(
    storyId: String,
    private val repository: StoryNotesRepository,
) : ViewModel() {

    private val filterFlow = MutableStateFlow(NoteFilter.All)

    val state: StateFlow<StoryNotesTabUiState> = combine(
        repository.observeForStory(storyId),
        filterFlow,
    ) { all, filter ->
        val category = filter.category
        val visible = if (category == null) all else all.filter { it.category == category }
        StoryNotesTabUiState(
            filter = filter,
            notes = visible,
            totalCount = all.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryNotesTabUiState())

    fun selectFilter(filter: NoteFilter) {
        filterFlow.value = filter
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class NoteSaveStatus { Idle, Saving, Saved }

/** What the note editor shows besides the two text fields, which the screen edits directly. */
data class StoryNoteEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val category: String = NoteCategories.DEFAULT,
    val saveStatus: NoteSaveStatus = NoteSaveStatus.Idle,
)

/**
 * Loads one note (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new note is only created once it has something in it, and a new note that ends up
 * completely empty is discarded.
 */
class StoryNoteEditorViewModel(
    private val storyId: String,
    noteId: String,
    private val repository: StoryNotesRepository,
) : ViewModel() {

    private val targetId: String = noteId

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = noteId == NEW_ID

    private val _state = MutableStateFlow(StoryNoteEditorUiState(isLoaded = isNew))
    val state: StateFlow<StoryNoteEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(NoteDraft())

    /** The latest draft, even before it has been saved. */
    internal val currentDraft: NoteDraft get() = draft.value

    @Volatile
    private var currentId: String? = if (isNew) null else noteId
    private var createdAt: Long = 0L

    @Volatile
    private var dirty = false

    @Volatile
    private var deleted = false

    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    // Writes must finish even when the screen is already gone and viewModelScope is cancelled,
    // so they run in their own scope. It only ever holds short database writes.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val note = repository.getById(targetId)
            if (note == null || note.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = note.createdAt
            draft.value = NoteDraft(title = note.title, body = note.body, category = note.category)
            _state.update { it.copy(isLoaded = true, category = note.category) }
        }
    }

    private fun edit(change: (NoteDraft) -> NoteDraft) {
        val next = change(draft.value)
        if (next == draft.value) return
        draft.value = next
        scheduleSave()
    }

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onBodyChange(value: String) = edit { it.copy(body = value) }

    fun onCategoryChange(category: String) {
        edit { it.copy(category = category) }
        _state.update { it.copy(category = category) }
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the note (saving any pending text first so Undo brings it back intact),
     * tells the Notes tab to offer Undo, then calls [onDone].
     */
    fun delete(onDone: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val id = currentId
                    if (id != null) {
                        repository.softDelete(id)
                        NoteUndoBus.deletedId.value = id
                    }
                    deleted = true
                }
            }
            onDone()
        }
    }

    private fun scheduleSave() {
        dirty = true
        _state.update {
            if (it.saveStatus == NoteSaveStatus.Saving) it else it.copy(saveStatus = NoteSaveStatus.Saving)
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        withContext(NonCancellable) {
            saveMutex.withLock { persistLocked() }
        }
    }

    /** Must be called with [saveMutex] held. Writes the latest draft if it changed. */
    private suspend fun persistLocked() {
        if (deleted || !dirty) return
        dirty = false
        val text = draft.value
        val existingId = currentId
        if (isNew && text.isBlank) {
            // Nothing worth keeping: forget a note that was saved earlier and emptied since.
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = NoteSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id: String
        if (existingId != null) {
            id = existingId
        } else {
            id = newId()
            createdAt = now
            currentId = id
        }
        repository.upsert(
            StoryNoteEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                storyId = storyId,
                title = text.title.trim(),
                body = text.body,
                category = text.category,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) NoteSaveStatus.Saving else NoteSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
