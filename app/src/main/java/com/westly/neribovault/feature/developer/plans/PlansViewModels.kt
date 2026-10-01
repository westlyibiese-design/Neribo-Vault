package com.westly.neribovault.feature.developer.plans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.FolderPlanEntity
import com.westly.neribovault.data.local.entity.PlanningDocEntity
import com.westly.neribovault.data.repository.FolderPlansRepository
import com.westly.neribovault.data.repository.PlanningDocsRepository
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

private const val SAVE_DEBOUNCE_MS = 600L
private const val NOTICE_MAX_AGE_MS = 15_000L

/**
 * Hands a just-deleted planning doc or folder plan from its editor back to the Plans tab, which
 * shows the Undo snackbar. The editors have no navigation controller of their own, so this small
 * in-memory mailbox does the job of the `deletedId` saved-state key.
 */
internal object PlansDeleteNotice {
    enum class Kind { Planning, Folder }

    /** An item that was moved to Recently deleted from its editor. */
    data class Deleted(val kind: Kind, val projectId: String, val id: String, val at: Long)

    private val mailbox = MutableStateFlow<Deleted?>(null)

    /** The latest unconsumed delete, or null. */
    val pending: StateFlow<Deleted?> = mailbox.asStateFlow()

    fun post(kind: Kind, projectId: String, id: String) {
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
internal enum class PlanSaveStatus { Idle, Saving, Saved }

// ---------------------------------------------------------------------------------------------
// Plans tab
// ---------------------------------------------------------------------------------------------

/** Everything the Plans tab draws. */
internal data class ProjectPlansUiState(
    val docs: List<PlanningDocEntity> = emptyList(),
    val plans: List<FolderPlanEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Plans tab of one project. */
internal class ProjectPlansViewModel(
    projectId: String,
    private val docsRepository: PlanningDocsRepository,
    private val plansRepository: FolderPlansRepository,
) : ViewModel() {

    val state: StateFlow<ProjectPlansUiState> = combine(
        docsRepository.observeByProject(projectId),
        plansRepository.observeByProject(projectId),
    ) { docs, plans ->
        ProjectPlansUiState(
            docs = docs.sortedByDescending { it.updatedAt },
            plans = plans.sortedByDescending { it.updatedAt },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectPlansUiState())

    fun deleteDoc(id: String) {
        viewModelScope.launch { docsRepository.softDelete(id) }
    }

    fun restoreDoc(id: String) {
        viewModelScope.launch { docsRepository.restore(id) }
    }

    fun duplicateDoc(doc: PlanningDocEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            docsRepository.upsert(
                doc.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = copyTitle(doc.title),
                ),
            )
        }
    }

    fun deletePlan(id: String) {
        viewModelScope.launch { plansRepository.softDelete(id) }
    }

    fun restorePlan(id: String) {
        viewModelScope.launch { plansRepository.restore(id) }
    }

    fun duplicatePlan(plan: FolderPlanEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            plansRepository.upsert(
                plan.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = copyTitle(plan.title),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Planning document editor
// ---------------------------------------------------------------------------------------------

/** What the planning doc editor shows besides its two text fields, which the screen edits directly. */
internal data class PlanningDocEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val kind: String = PLAN_KIND_FEATURE,
    val done: Int = 0,
    val total: Int = 0,
    val saveStatus: PlanSaveStatus = PlanSaveStatus.Idle,
)

private data class PlanDraft(val title: String = "", val body: String = "")

/**
 * Loads one planning doc (or prepares a new one), debounces autosave by 600ms and flushes on
 * demand. A new doc is only created once it has some text, and a new doc that ends up completely
 * empty is discarded.
 */
internal class PlanningDocEditorViewModel(
    projectId: String,
    private val docId: String,
    private val repository: PlanningDocsRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = docId == PLAN_NEW_ID

    private val _state = MutableStateFlow(PlanningDocEditorUiState(isLoaded = isNew))
    val state: StateFlow<PlanningDocEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(PlanDraft())

    val currentTitle: String get() = draft.value.title
    val currentBody: String get() = draft.value.body

    private var currentId: String? = if (isNew) null else docId
    private var ownerProjectId: String? = projectId.takeUnless { it == PLAN_NO_PROJECT }
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
            draft.value = PlanDraft(title = doc.title, body = doc.body)
            val progress = checklistProgress(doc.body)
            _state.update {
                it.copy(
                    isLoaded = true,
                    kind = doc.kind,
                    done = progress.done,
                    total = progress.total,
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
        val progress = checklistProgress(value)
        _state.update { it.copy(done = progress.done, total = progress.total) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setKind(value: String) {
        if (value == _state.value.kind) return
        _state.update { it.copy(kind = value) }
        scheduleSave(0L)
    }

    /** The plain text for Copy and Share, built from what is on screen right now. */
    fun plainText(): String = planPlainText(draft.value.title, draft.value.body)

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Saves pending text and makes a copy of the doc. Reports false when there was nothing saved
     * yet to copy.
     */
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
                title = copyTitle(source.title),
            ),
        )
        return true
    }

    /**
     * Soft-deletes the doc (saving pending text first so Undo brings it back intact) and reports
     * the deleted id, or null when nothing had been saved yet.
     */
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
            if (it.saveStatus == PlanSaveStatus.Saving) it else it.copy(saveStatus = PlanSaveStatus.Saving)
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
            _state.update { it.copy(saveStatus = PlanSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            PlanningDocEntity(
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
            it.copy(saveStatus = if (dirty) PlanSaveStatus.Saving else PlanSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}

// ---------------------------------------------------------------------------------------------
// Folder plan editor
// ---------------------------------------------------------------------------------------------

/** What the folder plan editor shows besides its two text fields, which the screen edits directly. */
internal data class FolderPlanEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val saveStatus: PlanSaveStatus = PlanSaveStatus.Idle,
)

private data class FolderDraft(val title: String = "", val outline: String = "")

/**
 * Loads one folder plan (or prepares a new one) and autosaves it. `treeText` always holds the
 * owner's own outline text, never the rendered tree, so the plan round-trips exactly.
 */
internal class FolderPlanEditorViewModel(
    projectId: String,
    private val planId: String,
    private val repository: FolderPlansRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = planId == PLAN_NEW_ID

    private val _state = MutableStateFlow(FolderPlanEditorUiState(isLoaded = isNew))
    val state: StateFlow<FolderPlanEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(FolderDraft())

    val currentTitle: String get() = draft.value.title
    val currentOutline: String get() = draft.value.outline

    private var currentId: String? = if (isNew) null else planId
    private var ownerProjectId: String? = projectId.takeUnless { it == PLAN_NO_PROJECT }
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
            val plan = repository.getById(planId)
            if (plan == null || plan.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = plan.createdAt
            ownerProjectId = plan.projectId
            draft.value = FolderDraft(title = plan.title, outline = plan.treeText)
            _state.update { it.copy(isLoaded = true) }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onOutlineChange(value: String) {
        if (value == draft.value.outline) return
        draft.update { it.copy(outline = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /** Saves pending text and makes a copy of the plan. Reports false when nothing was saved yet. */
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
                title = copyTitle(source.title),
            ),
        )
        return true
    }

    /** Soft-deletes the plan and reports its id, or null when nothing had been saved yet. */
    fun deletePlan(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == PlanSaveStatus.Saving) it else it.copy(saveStatus = PlanSaveStatus.Saving)
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
        val existingId = currentId
        val isEmpty = text.title.isBlank() && text.outline.isBlank()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = PlanSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            FolderPlanEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                projectId = ownerProjectId,
                title = text.title,
                treeText = text.outline,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) PlanSaveStatus.Saving else PlanSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
