package com.westly.neribovault.feature.memories

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.core.util.startOfDayMillis
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.data.repository.MemoriesRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class MemorySaveStatus { Idle, Saving, Saved }

/** What the editor shows besides the text fields, which the screen edits directly. */
data class MemoryEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val memoryDate: Long = 0L,
    val people: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    /** True while picked photos are being copied into private storage. */
    val isImporting: Boolean = false,
    val updatedAt: Long? = null,
    val saveStatus: MemorySaveStatus = MemorySaveStatus.Idle,
)

private data class Draft(
    val title: String = "",
    val description: String = "",
    val location: String = "",
)

/**
 * Loads one memory (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new memory is only created in the database once it has something in it, and a new memory
 * that ends up completely empty is discarded.
 *
 * Photos are copied into private storage as soon as they are picked. A photo the owner removes
 * is deleted from disk only after the memory has been saved without it.
 */
class MemoryEditorViewModel(
    private val memoryId: String,
    private val repository: MemoriesRepository,
    private val photoStore: MemoryPhotoStore,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = memoryId == MemoriesRoutes.NEW_MEMORY_ID

    private val _state = MutableStateFlow(
        MemoryEditorUiState(
            isLoaded = isNew,
            memoryDate = startOfDayMillis(System.currentTimeMillis()),
        ),
    )
    val state: StateFlow<MemoryEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(Draft())

    /** The latest text of each field, even before it has been saved. */
    val currentTitle: String get() = draft.value.title
    val currentDescription: String get() = draft.value.description
    val currentLocation: String get() = draft.value.location

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Gentle one-off messages (for example the photo limit), each delivered once. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var currentId: String? = if (isNew) null else memoryId
    private var createdAt: Long = 0L

    @Volatile
    private var dirty = false

    @Volatile
    private var deleted = false

    private val saveMutex = Mutex()

    @Volatile
    private var saveJob: Job? = null

    // Photos the owner removed that may still be on disk. Their files are deleted once a save
    // that no longer lists them has gone through.
    private val removedPending: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    // Photo copies and database writes must finish even when the screen is already gone and
    // viewModelScope is cancelled, so they run in their own scope.
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val memory = repository.getById(memoryId)
            if (memory == null || memory.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = memory.createdAt
            draft.value = Draft(
                title = memory.title,
                description = memory.description,
                location = memory.location,
            )
            _state.update {
                it.copy(
                    isLoaded = true,
                    memoryDate = memory.memoryDate,
                    people = memory.people,
                    tags = memory.tags,
                    photos = memory.photoUris,
                    updatedAt = memory.updatedAt,
                )
            }
        }
    }

    fun onTitleChange(value: String) = change(value, draft.value.title) {
        draft.update { d -> d.copy(title = value) }
    }

    fun onDescriptionChange(value: String) = change(value, draft.value.description) {
        draft.update { d -> d.copy(description = value) }
    }

    fun onLocationChange(value: String) = change(value, draft.value.location) {
        draft.update { d -> d.copy(location = value) }
    }

    private inline fun change(value: String, old: String, block: () -> Unit) {
        if (value == old) return
        block()
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setDate(startOfDay: Long) {
        if (startOfDay == _state.value.memoryDate) return
        _state.update { it.copy(memoryDate = startOfDay) }
        scheduleSave(0L)
    }

    fun addPerson(raw: String) {
        val name = normalizePerson(raw)
        val people = _state.value.people
        if (name.isEmpty() || people.size >= MAX_PEOPLE) return
        if (people.any { it.equals(name, ignoreCase = true) }) return
        _state.update { it.copy(people = it.people + name) }
        scheduleSave(0L)
    }

    fun removePerson(name: String) {
        if (name !in _state.value.people) return
        _state.update { it.copy(people = it.people - name) }
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

    /**
     * Copies the picked photos into private storage (never blocking the screen) and adds them to
     * the memory. At most [MAX_PHOTOS] fit; extra picks are left out with a gentle message.
     */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.isImporting) return
        val room = MAX_PHOTOS - _state.value.photos.size
        if (room <= 0) {
            messageChannel.trySend("A memory holds up to $MAX_PHOTOS photos.")
            return
        }
        val chosen = uris.take(room)
        val skipped = uris.size - chosen.size
        _state.update { it.copy(isImporting = true) }
        workScope.launch {
            val imported = mutableListOf<String>()
            var failed = 0
            try {
                for (uri in chosen) {
                    val path = photoStore.importPhoto(uri)
                    if (path != null) imported.add(path) else failed++
                }
            } finally {
                _state.update { it.copy(photos = it.photos + imported, isImporting = false) }
            }
            if (imported.isNotEmpty()) scheduleSave(0L)
            when {
                skipped > 0 -> messageChannel.trySend(
                    "A memory holds up to $MAX_PHOTOS photos, so $skipped ${if (skipped == 1) "photo was" else "photos were"} left out.",
                )
                failed > 0 -> messageChannel.trySend(
                    if (failed == 1) "One photo could not be added." else "$failed photos could not be added.",
                )
            }
            return@launch
        }
    }

    /** Takes a photo out of the memory. Its file is deleted once the memory is saved without it. */
    fun removePhoto(path: String) {
        if (path !in _state.value.photos) return
        removedPending.add(path)
        _state.update { it.copy(photos = it.photos - path) }
        scheduleSave(0L)
    }

    /** The memory as plain text for copying or sharing, using the latest unsaved text. */
    fun plainText(): String {
        val text = draft.value
        val meta = _state.value
        return composeMemoryText(
            title = text.title,
            description = text.description,
            memoryDate = meta.memoryDate,
            location = text.location,
            people = meta.people,
            tags = meta.tags,
        )
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) workScope.launch { persist() }
    }

    /**
     * Soft-deletes the memory (saving any pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet. Photo files stay on disk
     * so Restore keeps them.
     */
    fun deleteMemory(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == MemorySaveStatus.Saving) it else it.copy(saveStatus = MemorySaveStatus.Saving)
        }
        saveJob?.cancel()
        saveJob = workScope.launch {
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
            text.description.isBlank() &&
            text.location.isBlank() &&
            meta.people.isEmpty() &&
            meta.tags.isEmpty() &&
            meta.photos.isEmpty()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            deleteRemovedFiles(keep = emptySet())
            _state.update { it.copy(saveStatus = MemorySaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            MemoryEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                title = text.title,
                description = text.description,
                memoryDate = meta.memoryDate,
                location = text.location,
                people = meta.people,
                photoUris = meta.photos,
                tags = meta.tags,
            ),
        )
        // Only now that the memory is saved without them are the removed photos really gone.
        deleteRemovedFiles(keep = meta.photos.toSet())
        _state.update {
            it.copy(
                saveStatus = if (dirty) MemorySaveStatus.Saving else MemorySaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    private suspend fun deleteRemovedFiles(keep: Set<String>) {
        val doomed = removedPending.filter { it !in keep }
        if (doomed.isEmpty()) return
        removedPending.removeAll(doomed.toSet())
        photoStore.deleteFilesFor(doomed)
    }

    override fun onCleared() {
        // Anything still waiting for its debounce is saved right away, in the work scope.
        saveJob?.cancel()
        if (dirty && !deleted) workScope.launch { persist() }
        super.onCleared()
    }
}
