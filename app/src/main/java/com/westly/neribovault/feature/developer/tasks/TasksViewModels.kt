package com.westly.neribovault.feature.developer.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.TaskEntity
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.data.repository.TasksRepository
import com.westly.neribovault.feature.developer.tasks.reminders.TaskReminderScheduler
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L
private const val NOTICE_MAX_AGE_MS = 15_000L

/** Only one completion runs at a time, so a double tap can never create two next occurrences. */
private val completionMutex = Mutex()

/**
 * Hands a just-deleted task from the editor back to the list it was opened from, which shows
 * the Undo snackbar. The editor has no navigation controller of its own, so this small
 * in-memory mailbox does the job of the `deletedId` saved-state key used by the other vaults.
 */
internal object TaskDeleteNotice {
    /** A task that was moved to Recently deleted from its editor. */
    data class Deleted(val projectId: String?, val taskId: String, val at: Long)

    private val mailbox = MutableStateFlow<Deleted?>(null)

    /** The latest unconsumed delete, or null. */
    val pending: StateFlow<Deleted?> = mailbox.asStateFlow()

    fun post(projectId: String?, taskId: String) {
        mailbox.value = Deleted(projectId, taskId, System.currentTimeMillis())
    }

    fun clear() {
        mailbox.value = null
    }

    /** True while [notice] is recent enough to still be worth an Undo. */
    fun isFresh(notice: Deleted): Boolean =
        System.currentTimeMillis() - notice.at <= NOTICE_MAX_AGE_MS
}

/**
 * The writes shared by every task screen: quick add, done and un-done (with the next occurrence
 * of repeating tasks), delete and restore. Each one also keeps the task's reminder in line.
 */
internal class TaskOperations(
    private val appContext: Context,
    private val repository: TasksRepository,
) {
    /** Creates a normal-priority task with just a title. */
    suspend fun quickAdd(title: String, projectId: String?) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            projectId = projectId,
            title = clean,
            notes = "",
            dueAt = null,
            priority = PRIORITY_NORMAL,
            isDone = false,
            doneAt = null,
            repeatRule = null,
        )
        withContext(NonCancellable) { repository.upsert(task) }
    }

    /**
     * Marks the task done or not done and returns its latest state (null if it is gone).
     * Done cancels the reminder and, for a repeating task with a due date, creates the next
     * occurrence. Un-checking never deletes an occurrence that was already created.
     */
    suspend fun setDone(taskId: String, done: Boolean): TaskEntity? = withContext(NonCancellable) {
        completionMutex.withLock {
            val current = repository.getById(taskId)
            if (current == null || current.isDeleted || current.isDone == done) {
                current
            } else {
                val now = System.currentTimeMillis()
                val updated = current.copy(isDone = done, doneAt = if (done) now else null)
                repository.upsert(updated)
                TaskReminderScheduler.sync(appContext, updated)
                if (done) createNextOccurrence(current, now)
                updated
            }
        }
    }

    private suspend fun createNextOccurrence(finished: TaskEntity, now: Long) {
        val rule = finished.repeatRule ?: return
        val due = finished.dueAt ?: return
        val nextDue = nextDueAt(due, rule, now) ?: return
        // Done, un-done and done again must not pile up copies of the same occurrence.
        val alreadyThere = repository.observeAll().first().any { other ->
            !other.isDone &&
                other.projectId == finished.projectId &&
                other.title == finished.title &&
                other.repeatRule == rule &&
                other.dueAt == nextDue
        }
        if (alreadyThere) return
        val next = TaskEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            projectId = finished.projectId,
            title = finished.title,
            notes = finished.notes,
            dueAt = nextDue,
            priority = finished.priority,
            isDone = false,
            doneAt = null,
            repeatRule = rule,
        )
        repository.upsert(next)
        TaskReminderScheduler.sync(appContext, next)
    }

    /** Soft-deletes the task and cancels its reminder. */
    suspend fun delete(id: String) {
        withContext(NonCancellable) {
            repository.softDelete(id)
            TaskReminderScheduler.cancel(appContext, id)
        }
    }

    /** Brings a soft-deleted task back and recreates its reminder. */
    suspend fun restore(id: String) {
        withContext(NonCancellable) {
            repository.restore(id)
            val task = repository.getById(id)
            if (task != null) TaskReminderScheduler.sync(appContext, task)
        }
    }
}

/** Everything the project Tasks tab draws. */
internal data class ProjectTasksUiState(
    val groups: TaskGroups = TaskGroups(),
    val doneCount: Int = 0,
    val doneExpanded: Boolean = false,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean
        get() = groups.overdue.isEmpty() &&
            groups.today.isEmpty() &&
            groups.upcoming.isEmpty() &&
            groups.noDate.isEmpty() &&
            doneCount == 0
}

/** State and actions for the Tasks tab of one project. */
internal class ProjectTasksViewModel(
    private val projectId: String,
    private val repository: TasksRepository,
    appContext: Context,
) : ViewModel() {

    private val operations = TaskOperations(appContext, repository)
    private val expandedFlow = MutableStateFlow(false)

    val state: StateFlow<ProjectTasksUiState> = combine(
        repository.observeByProject(projectId),
        expandedFlow,
        minuteTicker(),
    ) { tasks, expanded, now ->
        val groups = groupTasks(tasks, now)
        ProjectTasksUiState(
            groups = groups.copy(done = groups.done.take(DONE_LIMIT)),
            doneCount = groups.done.size,
            doneExpanded = expanded,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectTasksUiState())

    fun toggleDoneExpanded() {
        expandedFlow.update { !it }
    }

    fun quickAdd(title: String) {
        viewModelScope.launch { operations.quickAdd(title, projectId) }
    }

    fun toggleDone(task: TaskEntity) {
        viewModelScope.launch { operations.setDone(task.id, !task.isDone) }
    }

    fun delete(id: String) {
        viewModelScope.launch { operations.delete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { operations.restore(id) }
    }
}

/** The chips of the Tasks overview. */
internal enum class TaskFilter(val label: String) {
    Today("Today"),
    Upcoming("Upcoming"),
    Overdue("Overdue"),
    NoDate("No date"),
    AllOpen("All open"),
    Done("Done"),
}

/** One section of the overview and its tasks. */
internal data class SectionList(val section: TaskSection, val tasks: List<TaskEntity>)

/** Everything the Tasks overview draws. */
internal data class TasksOverviewUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val filter: TaskFilter = TaskFilter.AllOpen,
    val sections: List<SectionList> = emptyList(),
    val projectNames: Map<String, String> = emptyMap(),
    val hasAnyTasks: Boolean = false,
    val isLoading: Boolean = true,
)

private data class OverviewControls(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val filter: TaskFilter = TaskFilter.AllOpen,
)

/**
 * State and actions for the Tasks overview (all tasks across projects). When it is first
 * opened it also re-syncs the reminders of every open task, which is safe to repeat.
 */
internal class TasksOverviewViewModel(
    private val repository: TasksRepository,
    projectsRepository: ProjectsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val operations = TaskOperations(appContext, repository)
    private val controls = MutableStateFlow(OverviewControls())

    val state: StateFlow<TasksOverviewUiState> = combine(
        repository.observeAll(),
        projectsRepository.observeAll(),
        controls,
        minuteTicker(),
    ) { tasks, projects, ctl, now ->
        val names = projects.associate { project ->
            project.id to project.name.trim().ifEmpty { "Untitled project" }
        }
        val needle = ctl.query.trim().lowercase()
        val matched = if (needle.isEmpty()) {
            tasks
        } else {
            tasks.filter { task ->
                val projectName = task.projectId?.let { names[it] }.orEmpty()
                task.title.lowercase().contains(needle) ||
                    task.notes.lowercase().contains(needle) ||
                    projectName.lowercase().contains(needle)
            }
        }
        val groups = groupTasks(matched, now)
        val sections = when (ctl.filter) {
            TaskFilter.Today -> listOf(SectionList(TaskSection.Today, groups.today))
            TaskFilter.Upcoming -> listOf(SectionList(TaskSection.Upcoming, groups.upcoming))
            TaskFilter.Overdue -> listOf(SectionList(TaskSection.Overdue, groups.overdue))
            TaskFilter.NoDate -> listOf(SectionList(TaskSection.NoDate, groups.noDate))
            TaskFilter.AllOpen -> listOf(
                SectionList(TaskSection.Overdue, groups.overdue),
                SectionList(TaskSection.Today, groups.today),
                SectionList(TaskSection.Upcoming, groups.upcoming),
                SectionList(TaskSection.NoDate, groups.noDate),
            )
            TaskFilter.Done -> listOf(SectionList(TaskSection.Done, groups.done))
        }.filter { it.tasks.isNotEmpty() }
        TasksOverviewUiState(
            query = ctl.query,
            isSearchOpen = ctl.isSearchOpen,
            filter = ctl.filter,
            sections = sections,
            projectNames = names,
            hasAnyTasks = tasks.isNotEmpty(),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksOverviewUiState())

    init {
        viewModelScope.launch { resyncReminders() }
    }

    /** Makes every open task's reminder match its due time. Idempotent. */
    private suspend fun resyncReminders() {
        withContext(Dispatchers.Default) {
            val tasks = repository.observeAll().first()
            tasks.filter { !it.isDone }.forEach { task ->
                TaskReminderScheduler.sync(appContext, task)
            }
        }
    }

    fun selectFilter(filter: TaskFilter) {
        controls.update { it.copy(filter = filter) }
    }

    fun openSearch() {
        controls.update { it.copy(isSearchOpen = true) }
    }

    fun closeSearch() {
        controls.update { it.copy(isSearchOpen = false, query = "") }
    }

    fun onQueryChange(value: String) {
        controls.update { it.copy(query = value) }
    }

    /** Creates an unlinked task. */
    fun quickAdd(title: String) {
        viewModelScope.launch { operations.quickAdd(title, null) }
    }

    fun toggleDone(task: TaskEntity) {
        viewModelScope.launch { operations.setDone(task.id, !task.isDone) }
    }

    fun delete(id: String) {
        viewModelScope.launch { operations.delete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { operations.restore(id) }
    }
}

/** The little "Saved" / "Saving…" label in the editor's top bar. */
internal enum class TaskSaveStatus { Idle, Saving, Saved }

/** What the editor shows besides its two text fields, which the screen edits directly. */
internal data class TaskEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val projectId: String? = null,
    val dueAt: Long? = null,
    val priority: String = PRIORITY_NORMAL,
    val repeatRule: String? = null,
    val isDone: Boolean = false,
    val saveStatus: TaskSaveStatus = TaskSaveStatus.Idle,
)

private data class TaskDraft(
    val title: String = "",
    val notes: String = "",
)

/**
 * Loads one task (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new task is only created once it has a title or notes, and a new task that ends up empty
 * is discarded. After every save the task's reminder is brought in line with its due time.
 */
internal class TaskEditorViewModel(
    initialProjectId: String?,
    private val taskId: String,
    private val repository: TasksRepository,
    projectsRepository: ProjectsRepository,
    private val appContext: Context,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = taskId == TASK_NEW_ID

    private val operations = TaskOperations(appContext, repository)

    private val _state = MutableStateFlow(
        TaskEditorUiState(isLoaded = isNew, projectId = initialProjectId),
    )
    val state: StateFlow<TaskEditorUiState> = _state.asStateFlow()

    /** The projects to choose from, by name. */
    val projects: StateFlow<List<ProjectEntity>> = projectsRepository.observeAll()
        .map { list -> list.sortedBy { it.name.trim().lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val draft = MutableStateFlow(TaskDraft())

    val currentTitle: String get() = draft.value.title
    val currentNotes: String get() = draft.value.notes

    private var currentId: String? = if (isNew) null else taskId
    private var createdAt: Long = 0L
    private var doneAt: Long? = null

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
            val task = repository.getById(taskId)
            if (task == null || task.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = task.createdAt
            doneAt = task.doneAt
            draft.value = TaskDraft(title = task.title, notes = task.notes)
            _state.update {
                it.copy(
                    isLoaded = true,
                    projectId = task.projectId,
                    dueAt = task.dueAt,
                    priority = task.priority,
                    repeatRule = if (task.dueAt != null) task.repeatRule else null,
                    isDone = task.isDone,
                )
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onNotesChange(value: String) {
        if (value == draft.value.notes) return
        draft.update { it.copy(notes = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setPriority(value: String) {
        if (value == _state.value.priority) return
        _state.update { it.copy(priority = value) }
        scheduleSave(0L)
    }

    fun setProject(projectId: String?) {
        if (projectId == _state.value.projectId) return
        _state.update { it.copy(projectId = projectId) }
        scheduleSave(0L)
    }

    /** Sets the day from the date picker; the time of day stays, or becomes 9:00 AM. */
    fun setDueDate(pickerMillis: Long) {
        _state.update { it.copy(dueAt = applyPickedDate(it.dueAt, pickerMillis)) }
        scheduleSave(0L)
    }

    /** Sets the time of day; the day stays, or becomes the default day. */
    fun setDueTime(hour: Int, minute: Int) {
        _state.update { it.copy(dueAt = applyPickedTime(it.dueAt, hour, minute)) }
        scheduleSave(0L)
    }

    /** Removes the due date. A repeat rule needs a due date, so it goes too. */
    fun clearDue() {
        _state.update { it.copy(dueAt = null, repeatRule = null) }
        scheduleSave(0L)
    }

    /** Picks how the task repeats. A repeating task without a due date gets the default one. */
    fun setRepeat(rule: String?) {
        if (rule == _state.value.repeatRule) return
        _state.update { current ->
            if (rule == null) {
                current.copy(repeatRule = null)
            } else {
                current.copy(repeatRule = rule, dueAt = current.dueAt ?: defaultDueMillis())
            }
        }
        scheduleSave(0L)
    }

    /**
     * Marks the task done or not done through the same logic as the lists (so a repeating task
     * creates its next occurrence). Pending edits are saved first. Does nothing until the task
     * has been saved once.
     */
    fun setDone(done: Boolean) {
        saveJob?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val id = currentId
                    if (id != null && !deleted) {
                        val latest = operations.setDone(id, done)
                        if (latest != null) {
                            doneAt = latest.doneAt
                            _state.update { it.copy(isDone = latest.isDone) }
                        }
                    }
                }
            }
        }
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Soft-deletes the task (saving pending text first so Undo brings it back intact), cancels
     * its reminder and reports the deleted id, or null when nothing had been saved yet.
     */
    fun deleteTask(onDone: (deletedId: String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val id = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val target = currentId
                    if (target != null) operations.delete(target)
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
            if (it.saveStatus == TaskSaveStatus.Saving) it else it.copy(saveStatus = TaskSaveStatus.Saving)
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
        val isEmpty = text.title.isBlank() && text.notes.isBlank()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                TaskReminderScheduler.cancel(appContext, existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = TaskSaveStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        val entity = TaskEntity(
            id = id,
            createdAt = createdAt,
            updatedAt = now,
            projectId = meta.projectId,
            title = text.title,
            notes = text.notes,
            dueAt = meta.dueAt,
            priority = meta.priority,
            isDone = meta.isDone,
            doneAt = doneAt,
            repeatRule = if (meta.dueAt != null) meta.repeatRule else null,
        )
        repository.upsert(entity)
        TaskReminderScheduler.sync(appContext, entity)
        _state.update {
            it.copy(saveStatus = if (dirty) TaskSaveStatus.Saving else TaskSaveStatus.Saved)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
