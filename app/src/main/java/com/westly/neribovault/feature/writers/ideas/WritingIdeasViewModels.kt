package com.westly.neribovault.feature.writers.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.WritingIdeaEntity
import com.westly.neribovault.data.repository.WritingIdeasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val NEW_ID = "new"
private const val SAVE_DEBOUNCE_MS = 600L

/** Everything the ideas list draws. */
data class WritingIdeasUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val filter: IdeaFilter = IdeaFilter.All,
    val ideas: List<WritingIdeaEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || filter != IdeaFilter.All
}

private data class IdeaControls(
    val query: String,
    val isSearchOpen: Boolean,
    val filter: IdeaFilter,
)

/** State and actions for the writing ideas list and its Recently deleted section. */
@OptIn(ExperimentalCoroutinesApi::class)
class WritingIdeasViewModel(private val repository: WritingIdeasRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val filterFlow = MutableStateFlow(IdeaFilter.All)

    private val controls: Flow<IdeaControls> = combine(
        queryFlow,
        searchOpenFlow,
        filterFlow,
    ) { query, open, filter ->
        IdeaControls(query = query, isSearchOpen = open, filter = filter)
    }

    private val ideas: Flow<List<WritingIdeaEntity>> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) repository.observeAll() else repository.search(query)
        }

    val state: StateFlow<WritingIdeasUiState> = combine(controls, ideas) { c, list ->
        val status = c.filter.status
        val visible = if (status == null) list else list.filter { it.status == status }
        WritingIdeasUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            filter = c.filter,
            ideas = visible,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WritingIdeasUiState())

    /** The deleted ideas, most recently deleted first. */
    val trash: StateFlow<List<WritingIdeaEntity>> = repository.observeTrashed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    fun selectFilter(value: IdeaFilter) {
        filterFlow.value = value
    }

    fun setStatus(id: String, status: String) {
        viewModelScope.launch { repository.setStatus(id, status) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every idea that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { idea ->
                repository.deletePermanently(idea.id)
            }
        }
    }
}

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class IdeaSaveStatus { Idle, Saving, Saved }

/** What the idea editor shows besides the two text fields, which the screen edits directly. */
data class IdeaEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val genre: String? = null,
    val status: String = IdeaOptions.DEFAULT_STATUS,
    val saveStatus: IdeaSaveStatus = IdeaSaveStatus.Idle,
)

/**
 * Loads one idea (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new idea is only created once it has something in it, and a new idea that ends up
 * completely empty is discarded.
 */
class IdeaEditorViewModel(
    ideaId: String,
    private val repository: WritingIdeasRepository,
) : ViewModel() {

    private val targetId: String = ideaId

    /** True when this editor was opened to create a new idea. */
    val isNew: Boolean = ideaId == NEW_ID

    private val _state = MutableStateFlow(IdeaEditorUiState(isLoaded = isNew))
    val state: StateFlow<IdeaEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(IdeaDraft())

    /** The latest draft, even before it has been saved. */
    internal val currentDraft: IdeaDraft get() = draft.value

    @Volatile
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
            val idea = repository.getById(targetId)
            if (idea == null || idea.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = idea.createdAt
            draft.value = IdeaDraft(
                title = idea.title,
                body = idea.body,
                genre = idea.genre,
                status = idea.status,
            )
            _state.update { it.copy(isLoaded = true, genre = idea.genre, status = idea.status) }
        }
    }

    private fun edit(change: (IdeaDraft) -> IdeaDraft) {
        val next = change(draft.value)
        if (next == draft.value) return
        draft.value = next
        scheduleSave()
    }

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onBodyChange(value: String) = edit { it.copy(body = value) }

    /** Picks a genre; picking the selected genre again clears it. */
    fun onGenreToggle(genre: String) {
        val next = if (draft.value.genre == genre) null else genre
        edit { it.copy(genre = next) }
        _state.update { it.copy(genre = next) }
    }

    fun onStatusChange(status: String) {
        edit { it.copy(status = status) }
        _state.update { it.copy(status = status) }
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
    fun delete(onDone: (deletedId: String?) -> Unit) {
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

    private fun scheduleSave() {
        dirty = true
        _state.update {
            if (it.saveStatus == IdeaSaveStatus.Saving) it else it.copy(saveStatus = IdeaSaveStatus.Saving)
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
            // Nothing worth keeping: forget an idea that was saved earlier and emptied since.
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = IdeaSaveStatus.Idle) }
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
            WritingIdeaEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                title = text.title.trim(),
                body = text.body,
                genre = text.genre,
                status = text.status,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) IdeaSaveStatus.Saving else IdeaSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
