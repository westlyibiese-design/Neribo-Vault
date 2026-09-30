package com.westly.neribovault.feature.writers.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.StoryCharacterEntity
import com.westly.neribovault.data.repository.StoryCharactersRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val NEW_ID = "new"
private const val SAVE_DEBOUNCE_MS = 600L

/**
 * Hands the id of a character deleted inside the editor back to the Characters tab, which
 * shows the "Moved to Recently deleted" snackbar with Undo when it comes back on screen.
 */
internal object CharacterUndoBus {
    val deletedId = MutableStateFlow<String?>(null)
}

/** What the Characters tab draws. */
data class CharactersTabUiState(
    val characters: List<StoryCharacterEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Characters tab of one story. */
class CharactersTabViewModel(
    storyId: String,
    private val repository: StoryCharactersRepository,
) : ViewModel() {

    val state: StateFlow<CharactersTabUiState> = repository.observeForStory(storyId)
        .map { list -> CharactersTabUiState(characters = list, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CharactersTabUiState())

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class CharacterSaveStatus { Idle, Saving, Saved }

/** What the character editor shows besides the three text fields, which the screen edits directly. */
data class CharacterEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val role: String = CharacterRoles.DEFAULT,
    val traits: List<String> = emptyList(),
    val saveStatus: CharacterSaveStatus = CharacterSaveStatus.Idle,
)

/**
 * Loads one character (or prepares a new one), debounces autosave by 600ms and flushes on
 * demand. A new character is only created once it has something in it, and a new character
 * that ends up completely empty is discarded.
 */
class CharacterEditorViewModel(
    private val storyId: String,
    characterId: String,
    private val repository: StoryCharactersRepository,
) : ViewModel() {

    private val targetId: String = characterId

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = characterId == NEW_ID

    private val _state = MutableStateFlow(CharacterEditorUiState(isLoaded = isNew))
    val state: StateFlow<CharacterEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(CharacterDraft())

    /** The latest draft, even before it has been saved. */
    internal val currentDraft: CharacterDraft get() = draft.value

    @Volatile
    private var currentId: String? = if (isNew) null else characterId
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
            val character = repository.getById(targetId)
            if (character == null || character.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = character.createdAt
            draft.value = CharacterDraft(
                name = character.name,
                role = character.role,
                description = character.description,
                traits = character.traits,
                backstory = character.backstory,
            )
            _state.update {
                it.copy(isLoaded = true, role = character.role, traits = character.traits)
            }
        }
    }

    private fun edit(change: (CharacterDraft) -> CharacterDraft) {
        val next = change(draft.value)
        if (next == draft.value) return
        draft.value = next
        scheduleSave()
    }

    fun onNameChange(value: String) = edit { it.copy(name = value) }

    fun onDescriptionChange(value: String) = edit { it.copy(description = value) }

    fun onBackstoryChange(value: String) = edit { it.copy(backstory = value) }

    fun onRoleChange(role: String) {
        edit { it.copy(role = role) }
        _state.update { it.copy(role = role) }
    }

    /** Adds a trait. Returns false when it is blank, a duplicate, or the limit is reached. */
    fun addTrait(raw: String): Boolean {
        val trait = raw.trim().take(MAX_TRAIT_LENGTH)
        val current = draft.value.traits
        if (trait.isEmpty() || current.size >= MAX_TRAITS) return false
        if (current.any { it.equals(trait, ignoreCase = true) }) return false
        setTraits(current + trait)
        return true
    }

    fun removeTrait(trait: String) {
        setTraits(draft.value.traits.filterNot { it == trait })
    }

    private fun setTraits(traits: List<String>) {
        edit { it.copy(traits = traits) }
        _state.update { it.copy(traits = traits) }
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the character (saving any pending text first so Undo brings it back intact),
     * tells the Characters tab to offer Undo, then calls [onDone].
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
                        CharacterUndoBus.deletedId.value = id
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
            if (it.saveStatus == CharacterSaveStatus.Saving) it else it.copy(saveStatus = CharacterSaveStatus.Saving)
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
            // Nothing worth keeping: forget a character that was saved earlier and emptied since.
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = CharacterSaveStatus.Idle) }
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
            StoryCharacterEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                storyId = storyId,
                name = text.name.trim(),
                role = text.role,
                description = text.description.trim(),
                traits = text.traits,
                backstory = text.backstory,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) CharacterSaveStatus.Saving else CharacterSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
