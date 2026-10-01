package com.westly.neribovault.feature.developer.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.feature.developer.bugs.ProjectBugsTab
import com.westly.neribovault.feature.developer.buildProjectSummary
import com.westly.neribovault.feature.developer.docs.ProjectDocsTab
import com.westly.neribovault.feature.developer.plans.ProjectPlansTab
import com.westly.neribovault.feature.developer.secrets.SecretsTab
import com.westly.neribovault.feature.developer.tasks.ProjectTasksTab
import kotlinx.coroutines.launch

private val TAB_LABELS = listOf("Overview", "Secrets", "Bugs", "Tasks", "Plans", "Docs")

/**
 * A project with its tabs. Overview and Secrets live in this feature; Bugs, Tasks, Plans and
 * Docs are drawn by their own composables and bring their own "add" buttons.
 */
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: (String) -> Unit,
    onOpenSecret: (secretId: String) -> Unit,
    onOpenBug: (bugId: String) -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onOpenPlanningDoc: (docId: String) -> Unit,
    onOpenFolderPlan: (planId: String) -> Unit,
    onOpenDocument: (docId: String) -> Unit,
    onOpenPrompt: (promptId: String) -> Unit,
) {
    val vm = neriboViewModel(key = "project-detail-$projectId") { c ->
        ProjectDetailViewModel(projectId, c.projectsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val project = state.project

    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = project?.name?.trim()?.ifEmpty { null } ?: "Project",
                onBack = onBack,
                actions = {
                    if (project != null) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Edit",
                                    onClick = onEdit,
                                    icon = Icons.Outlined.Edit,
                                ),
                                MenuAction(
                                    label = "Copy project summary",
                                    onClick = {
                                        context.copyToClipboard("Project summary", buildProjectSummary(project))
                                        showMessage("Summary copied")
                                    },
                                    icon = Icons.Outlined.ContentCopy,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = { confirmDelete = true },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> Unit
            project == null -> EmptyState(
                icon = Icons.Outlined.Code,
                title = "Project not found",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                DeveloperTabRow(
                    labels = TAB_LABELS,
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> ProjectOverviewTab(project = project, onMessage = showMessage)
                        1 -> SecretsTab(
                            projectId = project.id,
                            snackbarHostState = snackbarHostState,
                            onOpenSecret = onOpenSecret,
                        )
                        2 -> ProjectBugsTab(
                            projectId = project.id,
                            onOpenBug = onOpenBug,
                            modifier = Modifier.fillMaxSize(),
                        )
                        3 -> ProjectTasksTab(
                            projectId = project.id,
                            onOpenTask = onOpenTask,
                            modifier = Modifier.fillMaxSize(),
                        )
                        4 -> ProjectPlansTab(
                            projectId = project.id,
                            onOpenPlanningDoc = onOpenPlanningDoc,
                            onOpenFolderPlan = onOpenFolderPlan,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> ProjectDocsTab(
                            projectId = project.id,
                            onOpenDocument = onOpenDocument,
                            onOpenPrompt = onOpenPrompt,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this project?",
            message = "Items linked to this project will stay but lose their project. " +
                "You can bring the project back from Recently deleted.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete { onDeleted(projectId) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * A scrollable row of plain tab labels with an accent underline under the selected one and a
 * hairline below the row. Each tab is at least 48dp tall.
 */
@Composable
private fun DeveloperTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Column(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) colors.primary else Color.Transparent),
                    )
                }
            }
        }
        NeriboDivider()
    }
}
