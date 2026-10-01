package com.westly.neribovault.feature.developer.bugs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.BugEntity
import com.westly.neribovault.data.repository.BugsRepository
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
 * Hands a just-deleted bug from the editor back to the Bugs tab, which shows the Undo snackbar.
 * The editor has no navigation controller of its own, so this small in-memory mailbox does the
 * job of the `deletedId` saved-state key used by the other vaults.
 */
internal object BugDeleteNotice {
    /** A bug that was moved to Recently deleted from its editor. */
    data class Deleted(val projectId: String, val bugId: String, val at: Long)

    private val mailbox = MutableStateFlow<Deleted?>(null)

    /** The latest unconsumed delete, or null. */
    val pending: StateFlow<Deleted?> = mailbox.asStateFlow()

    fun post(projectId: String, bugId: String) {
        mailbox.value = Deleted(projectId, bugId, System.currentTimeMillis())
    }

    fun clear() {
        mailbox.value = null
    }

    /** True while [notice] is recent enough to still be worth an Undo. */
    fun isFresh(notice: Deleted): Boolean =
        System.currentTimeMillis() - notice.at <= NOTICE_MAX_AGE_MS
}

/** Which bugs the tab shows. */
internal enum class BugFilter(val label: String) {
    Open("Open"),
    Resolved("Resolved"),
    All("All"),
}

/** Everything the Bugs tab draws. */
internal data class ProjectBugsUiState(
    val filter: BugFilter = BugFilter.Open,
    val bugs: List<BugEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Bugs tab of one project. */
internal class ProjectBugsViewModel(
    projectId: String,
    private val repository: BugsRepository,
) : ViewModel() {

    private val filterFlow = MutableStateFlow(BugFilter.Open)

    val state: StateFlow<ProjectBugsUiState> = combine(
        repository.observeByProject(projectId),
        filterFlow,
    ) { all, filter ->
        val visible = all
            .filter { bug ->
                when (filter) {
                    BugFilter.Open -> isActiveStatus(bug.status)
                    BugFilter.Resolved -> isFinishedStatus(bug.status)
                    BugFilter.All -> true
                }
            }
            .sortedWith(
                compareBy<BugEntity> { severityRank(it.severity) }
                    .thenByDescending { it.updatedAt },
            )
        ProjectBugsUiState(filter = filter, bugs = visible, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectBugsUiState())

    fun setFilter(filter: BugFilter) {
        filterFlow.value = filter
    }

    /** Moves the bug to [status], stamping or clearing `resolvedAt` as needed. */
    fun changeStatus(bugId: String, status: String) {
        viewModelScope.launch {
            val latest = repository.getById(bugId) ?: return@launch
            if (latest.isDeleted || latest.status == status) return@launch
            repository.upsert(latest.withStatus(status, System.currentTimeMillis()))
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}

/** The little "Saved" / "Saving…" label in the editor's top bar. */
internal enum class BugSaveStatus { Idle, Saving, Saved }

/** What the editor shows besides its four text fields, which the screen edits directly. */
internal data class BugEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val severity: String = SEVERITY_MEDIUM,
    val status: String = BUG_OPEN,
    val resolvedAt: Long? = null,
    val saveStatus: BugSaveStatus = BugSaveStatus.Idle,
)

private data class BugDraft(
    val title: String = "",
    val description: String = "",
    val steps: String = "",
    val resolution: String = "",
)

/**
 * Loads one bug (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new bug is only created once it has some text, and a new bug that ends up completely empty
 * is discarded.
 */
internal class BugEditorViewModel(
    private val projectId: String,
    private val bugId: String,
    private val repository: BugsRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = bugId == BUG_NEW_ID

    private val _state = MutableStateFlow(BugEditorUiState(isLoaded = isNew))
    val state: StateFlow<BugEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(BugDraft())

    val currentTitle: String get() = draft.value.title
    val currentDescription: String get() = draft.value.description
    val currentSteps: String get() = draft.value.steps
    val currentResolution: String get() = draft.value.resolution

    private var currentId: String? = if (isNew) null else bugId
    private var ownerProjectId: String? = projectId
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
            val bug = repository.getById(bugId)
            if (bug == null || bug.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = bug.createdAt
            ownerProjectId = bug.projectId
            draft.value = BugDraft(
                title = bug.title,
                description = bug.description,
                steps = bug.stepsToReproduce,
                resolution = bug.resolution,
            )
            _state.update {
                it.copy(
                    isLoaded = true,
                    severity = bug.severity,
                    status = bug.status,
                    resolvedAt = bug.resolvedAt,
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

    fun onStepsChange(value: String) {
        if (value == draft.value.steps) return
        draft.update { it.copy(steps = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onResolutionChange(value: String) {
        if (value == draft.value.resolution) return
        draft.update { it.copy(resolution = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setSeverity(value: String) {
        if (value == _state.value.severity) return
        _state.update { it.copy(severity = value) }
        scheduleSave(0L)
    }

    /**
     * Picks a status. Resolved and Closed stamp `resolvedAt` if it is empty; Open and
     * In progress clear it.
     */
    fun setStatus(value: String) {
        if (value == _state.value.status) return
        val now = System.currentTimeMillis()
        _state.update { current ->
            if (isFinishedStatus(value)) {
                current.copy(status = value, resolvedAt = current.resolvedAt ?: now)
            } else {
                current.copy(status = value, resolvedAt = null)
            }
        }
        scheduleSave(0L)
    }

    /** The plain-text report for Copy and Share, built from what is on screen right now. */
    fun plainText(): String {
        val text = draft.value
        val meta = _state.value
        return bugPlainText(
            title = text.title,
            severity = meta.severity,
            status = meta.status,
            description = text.description,
            steps = text.steps,
            resolution = text.resolution,
        )
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the bug (saving pending text first so Undo brings it back intact) and
     * reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteBug(onDone: (deletedId: String?) -> Unit) {
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
            if (it.saveStatus == BugSaveStatus.Saving) it else it.copy(saveStatus = BugSaveStatus.Saving)
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
            text.description.isBlank() &&
            text.steps.isBlank() &&
            text.resolution.isBlank()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = BugSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        repository.upsert(
            BugEntity(
                id = id,
                createdAt = createdAt,
                updatedAt = now,
                projectId = ownerProjectId,
                title = text.title,
                description = text.description,
                severity = meta.severity,
                status = meta.status,
                stepsToReproduce = text.steps,
                resolution = text.resolution,
                resolvedAt = meta.resolvedAt,
            ),
        )
        _state.update {
            it.copy(saveStatus = if (dirty) BugSaveStatus.Saving else BugSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
