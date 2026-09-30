package com.westly.neribovault.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.NoteEntity
import com.westly.neribovault.data.repository.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class SaveStatus { Idle, Saving, Saved }

/** What the editor shows besides the two text fields, which the screen edits directly. */
data class NoteEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val updatedAt: Long? = null,
    val saveStatus: SaveStatus = SaveStatus.Idle,
)

private data class Draft(val title: String = "", val body: String = "")

/**
 * Loads one note (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new note is only created in the database once it has something in it, and a new note that
 * ends up completely empty is discarded.
 */
class NoteEditorViewModel(
    private val noteId: String,
    private val repository: NotesRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = noteId == NotesRoutes.NEW_NOTE_ID

    private val _state = MutableStateFlow(NoteEditorUiState(isLoaded = isNew))
    val state: StateFlow<NoteEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(Draft())

    /** The latest title, even before it has been saved. */
    val currentTitle: String get() = draft.value.title

    /** The latest body, even before it has been saved. */
    val currentBody: String get() = draft.value.body

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
            val note = repository.getById(noteId)
            if (note == null || note.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = note.createdAt
            draft.value = Draft(title = note.title, body = note.body)
            _state.update {
                it.copy(
                    isLoaded = true,
                    tags = note.tags,
                    isPinned = note.isPinned,
                    isArchived = note.isArchived,
                    updatedAt = note.updatedAt,
                )
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onBodyChange(value: String) {
        if (value == draft.value.body) return
        draft.update { it.copy(body = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun addTag(raw: String) {
        val tag = normalizeTag(raw)
        val tags = _state.value.tags
        if (tag.isEmpty() || tag in tags || tags.size >= MAX_TAGS) return
        _state.update { it.copy(tags = it.tags + tag) }
        scheduleSave(0L)
    }

    fun removeTag(tag: String) {
        if (tag !in _state.value.tags) return
        _state.update { it.copy(tags = it.tags - tag) }
        scheduleSave(0L)
    }

    fun togglePinned() {
        _state.update { it.copy(isPinned = !it.isPinned) }
        scheduleSave(0L)
    }

    fun toggleArchived() {
        _state.update { it.copy(isArchived = !it.isArchived) }
        scheduleSave(0L)
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the note (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteNote(onDone: (deletedId: String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val id = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val target = currentId
                    if (target != null) repository.softDelete(target)
                    deleted = true
                    target
                }
            }
            onDone(id)
        }
    }

    private fun scheduleSave(delayMs: Long) {
        dirty = true
        _state.update { if (it.saveStatus == SaveStatus.Saving) it else it.copy(saveStatus = SaveStatus.Saving) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMs)
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
        val meta = _state.value
        val existingId = currentId
        val isEmpty = text.title.isBlank() && text.body.isBlank() && meta.tags.isEmpty()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = SaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            NoteEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                title = text.title,
                body = text.body,
                tags = meta.tags,
                isPinned = meta.isPinned,
                isArchived = meta.isArchived,
            ),
        )
        _state.update {
            it.copy(
                saveStatus = if (dirty) SaveStatus.Saving else SaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
