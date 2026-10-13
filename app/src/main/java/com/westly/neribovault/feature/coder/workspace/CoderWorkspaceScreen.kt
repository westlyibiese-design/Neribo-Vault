package com.westly.neribovault.feature.coder.workspace

import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.coder.editor.CoderEditorPanel
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.ExtraKeysBar
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import kotlinx.coroutines.launch

private enum class WorkspaceDialog { None, Chooser, NewFile, NewFolder, Rename, Delete, RenameProject }

/**
 * One project. With no file open it shows the file tree (Files mode); with a file open it shows the
 * code editor (Editor mode). Back saves and returns from the editor first, then leaves the project.
 */
@Composable
fun CoderWorkspaceScreen(projectId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "coder-workspace-$projectId") { c ->
        CoderWorkspaceViewModel(projectId, c.coderProjectsRepository, c.coderFileStore)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val editorValue by vm.editorValue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var savedOpenPath by rememberSaveable { mutableStateOf<String?>(null) }
    val restoredPath = remember { savedOpenPath }
    var restoreHandled by remember { mutableStateOf(restoredPath == null) }
    var expandedFolders by rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var dialog by rememberSaveable { mutableStateOf(WorkspaceDialog.None) }
    var dialogPath by rememberSaveable { mutableStateOf("") }

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
    BackHandler(enabled = openPath != null) { vm.closeFile() }

    val existingPaths = remember(state.entries) { state.entries.map { it.path }.toSet() }
    val project = state.project
    val canBrowse = !state.isLoading && project != null

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
                    } else if (canBrowse) {
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
