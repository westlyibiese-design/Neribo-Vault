package com.westly.neribovault.feature.coder.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.feature.coder.env.CoderEnvStore
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.feature.coder.CoderCheckResult
import com.westly.neribovault.feature.coder.GuidanceMode
import com.westly.neribovault.feature.coder.VAULT_ID
import com.westly.neribovault.feature.coder.VAULT_NAME
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderProjectCopyResult
import com.westly.neribovault.feature.coder.guidance.CoderTipEngine
import com.westly.neribovault.feature.coder.guidance.CoderTipHost
import com.westly.neribovault.feature.coder.guidance.CoderWelcomeCard
import com.westly.neribovault.feature.coder.guidance.GuidanceSheet
import com.westly.neribovault.feature.coder.guidance.TipContext
import com.westly.neribovault.feature.coder.guidance.TipScreen
import com.westly.neribovault.feature.coder.workspace.NameEntryDialog
import com.westly.neribovault.feature.coder.zip.CoderImportReport
import com.westly.neribovault.feature.coder.zip.CoderImportResult
import com.westly.neribovault.feature.coder.zip.CoderZipImporter
import com.westly.neribovault.feature.coder.zip.ImportReportDialog
import com.westly.neribovault.feature.coder.zip.ZipMessageDialog
import com.westly.neribovault.feature.coder.zip.ZipProgressDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The list of Coder projects, with New project, Import ZIP, rename, delete with Undo, and the Recently deleted screen. */
@Composable
fun CoderHomeScreen(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenImportGitHub: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm = neriboViewModel { c -> CoderHomeViewModel(c.coderProjectsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var showGuidanceSheet by rememberSaveable { mutableStateOf(false) }
    var welcomeDismissed by rememberSaveable { mutableStateOf(false) }
    val prefsStore = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val guidanceMode by prefsStore.guidanceMode.collectAsStateWithLifecycle<GuidanceMode?>(initialValue = null)
    val welcomeSeen by prefsStore.welcomeSeen.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val currentMode = guidanceMode
    val currentWelcomeSeen = welcomeSeen
    // The welcome card shows once, for a Beginner with no project yet; nothing shows until the saved choices are read.
    val welcomeVisible = !state.isLoading && !welcomeDismissed && currentMode != null && currentWelcomeSeen != null &&
        CoderTipEngine.showWelcome(currentMode, currentWelcomeSeen, state.projects.size)
    // "Show tips again" clears the saved flag; the card may then come back in this same visit.
    LaunchedEffect(currentWelcomeSeen) { if (currentWelcomeSeen == false) welcomeDismissed = false }

    // Variables of projects that no longer exist (not even in the trash) are removed once the real lists have loaded.
    LaunchedEffect(Unit) {
        val projectsRepo = (context.applicationContext as NeriboApp).container.coderProjectsRepository
        val live = projectsRepo.observeAll().first()
        val trashed = projectsRepo.observeTrashed().first()
        CoderEnvStore.shared(context).purgeOrphans((live + trashed).map { it.id }.toSet())
    }

    val importer = remember(context) {
        val container = (context.applicationContext as NeriboApp).container
        CoderZipImporter(context.applicationContext, container.coderFileStore, container.coderProjectsRepository)
    }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importedId by rememberSaveable { mutableStateOf<String?>(null) }
    var importedFiles by rememberSaveable { mutableStateOf(0) }
    var importedReasons by rememberSaveable { mutableStateOf("") }
    var importedWrapper by rememberSaveable { mutableStateOf<String?>(null) }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !importing) {
            importing = true
            importJob = scope.launch {
                try {
                    val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                    when (val result = importer.import(uri, name)) {
                        is CoderImportResult.Success -> {
                            importedFiles = result.report.importedFiles
                            importedReasons = result.report.skippedReasons.joinToString("\n")
                            importedWrapper = result.report.strippedWrapperFolder
                            importedId = result.projectId
                        }
                        is CoderImportResult.Failure -> importError = result.message
                    }
                } finally {
                    importing = false
                    importJob = null
                }
            }
        }
    }

    // Duplicate: the copy runs in the background with a Cancel button; the new row appears through the project list.
    var duplicating by remember { mutableStateOf(false) }
    var duplicateJob by remember { mutableStateOf<Job?>(null) }
    val startDuplicate: (CoderProjectEntity) -> Unit = { source ->
        if (!duplicating) {
            duplicating = true
            val container = (context.applicationContext as NeriboApp).container
            duplicateJob = scope.launch {
                var message = ""
                try {
                    message = duplicateCoderProject(container.coderFileStore, container.coderProjectsRepository, source)
                } finally {
                    duplicating = false
                    duplicateJob = null
                }
                if (message.isNotEmpty()) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    LaunchedEffect(state.createdProjectId) {
        val id = state.createdProjectId ?: return@LaunchedEffect
        vm.consumeCreated()
        onOpenProject(id)
    }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    val menuActions = buildList<MenuAction> {
        add(MenuAction(label = "Import ZIP", onClick = { importLauncher.launch(ZIP_MIME_TYPES) }, icon = Icons.Outlined.Archive))
        add(MenuAction(label = "Import from GitHub", onClick = onOpenImportGitHub, icon = Icons.Outlined.Cloud))
        add(MenuAction(label = "AI settings", onClick = onOpenAiSettings, icon = Icons.Outlined.Settings))
        add(MenuAction(label = "Recently deleted", onClick = onOpenTrash, icon = Icons.Outlined.History))
        add(MenuAction(label = "Vault lock", onClick = { showLockSheet = true }, icon = Icons.Outlined.Lock))
        add(MenuAction(label = "Guidance", onClick = { showGuidanceSheet = true }, icon = Icons.Outlined.Info))
        add(MenuAction(label = "Help", onClick = onOpenHelp, icon = Icons.Outlined.Book))
        if (BuildConfig.DEBUG) {
            add(MenuAction(label = "Run engine self-test", onClick = { vm.runSelfTest(context) }))
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Coder",
                onBack = onBack,
                actions = { OverflowMenu(actions = menuActions) },
            )
        },
        floatingActionButton = {
            NeriboFab(onClick = { showNewDialog = true }, contentDescription = "New project")
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            CoderTipHost(
                screen = TipScreen.Home,
                context = TipContext(
                    screen = TipScreen.Home,
                    projectListShown = !state.isLoading && state.projects.isNotEmpty(),
                    welcomeVisible = welcomeVisible,
                    busy = showNewDialog || renameId != null || showLockSheet || showGuidanceSheet || importing || duplicating ||
                        importedId != null || importError != null || state.selfTest != null,
                ),
                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs),
            )
            if (welcomeVisible) {
                CoderWelcomeCard(
                    onNewProject = {
                        welcomeDismissed = true
                        scope.launch { prefsStore.markWelcomeSeen() }
                        showNewDialog = true
                    },
                    onSkip = {
                        welcomeDismissed = true
                        scope.launch { prefsStore.markWelcomeSeen() }
                    },
                    modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> LoadingState()
                state.projects.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Code,
                    title = "No projects yet",
                    message = "Create your first project to start writing code.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "New project",
                    onAction = { showNewDialog = true },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.screen,
                        end = spacing.screen,
                        top = spacing.sm,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { onOpenProject(project.id) },
                            onRename = { renameId = project.id },
                            onDuplicate = { startDuplicate(project) },
                            onDelete = {
                                vm.delete(project.id)
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Project deleted",
                                        actionLabel = "Undo",
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.restore(project.id)
                                }
                            },
                        )
                    }
                }
            }
            }
        }
    }

    if (showNewDialog) {
        NewProjectSheet(
            isCreating = state.isCreating,
            onCreate = { name, template ->
                showNewDialog = false
                vm.createProject(name, template)
            },
            onDismiss = { showNewDialog = false },
        )
    }

    val renaming = renameId?.let { id -> state.projects.firstOrNull { it.id == id } }
    if (renaming != null) {
        NameEntryDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = renaming.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                vm.rename(renaming.id, name)
                renameId = null
            },
            onDismiss = { renameId = null },
            maxLength = CoderLimits.MAX_PROJECT_NAME_LENGTH,
        )
    }

    if (showLockSheet) {
        VaultLockSettingsSheet(
            vaultId = VAULT_ID,
            vaultName = VAULT_NAME,
            onDismiss = { showLockSheet = false },
        )
    }

    if (showGuidanceSheet) {
        GuidanceSheet(onDismiss = { showGuidanceSheet = false })
    }

    if (importing) {
        ZipProgressDialog(title = "Unpacking\u2026", onCancel = { importJob?.cancel() })
    }
    if (duplicating) {
        ZipProgressDialog(title = "Duplicating\u2026", onCancel = { duplicateJob?.cancel() })
    }
    val finishedId = importedId
    if (finishedId != null) {
        ImportReportDialog(
            report = CoderImportReport(
                importedFiles = importedFiles,
                skippedFiles = 0,
                skippedReasons = importedReasons.split("\n").filter { it.isNotEmpty() },
                strippedWrapperFolder = importedWrapper,
            ),
            onOpen = {
                importedId = null
                onOpenProject(finishedId)
            },
            onClose = { importedId = null },
        )
    }
    val failureMessage = importError
    if (failureMessage != null) {
        ZipMessageDialog(title = "Couldn't import", message = failureMessage, onDismiss = { importError = null })
    }

    val selfTest = state.selfTest
    if (selfTest != null) {
        SelfTestDialog(results = selfTest, onDismiss = { vm.dismissSelfTest() })
    }
}

private const val MSG_DUPLICATE_NO_SPACE = "Not enough space to duplicate this project."
private const val MSG_DUPLICATE_FAILED = "Couldn't duplicate this project. Please try again."
private const val MSG_DUPLICATED = "Project duplicated"

/**
 * The name of a copy of [name]: "X copy", then "X copy 2", "X copy 3" and so on, the first one no project in [existing]
 * has (ignoring case). The result is never longer than the project name limit; a long [name] is cut to make room.
 */
internal fun duplicateProjectName(name: String, existing: Collection<String>): String {
    val taken = existing.map { it.trim().lowercase() }.toSet()
    val base = name.trim()
    var number = 1
    while (true) {
        val suffix = if (number == 1) " copy" else " copy $number"
        val candidate = base.take(CoderLimits.MAX_PROJECT_NAME_LENGTH - suffix.length).trimEnd() + suffix
        if (candidate.lowercase() !in taken) return candidate
        number++
    }
}

/**
 * Copies [source] into a new project: every file and folder except the app's own `.coder*` folders (so GitHub and
 * Developer links, variables and history stay with the original), at least twice the size free on the phone, and the
 * row inserted last so no row ever points at a half copy. Returns the message to show ("" when it was cancelled).
 * Cancelling the calling coroutine stops the copy and removes the half-made folder.
 */
internal suspend fun duplicateCoderProject(
    files: CoderFileStore,
    projects: CoderProjectsRepository,
    source: CoderProjectEntity,
): String {
    val newProjectId = newId()
    val result = withContext(Dispatchers.IO) {
        val scope = this
        files.copyProject(source.id, newProjectId, shouldContinue = { scope.isActive })
    }
    when (result) {
        is CoderProjectCopyResult.Copied -> Unit
        CoderProjectCopyResult.NoSpace -> return MSG_DUPLICATE_NO_SPACE
        CoderProjectCopyResult.Cancelled -> return ""
        else -> return MSG_DUPLICATE_FAILED
    }
    val taken = projects.observeAll().first().map { it.name }
    val created = projects.createProject(
        name = duplicateProjectName(source.name, taken),
        templateId = source.templateId,
        starterFiles = emptyList(),
        id = newProjectId,
    )
    if (created == null) {
        withContext(Dispatchers.IO) { files.deleteProject(newProjectId) }
        return MSG_DUPLICATE_FAILED
    }
    return MSG_DUPLICATED
}

/** The file types the picker offers for Import ZIP; some phones label a ZIP with the generic type. */
private val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/** The picked file's name, or null when the provider does not give one. Does a quick query, so call it off the main thread. */
private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
    }
} catch (e: Exception) {
    null
}

@Composable
private fun ProjectCard(
    project: CoderProjectEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, bottom = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = spacing.sm)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Updated " + formatRelative(project.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            OverflowMenu(
                actions = listOf(
                    MenuAction(label = "Rename", onClick = onRename, icon = Icons.Outlined.Edit),
                    MenuAction(label = "Duplicate", onClick = onDuplicate, icon = Icons.Outlined.FileCopy),
                    MenuAction(label = "Delete", onClick = onDelete, icon = Icons.Outlined.Delete, destructive = true),
                ),
            )
        }
    }
}

/** Debug only: one line per check, then a summary such as "38 of 38 passed". */
@Composable
private fun SelfTestDialog(results: List<CoderCheckResult>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val passed = results.count { it.passed }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        },
        title = { Text(text = "Engine self-test", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "$passed of ${results.size} passed",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                for (result in results) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = if (result.passed) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = if (result.passed) "Passed" else "Failed",
                            modifier = Modifier.size(18.dp),
                            tint = if (result.passed) NeriboTheme.extraColors.success else colors.error,
                        )
                        Column(modifier = Modifier.padding(start = spacing.sm)) {
                            Text(text = result.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                            if (!result.passed) {
                                Text(text = result.detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            }
                        }
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
