package com.westly.neribovault.feature.church

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.core.util.startOfDayMillis
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.data.repository.ChurchRepository
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
enum class ChurchSaveStatus { Idle, Saving, Saved }

/** What the editor shows besides the text fields, which the screen edits directly. */
data class ChurchEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val type: String = TYPE_SERMON,
    val recordDate: Long = 0L,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val updatedAt: Long? = null,
    val saveStatus: ChurchSaveStatus = ChurchSaveStatus.Idle,
)

private data class Draft(
    val title: String = "",
    val speaker: String = "",
    val church: String = "",
    val scripture: String = "",
    val summary: String = "",
    val notes: String = "",
)

/**
 * Loads one record (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new record is only created in the database once it has something in it, and a new record
 * that ends up completely empty is discarded.
 */
class ChurchRecordEditorViewModel(
    private val recordId: String,
    private val repository: ChurchRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = recordId == ChurchRoutes.NEW_RECORD_ID

    private val _state = MutableStateFlow(
        ChurchEditorUiState(
            isLoaded = isNew,
            recordDate = startOfDayMillis(System.currentTimeMillis()),
        ),
    )
    val state: StateFlow<ChurchEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(Draft())

    /** The latest text of each field, even before it has been saved. */
    val currentTitle: String get() = draft.value.title
    val currentSpeaker: String get() = draft.value.speaker
    val currentChurch: String get() = draft.value.church
    val currentScripture: String get() = draft.value.scripture
    val currentSummary: String get() = draft.value.summary
    val currentNotes: String get() = draft.value.notes

    private var currentId: String? = if (isNew) null else recordId
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
            val record = repository.getById(recordId)
            if (record == null || record.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = record.createdAt
            draft.value = Draft(
                title = record.title,
                speaker = record.speaker,
                church = record.church,
                scripture = record.scriptureRefs,
                summary = record.summary,
                notes = record.notes,
            )
            _state.update {
                it.copy(
                    isLoaded = true,
                    type = record.type,
                    recordDate = record.recordDate,
                    tags = record.tags,
                    isPinned = record.isPinned,
                    updatedAt = record.updatedAt,
                )
            }
        }
    }

    fun onTitleChange(value: String) = change(value, draft.value.title) { draft.update { d -> d.copy(title = value) } }

    fun onSpeakerChange(value: String) = change(value, draft.value.speaker) { draft.update { d -> d.copy(speaker = value) } }

    fun onChurchChange(value: String) = change(value, draft.value.church) { draft.update { d -> d.copy(church = value) } }

    fun onScriptureChange(value: String) = change(value, draft.value.scripture) { draft.update { d -> d.copy(scripture = value) } }

    fun onSummaryChange(value: String) = change(value, draft.value.summary) { draft.update { d -> d.copy(summary = value) } }

    fun onNotesChange(value: String) = change(value, draft.value.notes) { draft.update { d -> d.copy(notes = value) } }

    private inline fun change(value: String, old: String, block: () -> Unit) {
        if (value == old) return
        block()
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setType(type: String) {
        if (type == _state.value.type) return
        _state.update { it.copy(type = type) }
        scheduleSave(0L)
    }

    fun setDate(startOfDay: Long) {
        if (startOfDay == _state.value.recordDate) return
        _state.update { it.copy(recordDate = startOfDay) }
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

    fun togglePinned() {
        _state.update { it.copy(isPinned = !it.isPinned) }
        scheduleSave(0L)
    }

    /** The record as plain text for copying or sharing, using the latest unsaved text. */
    fun plainText(): String {
        val text = draft.value
        val meta = _state.value
        return composeChurchText(
            title = text.title,
            type = meta.type,
            recordDate = meta.recordDate,
            speaker = text.speaker,
            church = text.church,
            scriptureRefs = text.scripture,
            summary = text.summary,
            notes = text.notes,
        )
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the record (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteRecord(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == ChurchSaveStatus.Saving) it else it.copy(saveStatus = ChurchSaveStatus.Saving)
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
        val isEmpty = text.title.isBlank() &&
            text.speaker.isBlank() &&
            text.church.isBlank() &&
            text.scripture.isBlank() &&
            text.summary.isBlank() &&
            text.notes.isBlank() &&
            meta.tags.isEmpty()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = ChurchSaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            ChurchRecordEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                type = meta.type,
                title = text.title,
                speaker = text.speaker,
                church = text.church,
                recordDate = meta.recordDate,
                scriptureRefs = text.scripture,
                summary = text.summary,
                notes = text.notes,
                tags = meta.tags,
                isPinned = meta.isPinned,
            ),
        )
        _state.update {
            it.copy(
                saveStatus = if (dirty) ChurchSaveStatus.Saving else ChurchSaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
