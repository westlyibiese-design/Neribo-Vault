package com.westly.neribovault.feature.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.data.repository.IdeasRepository
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
enum class IdeaSaveStatus { Idle, Saving, Saved }

/** What the editor shows besides the two text fields, which the screen edits directly. */
data class IdeaEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val category: String = "general",
    val status: String = "new",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val updatedAt: Long? = null,
    val saveStatus: IdeaSaveStatus = IdeaSaveStatus.Idle,
)

private data class IdeaDraft(val title: String = "", val description: String = "")

/**
 * Loads one idea (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new idea is only created in the database once it has something in it, and a new idea that
 * ends up completely empty is discarded.
 */
class IdeaEditorViewModel(
    private val ideaId: String,
    private val repository: IdeasRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = ideaId == IdeasRoutes.NEW_IDEA_ID

    private val _state = MutableStateFlow(IdeaEditorUiState(isLoaded = isNew))
    val state: StateFlow<IdeaEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(IdeaDraft())

    /** The latest title, even before it has been saved. */
    val currentTitle: String get() = draft.value.title

    /** The latest description, even before it has been saved. */
    val currentDescription: String get() = draft.value.description

    private var currentId: String? = if (isNew) null else ideaId
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
            val idea = repository.getById(ideaId)
            if (idea == null || idea.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = idea.createdAt
            draft.value = IdeaDraft(title = idea.title, description = idea.description)
            _state.update {
                it.copy(
                    isLoaded = true,
                    category = idea.category,
                    status = idea.status,
                    tags = idea.tags,
                    isPinned = idea.isPinned,
                    updatedAt = idea.updatedAt,
                )
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onDescriptionChange(value: String) {
        if (value == draft.value.description) return
        draft.update { it.copy(description = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setCategory(value: String) {
        if (value == _state.value.category) return
        _state.update { it.copy(category = value) }
        scheduleSave(0L)
    }

    fun setStatus(value: String) {
        if (value == _state.value.status) return
        _state.update { it.copy(status = value) }
        scheduleSave(0L)
    }

    fun addTag(raw: String) {
        val tag = normalizeIdeaTag(raw)
        val tags = _state.value.tags
        if (tag.isEmpty() || tag in tags || tags.size >= MAX_IDEA_TAGS) return
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

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the idea (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteIdea(onDone: (deletedId: String?) -> Unit) {
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
        _state.update {
            if (it.saveStatus == IdeaSaveStatus.Saving) it else it.copy(saveStatus = IdeaSaveStatus.Saving)
        }
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
        val isEmpty = text.title.isBlank() && text.description.isBlank() && meta.tags.isEmpty()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = IdeaSaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            IdeaEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                title = text.title,
                description = text.description,
                category = meta.category,
                status = meta.status,
                tags = meta.tags,
                isPinned = meta.isPinned,
            ),
        )
        _state.update {
            it.copy(
                saveStatus = if (dirty) IdeaSaveStatus.Saving else IdeaSaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
