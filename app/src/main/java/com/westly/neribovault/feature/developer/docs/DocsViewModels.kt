package com.westly.neribovault.feature.developer.docs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.countWords
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ProjectDocumentEntity
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.PromptEntity
import com.westly.neribovault.data.repository.ProjectDocumentsRepository
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.data.repository.PromptsRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L
private const val NOTICE_MAX_AGE_MS = 15_000L

/**
 * Hands a just-deleted document or prompt from its editor back to the screen that lists it (the
 * Docs tab or the Prompts library), which shows the Undo snackbar. The editors have no navigation
 * controller of their own, so this small in-memory mailbox does the job of the `deletedId`
 * saved-state key.
 */
internal object DocsDeleteNotice {
    enum class Kind { Document, Prompt }

    /** An item that was moved to Recently deleted from its editor. */
    data class Deleted(val kind: Kind, val projectId: String?, val id: String, val at: Long)

    private val mailbox = MutableStateFlow<Deleted?>(null)

    /** The latest unconsumed delete, or null. */
    val pending: StateFlow<Deleted?> = mailbox.asStateFlow()

    fun post(kind: Kind, projectId: String?, id: String) {
        mailbox.value = Deleted(kind, projectId, id, System.currentTimeMillis())
    }

    fun clear() {
        mailbox.value = null
    }

    /** True while [notice] is recent enough to still be worth an Undo. */
    fun isFresh(notice: Deleted): Boolean =
        System.currentTimeMillis() - notice.at <= NOTICE_MAX_AGE_MS
}

/** The little "Saved" / "Saving…" label in an editor's top bar. */
internal enum class DocSaveStatus { Idle, Saving, Saved }

/** Prompts first by favorite, then newest. */
private fun sortPrompts(prompts: List<PromptEntity>): List<PromptEntity> =
    prompts.sortedWith(
        compareByDescending<PromptEntity> { it.isFavorite }.thenByDescending { it.updatedAt },
    )

// ---------------------------------------------------------------------------------------------
// Docs tab
// ---------------------------------------------------------------------------------------------

/** Everything the Docs tab draws. */
internal data class ProjectDocsUiState(
    val docs: List<ProjectDocumentEntity> = emptyList(),
    val prompts: List<PromptEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Docs tab of one project. */
internal class ProjectDocsViewModel(
    projectId: String,
    private val docsRepository: ProjectDocumentsRepository,
    private val promptsRepository: PromptsRepository,
) : ViewModel() {

    val state: StateFlow<ProjectDocsUiState> = combine(
        docsRepository.observeByProject(projectId),
        promptsRepository.observeByProject(projectId),
    ) { docs, prompts ->
        ProjectDocsUiState(
            docs = docs.sortedByDescending { it.updatedAt },
            prompts = sortPrompts(prompts),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDocsUiState())

    fun deleteDoc(id: String) {
        viewModelScope.launch { docsRepository.softDelete(id) }
    }

    fun restoreDoc(id: String) {
        viewModelScope.launch { docsRepository.restore(id) }
    }

    fun duplicateDoc(doc: ProjectDocumentEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            docsRepository.upsert(
                doc.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = docCopyTitle(doc.title),
                ),
            )
        }
    }

    fun deletePrompt(id: String) {
        viewModelScope.launch { promptsRepository.softDelete(id) }
    }

    fun restorePrompt(id: String) {
        viewModelScope.launch { promptsRepository.restore(id) }
    }

    fun duplicatePrompt(prompt: PromptEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            promptsRepository.upsert(
                prompt.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = docCopyTitle(prompt.title),
                ),
            )
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val latest = promptsRepository.getById(id) ?: return@launch
            if (latest.isDeleted) return@launch
            promptsRepository.upsert(latest.copy(isFavorite = !latest.isFavorite))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Project document editor
// ---------------------------------------------------------------------------------------------

/** What the document editor shows besides its two text fields, which the screen edits directly. */
internal data class ProjectDocEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val kind: String = DOC_KIND_README,
    val wordCount: Int = 0,
    val saveStatus: DocSaveStatus = DocSaveStatus.Idle,
)

private data class DocDraft(val title: String = "", val body: String = "")

/**
 * Loads one project document (or prepares a new one), debounces autosave by 600ms and flushes
 * on demand. A new document is only created once it has some text, and a new document that ends
 * up completely empty is discarded.
 */
internal class ProjectDocEditorViewModel(
    projectId: String,
    private val docId: String,
    private val repository: ProjectDocumentsRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = docId == DOC_NEW_ID

    private val _state = MutableStateFlow(ProjectDocEditorUiState(isLoaded = isNew))
    val state: StateFlow<ProjectDocEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(DocDraft())

    val currentTitle: String get() = draft.value.title
    val currentBody: String get() = draft.value.body

    private var currentId: String? = if (isNew) null else docId
    private var ownerProjectId: String? = projectId.takeUnless { it == DOC_NO_PROJECT }
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
            val doc = repository.getById(docId)
            if (doc == null || doc.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = doc.createdAt
            ownerProjectId = doc.projectId
            draft.value = DocDraft(title = doc.title, body = doc.body)
            _state.update {
                it.copy(isLoaded = true, kind = doc.kind, wordCount = countWords(doc.body))
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
        val words = countWords(value)
        _state.update { if (it.wordCount == words) it else it.copy(wordCount = words) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setKind(value: String) {
        if (value == _state.value.kind) return
        _state.update { it.copy(kind = value) }
        scheduleSave(0L)
    }

    /** The plain text for Copy and Share, built from what is on screen right now. */
    fun plainText(): String = docPlainText(draft.value.title, draft.value.body)

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /** Saves pending text and makes a copy. Reports false when nothing had been saved yet. */
    fun duplicate(onDone: (created: Boolean) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val created = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    duplicateLocked()
                }
            }
            onDone(created)
        }
    }

    private suspend fun duplicateLocked(): Boolean {
        val id = currentId ?: return false
        val source = repository.getById(id) ?: return false
        val now = System.currentTimeMillis()
        repository.upsert(
            source.copy(
                id = newId(),
                createdAt = now,
                updatedAt = now,
                isDeleted = false,
                deletedAt = null,
                title = docCopyTitle(source.title),
            ),
        )
        return true
    }

    /** Soft-deletes the document and reports its id, or null when nothing had been saved yet. */
    fun deleteDoc(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == DocSaveStatus.Saving) it else it.copy(saveStatus = DocSaveStatus.Saving)
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
        val kind = _state.value.kind
        val existingId = currentId
        val isEmpty = text.title.isBlank() && text.body.isBlank()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = DocSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            ProjectDocumentEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                projectId = ownerProjectId,
                title = text.title,
                body = text.body,
                kind = kind,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) DocSaveStatus.Saving else DocSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------------------------
// Prompt editor
// ---------------------------------------------------------------------------------------------

/** What the prompt editor shows besides its two text fields, which the screen edits directly. */
internal data class PromptEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val category: String = PROMPT_CATEGORY_CODING,
    val projectId: String? = null,
    val isFavorite: Boolean = false,
    val variables: List<String> = emptyList(),
    val saveStatus: DocSaveStatus = DocSaveStatus.Idle,
)

private data class PromptDraft(val title: String = "", val body: String = "")

/**
 * Loads one prompt (or prepares a new one, preselecting [initialProjectId] when it is not null),
 * debounces autosave by 600ms and flushes on demand. A new prompt that ends up completely empty
 * is discarded.
 */
internal class PromptEditorViewModel(
    initialProjectId: String?,
    private val promptId: String,
    private val repository: PromptsRepository,
    projectsRepository: ProjectsRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = promptId == DOC_NEW_ID

    private val _state = MutableStateFlow(
        PromptEditorUiState(isLoaded = isNew, projectId = initialProjectId),
    )
    val state: StateFlow<PromptEditorUiState> = _state.asStateFlow()

    /** The projects to choose from, by name. */
    val projects: StateFlow<List<ProjectEntity>> = projectsRepository.observeAll()
        .map { list -> list.sortedBy { it.name.trim().lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val draft = MutableStateFlow(PromptDraft())

    val currentTitle: String get() = draft.value.title
    val currentBody: String get() = draft.value.body

    private var currentId: String? = if (isNew) null else promptId
    private var createdAt: Long = 0L

    @Volatile
    private var dirty = false

    @Volatile
    private var deleted = false

    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val prompt = repository.getById(promptId)
            if (prompt == null || prompt.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = prompt.createdAt
            draft.value = PromptDraft(title = prompt.title, body = prompt.body)
            _state.update {
                it.copy(
                    isLoaded = true,
                    category = prompt.category,
                    projectId = prompt.projectId,
                    isFavorite = prompt.isFavorite,
                    variables = extractVariables(prompt.body),
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
        val found = extractVariables(value)
        _state.update { if (it.variables == found) it else it.copy(variables = found) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setCategory(value: String) {
        if (value == _state.value.category) return
        _state.update { it.copy(category = value) }
        scheduleSave(0L)
    }

    fun setProject(value: String?) {
        if (value == _state.value.projectId) return
        _state.update { it.copy(projectId = value) }
        scheduleSave(0L)
    }

    fun toggleFavorite() {
        _state.update { it.copy(isFavorite = !it.isFavorite) }
        scheduleSave(0L)
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /** Saves pending text and makes a copy. Reports false when nothing had been saved yet. */
    fun duplicate(onDone: (created: Boolean) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val created = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    duplicateLocked()
                }
            }
            onDone(created)
        }
    }

    private suspend fun duplicateLocked(): Boolean {
        val id = currentId ?: return false
        val source = repository.getById(id) ?: return false
        val now = System.currentTimeMillis()
        repository.upsert(
            source.copy(
                id = newId(),
                createdAt = now,
                updatedAt = now,
                isDeleted = false,
                deletedAt = null,
                title = docCopyTitle(source.title),
            ),
        )
        return true
    }

    /** Soft-deletes the prompt and reports its id, or null when nothing had been saved yet. */
    fun deletePrompt(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == DocSaveStatus.Saving) it else it.copy(saveStatus = DocSaveStatus.Saving)
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
        val isEmpty = text.title.isBlank() && text.body.isBlank()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = DocSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            PromptEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                projectId = meta.projectId,
                title = text.title,
                body = text.body,
                category = meta.category,
                isFavorite = meta.isFavorite,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) DocSaveStatus.Saving else DocSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------------------------
// Prompts library
// ---------------------------------------------------------------------------------------------

/** Filter value for "favorites only". */
internal const val FILTER_FAVORITES = "favorites"

/** Filter value for "every prompt". */
internal const val FILTER_ALL = "all"

/** One prompt in the library with the name of its project, if any. */
internal data class PromptListItem(val prompt: PromptEntity, val projectName: String?)

/** Everything the library draws. */
internal data class PromptsLibraryUiState(
    val query: String = "",
    val filter: String = FILTER_ALL,
    val items: List<PromptListItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
)

/** State and actions for the Prompts library. */
internal class PromptsLibraryViewModel(
    private val promptsRepository: PromptsRepository,
    projectsRepository: ProjectsRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(FILTER_ALL)

    val state: StateFlow<PromptsLibraryUiState> = combine(
        promptsRepository.observeAll(),
        projectsRepository.observeAll(),
        queryFlow,
        filterFlow,
    ) { prompts, projects, query, filter ->
        val names = projects.associate { project ->
            project.id to project.name.trim().ifEmpty { "Untitled project" }
        }
        val needle = query.trim().lowercase()
        val visible = prompts
            .filter { prompt ->
                when (filter) {
                    FILTER_FAVORITES -> prompt.isFavorite
                    FILTER_ALL -> true
                    else -> prompt.category == filter
                }
            }
            .filter { prompt ->
                needle.isEmpty() ||
                    prompt.title.lowercase().contains(needle) ||
                    prompt.body.lowercase().contains(needle) ||
                    prompt.category.lowercase().contains(needle) ||
                    promptCategoryLabel(prompt.category).lowercase().contains(needle)
            }
        PromptsLibraryUiState(
            query = query,
            filter = filter,
            items = sortPrompts(visible).map { prompt ->
                PromptListItem(prompt, prompt.projectId?.let { names[it] })
            },
            totalCount = prompts.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PromptsLibraryUiState())

    fun setQuery(value: String) {
        queryFlow.value = value
    }

    fun setFilter(value: String) {
        filterFlow.value = value
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val latest = promptsRepository.getById(id) ?: return@launch
            if (latest.isDeleted) return@launch
            promptsRepository.upsert(latest.copy(isFavorite = !latest.isFavorite))
        }
    }

    fun restore(id: String) {
        viewModelScope.launch { promptsRepository.restore(id) }
    }
}
