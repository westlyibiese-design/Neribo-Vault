package com.westly.neribovault.feature.coder.workspace

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.coder.ai.CoderAiPanel
import com.westly.neribovault.feature.coder.ai.CoderAiViewModel
import com.westly.neribovault.feature.coder.editor.CoderEditorPanel
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.ExtraKeysBar
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.preview.CoderPreviewPanel
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import com.westly.neribovault.feature.coder.zip.CoderExportResult
import com.westly.neribovault.feature.coder.zip.CoderTrust
import com.westly.neribovault.feature.coder.zip.CoderZipExporter
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import com.westly.neribovault.feature.coder.zip.ZipProgressDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspaceDialog { None, Chooser, NewFile, NewFolder, Rename, Delete, RenameProject }

/**
 * One project. With no file open it shows the file tree (Files mode); with a file open it shows the
 * code editor (Editor mode). Back saves and returns from the editor first, then leaves the project.
 */
@Composable
fun CoderWorkspaceScreen(projectId: String, onBack: () -> Unit, onOpenAiSettings: () -> Unit, onOpenGitHub: () -> Unit) {
    val vm = neriboViewModel(key = "coder-workspace-$projectId") { c ->
        CoderWorkspaceViewModel(projectId, c.coderProjectsRepository, c.coderFileStore)
    }
    val aiVm = neriboViewModel(key = "coder-ai-$projectId") { c ->
        CoderAiViewModel(projectId, c.coderAiSettings, c.coderFileStore)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val editorValue by vm.editorValue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var savedOpenPath by rememberSaveable { mutableStateOf<String?>(null) }
    val restoredPath = remember { savedOpenPath }
    var restoreHandled by remember { mutableStateOf(restoredPath == null) }
    var expandedFolders by rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var dialog by rememberSaveable { mutableStateOf(WorkspaceDialog.None) }
    var dialogPath by rememberSaveable { mutableStateOf("") }
    var showTrustDialog by rememberSaveable { mutableStateOf(false) }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val exporter = remember(context) {
        CoderZipExporter(context.applicationContext, (context.applicationContext as NeriboApp).container.coderFileStore)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null && !exporting) {
            exporting = true
            scope.launch {
                val result = try {
                    exporter.export(projectId, uri)
                } finally {
                    exporting = false
                }
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    when (result) {
                        is CoderExportResult.Success ->
                            if (result.fileCount == 1) "Saved ZIP with 1 file" else "Saved ZIP with ${result.fileCount} files"
                        is CoderExportResult.Failure -> result.message
                    },
                )
            }
        }
    }

    LifecycleSaveEffect(onSave = { vm.flush() })

    // After the process was killed, open the file that was open before.
    LaunchedEffect(Unit) {
        val path = restoredPath
        if (path != null && vm.state.value.openPath == null) vm.openFile(path)
        restoreHandled = true
    }
    LaunchedEffect(state.openPath, restoreHandled) {
        if (restoreHandled || state.openPath != null) savedOpenPath = state.openPath
    }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    val openPath = state.openPath
    val previewEntry = state.previewEntry
    // While the preview is showing it handles Back itself (page history first, then closing).
    BackHandler(enabled = openPath != null && previewEntry == null && !aiOpen) { vm.closeFile() }
    // The AI help screen takes Back first and then returns to where the person was.
    BackHandler(enabled = aiOpen) { aiOpen = false }
    if (aiOpen) {
        CoderAiPanel(
            viewModel = aiVm,
            projectId = projectId,
            projectName = state.project?.name ?: "Project",
            entries = state.entries,
            openPath = openPath,
            editorValue = editorValue,
            snackbarHostState = snackbarHostState,
            onBack = { aiOpen = false },
            onOpenAiSettings = onOpenAiSettings,
            onInsertAtCursor = { vm.insertAtCursor(it) },
            onReplaceSelection = { vm.replaceSelection(it) },
            onReplaceAll = { vm.replaceAll(it) },
            onCreateFile = { path, text -> vm.createFile(path, text) },
        )
        return
    }
    if (previewEntry != null) {
        CoderPreviewPanel(
            projectId = projectId,
            entryPath = previewEntry,
            onClose = { vm.stopPreview() },
            onAskAi = { message ->
                vm.stopPreview()
                aiVm.prepareFromError(message)
                aiOpen = true
            },
        )
        return
    }

    val existingPaths = remember(state.entries) { state.entries.map { it.path }.toSet() }
    val project = state.project
    val canBrowse = !state.isLoading && project != null

    // Run shows the open file when it is a page, Markdown, SVG or JSON; otherwise the project's index.html.
    val runPreview: () -> Unit = {
        focusManager.clearFocus()
        scope.launch {
            val open = vm.state.value.openPath
            val entry = when {
                open != null && isPreviewableFile(open) -> open
                hasRunnableRoot(context, projectId) -> RUN_INDEX
                else -> null
            }
            if (entry != null) {
                vm.startPreview(entry)
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(MSG_NOTHING_TO_RUN)
            }
        }
    }
    // A project that came from a ZIP asks once per app session before its code runs.
    val onRun: () -> Unit = {
        if (project?.templateId == CoderZipRules.TEMPLATE_ID && !CoderTrust.isTrusted(projectId)) {
            showTrustDialog = true
        } else {
            runPreview()
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = project?.name ?: "Coder",
                subtitle = openPath,
                onBack = { if (openPath != null) vm.closeFile() else onBack() },
                actions = {
                    if (openPath != null) {
                        EditorActions(
                            canUndo = state.canUndo,
                            canRedo = state.canRedo,
                            status = state.saveStatus,
                            onUndo = { vm.undo() },
                            onRedo = { vm.redo() },
                            onRetry = { scope.launch { vm.saveNow() } },
                        )
                        AiHelpAction(onClick = { aiOpen = true })
                        RunAction(onClick = onRun)
                    } else if (canBrowse) {
                        AiHelpAction(onClick = { aiOpen = true })
                        RunAction(onClick = onRun)
                        OverflowMenu(
                            actions = listOf(
                                MenuAction("Rename project", { dialog = WorkspaceDialog.RenameProject }),
                                MenuAction("New file", {
                                    dialogPath = ""
                                    dialog = WorkspaceDialog.NewFile
                                }),
                                MenuAction("New folder", {
                                    dialogPath = ""
                                    dialog = WorkspaceDialog.NewFolder
                                }),
                                MenuAction("Export as ZIP", {
                                    exportLauncher.launch(exportFileName(project?.name.orEmpty()))
                                }),
                                MenuAction("GitHub", onOpenGitHub),
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (canBrowse && openPath == null) {
                NeriboFab(onClick = { dialog = WorkspaceDialog.Chooser }, contentDescription = "New file or folder")
            }
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize().padding(padding)) { LoadingState() }
            project == null -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "This project isn't available",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            openPath != null -> {
                val language = remember(openPath) { CoderLanguage.fromPath(openPath) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) {
                    CoderEditorPanel(
                        value = editorValue,
                        onValueChange = { vm.onEditorChange(it) },
                        language = language,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    ExtraKeysBar(onInsert = { vm.insertAtCursor(it) })
                }
            }
            else -> FileTreePanel(
                entries = state.entries,
                expandedFolders = expandedFolders,
                onToggleFolder = { path ->
                    expandedFolders = if (path in expandedFolders) expandedFolders - path else expandedFolders + path
                },
                onOpenFile = { vm.openFile(it) },
                onRename = {
                    dialogPath = it.path
                    dialog = WorkspaceDialog.Rename
                },
                onDelete = {
                    dialogPath = it.path
                    dialog = WorkspaceDialog.Delete
                },
                onNewFileIn = {
                    dialogPath = it
                    dialog = WorkspaceDialog.NewFile
                },
                onNewFolderIn = {
                    dialogPath = it
                    dialog = WorkspaceDialog.NewFolder
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    val dismissDialog = { dialog = WorkspaceDialog.None }
    when (dialog) {
        WorkspaceDialog.None -> Unit
        WorkspaceDialog.Chooser -> NewItemSheet(
            onNewFile = {
                dialogPath = ""
                dialog = WorkspaceDialog.NewFile
            },
            onNewFolder = {
                dialogPath = ""
                dialog = WorkspaceDialog.NewFolder
            },
            onDismiss = dismissDialog,
        )
        WorkspaceDialog.NewFile -> NameEntryDialog(
            title = "New file",
            label = "File name",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                vm.createFile(CoderPaths.join(dialogPath, name))
                dismissDialog()
                if (dialogPath.isNotEmpty() && dialogPath !in expandedFolders) expandedFolders = expandedFolders + dialogPath
            },
            onDismiss = dismissDialog,
            validate = { checkEntryName(it, dialogPath, existingPaths) },
        )
        WorkspaceDialog.NewFolder -> NameEntryDialog(
            title = "New folder",
            label = "Folder name",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                vm.createFolder(CoderPaths.join(dialogPath, name))
                dismissDialog()
                if (dialogPath.isNotEmpty() && dialogPath !in expandedFolders) expandedFolders = expandedFolders + dialogPath
            },
            onDismiss = dismissDialog,
            validate = { checkEntryName(it, dialogPath, existingPaths) },
        )
        WorkspaceDialog.Rename -> {
            val parent = CoderPaths.parentOf(dialogPath)
            NameEntryDialog(
                title = "Rename",
                label = "New name",
                initialValue = CoderPaths.nameOf(dialogPath),
                confirmLabel = "Rename",
                onConfirm = { name ->
                    if (name != CoderPaths.nameOf(dialogPath)) vm.renameEntry(dialogPath, CoderPaths.join(parent, name))
                    dismissDialog()
                },
                onDismiss = dismissDialog,
                validate = { checkEntryName(it, parent, existingPaths, ownPath = dialogPath) },
            )
        }
        WorkspaceDialog.Delete -> {
            val entry = state.entries.firstOrNull { it.path == dialogPath }
            if (entry == null) {
                LaunchedEffect(Unit) { dialog = WorkspaceDialog.None }
            } else {
                DeleteEntryDialog(
                    entry = entry,
                    onConfirm = {
                        vm.deleteEntry(entry.path)
                        dismissDialog()
                    },
                    onDismiss = dismissDialog,
                )
            }
        }
        WorkspaceDialog.RenameProject -> NameEntryDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = project?.name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = { name ->
                vm.renameProject(name)
                dismissDialog()
            },
            onDismiss = dismissDialog,
            maxLength = CoderLimits.MAX_PROJECT_NAME_LENGTH,
        )
    }

    if (showTrustDialog) {
        ConfirmDialog(
            title = "Run this project?",
            message = "This project came from a ZIP file. Running it runs its code on your phone, inside a safe preview that cannot reach your Neribo Vault data. Only run projects you trust.",
            confirmLabel = "Run",
            onConfirm = {
                CoderTrust.trust(projectId)
                showTrustDialog = false
                runPreview()
            },
            onDismiss = { showTrustDialog = false },
            dismissLabel = "Cancel",
        )
    }
    if (exporting) {
        ZipProgressDialog(title = "Saving ZIP\u2026", onCancel = null)
    }
}

private const val RUN_INDEX = "index.html"
private const val MSG_NOTHING_TO_RUN = "Add an index.html file to preview this project."
private val PREVIEWABLE_EXTENSIONS = setOf("html", "htm", "md", "markdown", "svg", "json")

/** The suggested file name for an export: the project name with anything but letters, digits, spaces, "-" and "_" replaced by "_". */
private fun exportFileName(projectName: String): String {
    val safe = projectName.map { ch -> if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_') ch else '_' }
        .joinToString("")
        .trim()
    return (if (safe.isEmpty()) "project" else safe) + ".zip"
}

/** True for files the preview can show on their own. */
private fun isPreviewableFile(path: String): Boolean =
    CoderPaths.nameOf(path).substringAfterLast('.', "").lowercase() in PREVIEWABLE_EXTENSIONS

/** True when the project has something to show as a website. Does disk work off the main thread. */
private suspend fun hasRunnableRoot(context: android.content.Context, projectId: String): Boolean {
    val store = (context.applicationContext as NeriboApp).container.coderFileStore
    return withContext(Dispatchers.IO) { CoderPreviewRoot.find(store, projectId) != null }
}

/** The Run button in the top bar. */
@Composable
private fun RunAction(onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.PlayArrow,
        contentDescription = "Run",
        onClick = onClick,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** The AI help button in the top bar. */
@Composable
private fun AiHelpAction(onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.Lightbulb,
        contentDescription = "AI help",
        onClick = onClick,
    )
}

/** Undo, Redo and the save status, shown in the top bar while a file is open. */
@Composable
private fun RowScope.EditorActions(
    canUndo: Boolean,
    canRedo: Boolean,
    status: CoderSaveStatus,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dim = colors.onSurfaceVariant.copy(alpha = 0.38f)
    SaveStatusIndicator(status = status, onRetry = onRetry)
    NeriboIconButton(
        icon = Icons.Outlined.Undo,
        contentDescription = "Undo",
        onClick = { if (canUndo) onUndo() },
        tint = if (canUndo) colors.onSurfaceVariant else dim,
    )
    NeriboIconButton(
        icon = Icons.Outlined.Redo,
        contentDescription = "Redo",
        onClick = { if (canRedo) onRedo() },
        tint = if (canRedo) colors.onSurfaceVariant else dim,
    )
}

@Composable
private fun SaveStatusIndicator(status: CoderSaveStatus, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        CoderSaveStatus.Saving -> Text(
            text = "Saving\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        CoderSaveStatus.Saved -> Text(
            text = "Saved",
            style = MaterialTheme.typography.labelSmall,
            color = NeriboTheme.extraColors.success,
        )
        CoderSaveStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Couldn't save",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
            TextButton(onClick = onRetry) {
                Text(text = "Retry", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
