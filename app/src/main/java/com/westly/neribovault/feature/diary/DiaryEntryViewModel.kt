package com.westly.neribovault.feature.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.core.util.startOfDayMillis
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.data.repository.DiaryRepository
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

/** The little "Saved" / "Saving…" label in the entry screen's top bar. */
enum class DiarySaveStatus { Idle, Saving, Saved }

/** What the entry screen shows besides the two text fields, which the screen edits directly. */
data class DiaryEntryUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val entryDate: Long = 0L,
    val mood: String? = null,
    val tags: List<String> = emptyList(),
    val updatedAt: Long? = null,
    val saveStatus: DiarySaveStatus = DiarySaveStatus.Idle,
)

private data class Draft(val title: String = "", val body: String = "")

/**
 * Loads one diary entry (or prepares a new one), debounces autosave by 600ms and flushes on
 * demand. A new entry is only created in the database once it has something in it, and a new
 * entry that ends up completely empty is discarded.
 *
 * [entryId] is a real id, `new`, or `new-<startOfDayMillis>` for a new entry on a chosen day.
 */
class DiaryEntryViewModel(
    private val entryId: String,
    private val repository: DiaryRepository,
) : ViewModel() {

    /** True when this screen was opened to create a new entry. */
    val isNew: Boolean = entryId.startsWith(DiaryRoutes.NEW_ENTRY_ID)

    private val initialDate: Long = if (isNew) {
        entryId.removePrefix("${DiaryRoutes.NEW_ENTRY_ID}-").toLongOrNull()
            ?.let { startOfDayMillis(it) }
            ?: startOfDayMillis(System.currentTimeMillis())
    } else {
        0L
    }

    private val _state = MutableStateFlow(
        DiaryEntryUiState(isLoaded = isNew, entryDate = initialDate),
    )
    val state: StateFlow<DiaryEntryUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(Draft())

    /** The latest title, even before it has been saved. */
    val currentTitle: String get() = draft.value.title

    /** The latest body, even before it has been saved. */
    val currentBody: String get() = draft.value.body

    private var currentId: String? = if (isNew) null else entryId
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
            val entry = repository.getById(entryId)
            if (entry == null || entry.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = entry.createdAt
            draft.value = Draft(title = entry.title, body = entry.body)
            _state.update {
                it.copy(
                    isLoaded = true,
                    entryDate = entry.entryDate,
                    mood = entry.mood,
                    tags = entry.tags,
                    updatedAt = entry.updatedAt,
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

    /** Moves the entry to another day. [dateMillis] is normalised to the start of that day. */
    fun setDate(dateMillis: Long) {
        val day = startOfDayMillis(dateMillis)
        if (day == _state.value.entryDate) return
        _state.update { it.copy(entryDate = day) }
        scheduleSave(0L)
    }

    /** Sets the mood (a [DiaryMood] key), or clears it with null. */
    fun setMood(key: String?) {
        if (key == _state.value.mood) return
        _state.update { it.copy(mood = key) }
        scheduleSave(0L)
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

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the entry (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteEntry(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == DiarySaveStatus.Saving) it else it.copy(saveStatus = DiarySaveStatus.Saving)
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
        val isEmpty = text.title.isBlank() && text.body.isBlank() &&
            meta.tags.isEmpty() && meta.mood == null
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = DiarySaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            DiaryEntryEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                entryDate = meta.entryDate,
                title = text.title,
                body = text.body,
                mood = meta.mood,
                tags = meta.tags,
            ),
        )
        _state.update {
            it.copy(
                saveStatus = if (dirty) DiarySaveStatus.Saving else DiarySaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
