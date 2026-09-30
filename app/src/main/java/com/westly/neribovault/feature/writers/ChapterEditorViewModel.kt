package com.westly.neribovault.feature.writers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.countWords
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.data.repository.StoriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L

/** The little "Saved" / "Saving…" label in the chapter editor's top bar. */
enum class ChapterSaveStatus { Idle, Saving, Saved }

/** What the chapter editor shows besides the two text fields, which the screen edits directly. */
data class ChapterEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    /** 1-based position of this chapter in the story. */
    val chapterNumber: Int = 1,
    val previousId: String? = null,
    val nextId: String? = null,
    val saveStatus: ChapterSaveStatus = ChapterSaveStatus.Idle,
)

private data class ChapterDraft(val title: String = "", val body: String = "")

/**
 * Loads one chapter (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new chapter is only created in the database once it has something in it, and a new chapter
 * that ends up completely empty is discarded. Every save recounts the words.
 */
class ChapterEditorViewModel(
    private val storyId: String,
    private val chapterId: String,
    private val repository: StoriesRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = chapterId == WritersRoutes.NEW_ID

    private val _state = MutableStateFlow(ChapterEditorUiState(isLoaded = isNew))
    val state: StateFlow<ChapterEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(ChapterDraft())

    /** The latest title, even before it has been saved. */
    val currentTitle: String get() = draft.value.title

    /** The latest body, even before it has been saved. */
    val currentBody: String get() = draft.value.body

    @Volatile
    private var currentId: String? = if (isNew) null else chapterId
    private var createdAt: Long = 0L
    private var sortOrder: Int = 0

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
        observeNeighbours()
    }

    private fun load() {
        viewModelScope.launch {
            val chapter = repository.getChapterById(chapterId)
            if (chapter == null || chapter.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = chapter.createdAt
            sortOrder = chapter.sortOrder
            draft.value = ChapterDraft(title = chapter.title, body = chapter.body)
            _state.update { it.copy(isLoaded = true) }
        }
    }

    /** Keeps the chapter number and the Previous / Next targets in step with the story. */
    private fun observeNeighbours() {
        viewModelScope.launch {
            repository.observeChapters(storyId).collect { chapters ->
                val id = currentId
                val index = if (id == null) -1 else chapters.indexOfFirst { it.id == id }
                val number = if (index >= 0) index + 1 else chapters.size + 1
                val previous = when {
                    index > 0 -> chapters[index - 1].id
                    index < 0 -> chapters.lastOrNull()?.id
                    else -> null
                }
                val next = if (index in 0 until chapters.lastIndex) chapters[index + 1].id else null
                _state.update {
                    it.copy(chapterNumber = number, previousId = previous, nextId = next)
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave()
    }

    fun onBodyChange(value: String) {
        if (value == draft.value.body) return
        draft.update { it.copy(body = value) }
        scheduleSave()
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the chapter (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteChapter(onDone: (deletedId: String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val id = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val target = currentId
                    if (target != null) repository.softDeleteChapter(target)
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
            if (it.saveStatus == ChapterSaveStatus.Saving) it else it.copy(saveStatus = ChapterSaveStatus.Saving)
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
        val isEmpty = text.title.isBlank() && text.body.isBlank()
        if (isEmpty && isNew) {
            // Nothing worth keeping: forget a chapter that was saved earlier and emptied since.
            if (existingId != null) {
                repository.softDeleteChapter(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = ChapterSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id: String
        if (existingId != null) {
            id = existingId
        } else {
            id = newId()
            createdAt = now
            // After the last chapter that is still in the story, even if earlier ones were deleted.
            sortOrder = (repository.observeChapters(storyId).first().maxOfOrNull { it.sortOrder } ?: -1) + 1
            currentId = id
        }
        repository.upsertChapter(
            StoryChapterEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                storyId = storyId,
                title = text.title.trim(),
                body = text.body,
                sortOrder = sortOrder,
                wordCount = countWords(text.body),
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) ChapterSaveStatus.Saving else ChapterSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
