package com.westly.neribovault.feature.developer.tasks

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.TaskEntity
import com.westly.neribovault.feature.developer.tasks.reminders.TaskReminderScheduler
import kotlinx.coroutines.launch

private const val TITLE_PLACEHOLDER = "Title"
private const val NOTES_PLACEHOLDER = "Write your notes"
private const val UNDO_MESSAGE = "Moved to Recently deleted"

/** The menu every task row offers: open it, or move it to Recently deleted. */
private fun taskMenuActions(onOpen: () -> Unit, onDelete: () -> Unit): List<MenuAction> = listOf(
    MenuAction(label = "Open", onClick = onOpen, icon = Icons.Outlined.Edit),
    MenuAction(label = "Delete", onClick = onDelete, icon = Icons.Outlined.Delete, destructive = true),
)

/**
 * The Tasks tab of a project: an "Add task" button, a quick-add field, and the tasks in
 * Overdue, Today, Upcoming and No date sections, with finished tasks tucked under a collapsed
 * Done header. Deletes show an Undo snackbar that belongs to this tab.
 */
@Composable
fun ProjectTasksTab(
    projectId: String,
    onOpenTask: (taskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = "dev-tasks-tab-$projectId") { c ->
        ProjectTasksViewModel(projectId, c.tasksRepository, appContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    // A task deleted from its editor comes back here through TaskDeleteNotice.
    LaunchedEffect(projectId) {
        TaskDeleteNotice.pending.collect { notice ->
            if (notice != null && notice.projectId == projectId) {
                TaskDeleteNotice.clear()
                if (TaskDeleteNotice.isFresh(notice)) {
                    showUndo(UNDO_MESSAGE, { vm.restore(notice.taskId) })
                }
            }
        }
    }

    val renderTask: @Composable (TaskEntity) -> Unit = { task ->
        TaskRow(
            task = task,
            projectName = null,
            actions = taskMenuActions(
                onOpen = { onOpenTask(task.id) },
                onDelete = {
                    vm.delete(task.id)
                    scope.launch { showUndo(UNDO_MESSAGE, { vm.restore(task.id) }) }
                },
            ),
            onToggleDone = { vm.toggleDone(task) },
            onClick = { onOpenTask(task.id) },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NeriboButton(
                text = "Add task",
                onClick = { onOpenTask(TASK_NEW_ID) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen),
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.Add,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            QuickAddField(
                placeholder = "Quick add a task",
                onAdd = { title -> vm.quickAdd(title) },
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val groups = state.groups
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "No tasks yet",
                        message = "Type one above and press Done. Small steps count.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        taskSectionItems(TaskSection.Overdue, groups.overdue, true, renderTask)
                        taskSectionItems(TaskSection.Today, groups.today, true, renderTask)
                        taskSectionItems(TaskSection.Upcoming, groups.upcoming, true, renderTask)
                        taskSectionItems(TaskSection.NoDate, groups.noDate, true, renderTask)
                        if (state.doneCount > 0) {
                            item(key = "header-done") {
                                DoneHeader(
                                    count = state.doneCount,
                                    expanded = state.doneExpanded,
                                    onToggle = { vm.toggleDoneExpanded() },
                                )
                            }
                            if (state.doneExpanded) {
                                taskSectionItems(TaskSection.Done, groups.done, false, renderTask)
                                if (state.doneCount > DONE_LIMIT) {
                                    item(key = "done-note") {
                                        Text(
                                            text = "Showing the last $DONE_LIMIT finished tasks.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(spacing.lg),
        ) { data ->
            Snackbar(snackbarData = data, shape = MaterialTheme.shapes.medium)
        }
    }
}

/**
 * Every task across all projects, with Today / Upcoming / Overdue / No date / All open / Done
 * chips, search over title, notes and project, and a quick-add field that creates a task with
 * no project. Opening it also re-syncs the reminders of all open tasks.
 */
@Composable
fun TasksOverviewScreen(
    onOpenTask: (projectId: String?, taskId: String) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = "dev-tasks-overview") { c ->
        TasksOverviewViewModel(c.tasksRepository, c.projectsRepository, appContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen never
    // pops the keyboard open on its own.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    // The search field edits this local copy so typing is never delayed by the database.
    var localQuery by rememberSaveable { mutableStateOf(state.query) }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    // A task deleted from its editor comes back here through TaskDeleteNotice.
    LaunchedEffect(Unit) {
        TaskDeleteNotice.pending.collect { notice ->
            if (notice != null) {
                TaskDeleteNotice.clear()
                if (TaskDeleteNotice.isFresh(notice)) {
                    showUndo(UNDO_MESSAGE, { vm.restore(notice.taskId) })
                }
            }
        }
    }

    LaunchedEffect(state.isSearchOpen, focusSearchOnOpen) {
        if (state.isSearchOpen && focusSearchOnOpen) {
            focusSearchOnOpen = false
            runCatching { searchFocus.requestFocus() }
        }
    }

    BackHandler(enabled = state.isSearchOpen) {
        localQuery = ""
        vm.closeSearch()
    }

    val renderTask: @Composable (TaskEntity) -> Unit = { task ->
        TaskRow(
            task = task,
            projectName = task.projectId?.let { id -> state.projectNames[id] },
            actions = taskMenuActions(
                onOpen = { onOpenTask(task.projectId, task.id) },
                onDelete = {
                    vm.delete(task.id)
                    scope.launch { showUndo(UNDO_MESSAGE, { vm.restore(task.id) }) }
                },
            ),
            onToggleDone = { vm.toggleDone(task) },
            onClick = { onOpenTask(task.projectId, task.id) },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Tasks",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search tasks",
                        onClick = {
                            if (state.isSearchOpen) {
                                localQuery = ""
                                vm.closeSearch()
                            } else {
                                focusSearchOnOpen = true
                                vm.openSearch()
                            }
                        },
                    )
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isSearchOpen) {
                NeriboSearchField(
                    query = localQuery,
                    onQueryChange = { value ->
                        localQuery = value
                        vm.onQueryChange(value)
                    },
                    modifier = Modifier
                        .padding(horizontal = spacing.screen)
                        .focusRequester(searchFocus),
                    placeholder = "Title, notes or project",
                )
                Spacer(modifier = Modifier.height(spacing.xs))
            }
            QuickAddField(
                placeholder = "Quick add a task",
                onAdd = { title -> vm.quickAdd(title) },
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskFilter.values().forEach { filter ->
                    NeriboChip(
                        label = filter.label,
                        selected = state.filter == filter,
                        onClick = { vm.selectFilter(filter) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    state.isLoading -> Unit
                    state.sections.isEmpty() -> OverviewEmptyState(state = state)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        state.sections.forEach { group ->
                            taskSectionItems(
                                section = group.section,
                                tasks = group.tasks,
                                showHeader = state.filter == TaskFilter.AllOpen,
                                itemContent = renderTask,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewEmptyState(state: TasksOverviewUiState) {
    when {
        !state.hasAnyTasks -> EmptyState(
            icon = Icons.Outlined.CheckCircle,
            title = "No tasks yet",
            message = "Add one above, or open a project and plan its work.",
            modifier = Modifier.fillMaxSize(),
        )
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> {
            val message = when (state.filter) {
                TaskFilter.Today -> "Nothing is due today."
                TaskFilter.Upcoming -> "Nothing is coming up."
                TaskFilter.Overdue -> "Nothing is overdue. Well done."
                TaskFilter.NoDate -> "Every task has a date."
                TaskFilter.AllOpen -> "Every task is done."
                TaskFilter.Done -> "Finished tasks will show up here."
            }
            EmptyState(
                icon = Icons.Outlined.CheckCircle,
                title = "All clear",
                message = message,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Full-screen task editor: a serif title, notes, the project, an optional due date and time
 * with a reminder, priority, repeat and a done toggle. Autosaves 600ms after the last change
 * and whenever the screen stops. `taskId == "new"` creates a task; [projectId] preselects the
 * project (null means unlinked).
 */
@Composable
fun TaskEditorScreen(
    projectId: String?,
    taskId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm = neriboViewModel(key = "dev-task-editor-$taskId") { c ->
        TaskEditorViewModel(projectId, taskId, c.tasksRepository, c.projectsRepository, appContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var notificationsOn by remember {
        mutableStateOf(TaskReminderScheduler.areNotificationsAllowed(context))
    }
    // The person can change the setting in the system Settings app and come back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = TaskReminderScheduler.areNotificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        notificationsOn = TaskReminderScheduler.areNotificationsAllowed(context)
    }

    // Called whenever the person gives a task a due time. On Android 13 and above this asks
    // for the notification permission, but only the first time. Saving never waits for it.
    val askForNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsOn &&
            TaskReminderScheduler.shouldAskForPermission(context)
        ) {
            TaskReminderScheduler.markPermissionAsked(context)
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val openNotificationSettings: () -> Unit = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent) }
    }

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val projectName: String? = projects
        .firstOrNull { it.id == state.projectId }
        ?.name
        ?.trim()
        ?.ifEmpty { "Untitled project" }

    val actions = listOf(
        MenuAction(
            label = "Copy",
            onClick = {
                context.copyToClipboard(
                    "Task",
                    taskPlainText(
                        title = vm.currentTitle,
                        notes = vm.currentNotes,
                        dueAt = state.dueAt,
                        priority = state.priority,
                        repeatRule = state.repeatRule,
                        projectName = projectName,
                    ),
                )
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete",
            onClick = {
                val owner = state.projectId
                vm.deleteTask { deletedId ->
                    if (deletedId != null) TaskDeleteNotice.post(owner, deletedId)
                    onBack()
                }
            },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New task" else "Task",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    TaskSaveStatus.Idle -> null
                    TaskSaveStatus.Saving -> "Saving\u2026"
                    TaskSaveStatus.Saved -> "Saved"
                },
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            TaskEditorContent(
                vm = vm,
                state = state,
                projects = projects,
                projectName = projectName,
                padding = padding,
                notificationsOn = notificationsOn,
                onDueSet = askForNotifications,
                onOpenNotificationSettings = openNotificationSettings,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun TaskEditorContent(
    vm: TaskEditorViewModel,
    state: TaskEditorUiState,
    projects: List<ProjectEntity>,
    projectName: String?,
    padding: PaddingValues,
    notificationsOn: Boolean,
    onDueSet: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var notesValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentNotes, TextRange(vm.currentNotes.length)))
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showProjectSheet by rememberSaveable { mutableStateOf(false) }

    val titleFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onNotesChange(notesValue.text)
        // Quick capture: a brand-new task opens with the title focused.
        if (vm.isNew && titleValue.text.isEmpty() && notesValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val dueAt = state.dueAt
    val now = System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        Spacer(modifier = Modifier.height(spacing.md))
        SectionHeader("Title")
        Spacer(modifier = Modifier.height(spacing.sm))
        TaskBorderlessField(
            value = titleValue,
            onValueChange = { new ->
                val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                titleValue = cleaned
                vm.onTitleChange(cleaned.text)
            },
            textStyle = titleStyle,
            placeholder = TITLE_PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocus),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { runCatching { notesFocus.requestFocus() } },
            ),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboDivider()
        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("Notes")
        Spacer(modifier = Modifier.height(spacing.sm))
        TaskBorderlessField(
            value = notesValue,
            onValueChange = { new ->
                notesValue = new
                vm.onNotesChange(new.text)
            },
            textStyle = bodyStyle,
            placeholder = NOTES_PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .focusRequester(notesFocus),
        )

        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("PROJECT")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { showProjectSheet = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = projectName ?: if (state.projectId == null) "No project" else "Linked project",
                style = MaterialTheme.typography.bodyLarge,
                color = if (state.projectId == null) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = "Change project",
                tint = colors.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("DUE")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = if (dueAt != null) formatTaskDue(dueAt, now) else "No due date",
                style = MaterialTheme.typography.bodyLarge,
                color = if (dueAt != null) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            NeriboButton(
                text = if (dueAt == null) "Pick date" else "Date",
                onClick = { showDatePicker = true },
                style = ButtonStyle.Secondary,
            )
            if (dueAt != null) {
                NeriboButton(
                    text = "Time",
                    onClick = { showTimePicker = true },
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "Clear",
                    onClick = { vm.clearDue() },
                    style = ButtonStyle.Text,
                )
            }
        }
        if (dueAt != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "Reminders can arrive a few minutes after the time you set.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (!state.isDone && dueAt <= now) {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = "This time has passed, so no reminder will be sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeriboTheme.extraColors.warning,
                )
            } else if (!state.isDone && !notificationsOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Reminders are off. Allow notifications in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeriboTheme.extraColors.warning,
                        modifier = Modifier.weight(1f),
                    )
                    NeriboButton(
                        text = "Settings",
                        onClick = onOpenNotificationSettings,
                        style = ButtonStyle.Text,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("PRIORITY")
        TaskChipRow {
            TASK_PRIORITIES.forEach { priority ->
                NeriboChip(
                    label = priorityLabel(priority),
                    selected = state.priority == priority,
                    onClick = { vm.setPriority(priority) },
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))
        SectionHeader("REPEAT")
        TaskChipRow {
            TASK_REPEATS.forEach { rule ->
                NeriboChip(
                    label = repeatLabel(rule),
                    selected = state.repeatRule == rule,
                    onClick = {
                        vm.setRepeat(rule)
                        if (rule != null) onDueSet()
                    },
                )
            }
        }
        if (state.repeatRule != null) {
            Text(
                text = "Marking it done creates the next one automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }

        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("STATUS")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { vm.setDone(!state.isDone) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.isDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (state.isDone) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = if (state.isDone) "Done" else "Mark as done",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(spacing.xxl))
    }

    if (showDatePicker) {
        TaskDatePickerDialog(
            initialPickerMillis = toPickerMillis(dueAt ?: defaultDueMillis(now)),
            onConfirm = { picked ->
                vm.setDueDate(picked)
                showDatePicker = false
                onDueSet()
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        val base = dueAt ?: defaultDueMillis(now)
        TaskTimePickerDialog(
            initialHour = hourOf(base),
            initialMinute = minuteOf(base),
            onConfirm = { hour, minute ->
                vm.setDueTime(hour, minute)
                showTimePicker = false
                onDueSet()
            },
            onDismiss = { showTimePicker = false },
        )
    }
    if (showProjectSheet) {
        ProjectPickerSheet(
            projects = projects,
            selectedId = state.projectId,
            onSelect = { id ->
                vm.setProject(id)
                showProjectSheet = false
            },
            onDismiss = { showProjectSheet = false },
        )
    }
}

/** A horizontally scrolling row of chips. */
@Composable
private fun TaskChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
