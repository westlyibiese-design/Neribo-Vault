package com.westly.neribovault.feature.developer.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.developer.HOME_FILTER_ORDER
import com.westly.neribovault.feature.developer.projectStatusLabel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Developer home: shortcuts to Tasks and Prompts, then the project list with status
 * filters, search and an Undo snackbar for deletes (including deletes made on the detail screen).
 */
@Composable
fun DeveloperHomeScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onEditProject: (String) -> Unit,
    onNewProject: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenPrompts: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> DeveloperHomeViewModel(c.projectsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
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

    LaunchedEffect(deletedIdFlow) {
        deletedIdFlow.collect { id ->
            if (id != null) {
                onDeletedIdConsumed()
                showUndo("Moved to Recently deleted", { vm.restore(id) })
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

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Developer",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search projects",
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
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Developer lock",
                                onClick = { showLockSheet = true },
                                icon = Icons.Outlined.Lock,
                            ),
                            MenuAction(
                                label = "Secret activity",
                                onClick = onOpenActivity,
                                icon = Icons.Outlined.Description,
                            ),
                            MenuAction(
                                label = "Recently deleted",
                                onClick = onOpenTrash,
                                icon = Icons.Outlined.History,
                            ),
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            NeriboFab(onClick = onNewProject, contentDescription = "New project")
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                    placeholder = "Name, description or stack",
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.sm,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "shortcuts") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        ShortcutRow(
                            icon = Icons.Outlined.CheckCircle,
                            label = "Tasks",
                            onClick = onOpenTasks,
                        )
                        ShortcutRow(
                            icon = Icons.Outlined.Code,
                            label = "Prompts",
                            onClick = onOpenPrompts,
                        )
                    }
                }
                item(key = "header") { SectionHeader("PROJECTS") }
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeriboChip(
                            label = "All",
                            selected = state.selectedStatus == null,
                            onClick = { vm.selectStatus(null) },
                        )
                        HOME_FILTER_ORDER.forEach { status ->
                            NeriboChip(
                                label = projectStatusLabel(status),
                                selected = state.selectedStatus == status,
                                onClick = { vm.selectStatus(status) },
                            )
                        }
                    }
                }
                when {
                    state.isLoading -> Unit
                    state.projects.isEmpty() -> item(key = "empty") {
                        HomeEmptyState(state = state, onNewProject = onNewProject)
                    }
                    else -> items(state.projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            actions = projectActions(
                                project = project,
                                onEdit = { onEditProject(project.id) },
                                onToggleArchive = { vm.toggleArchived(project) },
                                onDelete = { pendingDeleteId = project.id },
                            ),
                            onClick = { onOpenProject(project.id) },
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        ConfirmDialog(
            title = "Delete this project?",
            message = "Items linked to this project will stay but lose their project. " +
                "You can bring the project back from Recently deleted.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                val id = pendingDeleteId
                pendingDeleteId = null
                if (id != null) {
                    vm.delete(id)
                    scope.launch { showUndo("Moved to Recently deleted", { vm.restore(id) }) }
                }
            },
            onDismiss = { pendingDeleteId = null },
        )
    }

    if (showLockSheet) {
        VaultLockSettingsSheet(
            vaultId = "developer",
            vaultName = "Developer",
            onDismiss = { showLockSheet = false },
        )
    }
}

private fun projectActions(
    project: ProjectEntity,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
): List<MenuAction> {
    val archived = project.status == "archived"
    return listOf(
        MenuAction(label = "Edit", onClick = onEdit, icon = Icons.Outlined.Edit),
        MenuAction(
            label = if (archived) "Unarchive" else "Archive",
            onClick = onToggleArchive,
            icon = if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
        ),
        MenuAction(
            label = "Delete",
            onClick = onDelete,
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )
}

@Composable
private fun ShortcutRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colors.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeEmptyState(state: DeveloperHomeUiState, onNewProject: () -> Unit) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No project matches \u201C${state.query.trim()}\u201D.",
        )
        state.isFiltering -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No projects have this status yet.",
        )
        else -> EmptyState(
            icon = Icons.Outlined.Code,
            title = "No projects yet",
            message = "Every product starts as a project. Add your first one.",
            actionLabel = "New project",
            onAction = onNewProject,
        )
    }
}
