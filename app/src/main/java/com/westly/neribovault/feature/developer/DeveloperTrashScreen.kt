package com.westly.neribovault.feature.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.TrashRow
import com.westly.neribovault.core.util.formatDate
import kotlinx.coroutines.launch

/** What the person asked to delete forever, kept until they confirm. */
private enum class TrashKind { Project, Secret }

/** Recently deleted projects and secrets: restore, delete forever, or empty the trash. */
@Composable
fun DeveloperTrashScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> DeveloperTrashViewModel(c.projectsRepository, c.secretsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingKind by rememberSaveable { mutableStateOf<TrashKind?>(null) }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }

    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Recently deleted",
                onBack = onBack,
                actions = {
                    if (!state.isEmpty) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Empty trash",
                                    onClick = { confirmEmpty = true },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Items are removed permanently after 30 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> EmptyState(
                        icon = Icons.Outlined.Delete,
                        title = "Nothing deleted",
                        message = "Projects and secrets you delete wait here for 30 days before " +
                            "they are gone for good.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        if (state.projects.isNotEmpty()) {
                            item(key = "header-projects") { SectionHeader("PROJECTS") }
                            items(state.projects, key = { "project-${it.id}" }) { project ->
                                TrashRow(
                                    title = project.name.trim().ifEmpty { "Untitled project" },
                                    subtitle = "Deleted ${formatDate(project.deletedAt ?: project.updatedAt)} " +
                                        "\u00B7 ${projectStatusLabel(project.status)}",
                                    onRestore = {
                                        vm.restoreProject(project.id)
                                        showMessage("Project restored")
                                    },
                                    onDeleteForever = {
                                        pendingKind = TrashKind.Project
                                        pendingId = project.id
                                    },
                                )
                            }
                        }
                        if (state.secrets.isNotEmpty()) {
                            item(key = "header-secrets") { SectionHeader("SECRETS") }
                            items(state.secrets, key = { "secret-${it.id}" }) { secret ->
                                // Only the label is shown, never a value.
                                TrashRow(
                                    title = secret.label.trim().ifEmpty { "Untitled" },
                                    subtitle = "Deleted ${formatDate(secret.deletedAt ?: secret.updatedAt)}",
                                    onRestore = {
                                        vm.restoreSecret(secret.id)
                                        showMessage("Secret restored")
                                    },
                                    onDeleteForever = {
                                        pendingKind = TrashKind.Secret
                                        pendingId = secret.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val kind = pendingKind
    val id = pendingId
    if (kind != null && id != null) {
        ConfirmDialog(
            title = "Delete forever?",
            message = if (kind == TrashKind.Project) {
                "This project will be permanently deleted. This can't be undone."
            } else {
                "This secret will be permanently deleted. This can't be undone."
            },
            confirmLabel = "Delete forever",
            destructive = true,
            onConfirm = {
                if (kind == TrashKind.Project) vm.deleteProjectForever(id) else vm.deleteSecretForever(id)
                pendingKind = null
                pendingId = null
            },
            onDismiss = {
                pendingKind = null
                pendingId = null
            },
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Every project and secret in Recently deleted will be permanently deleted. " +
                "This can't be undone.",
            confirmLabel = "Empty trash",
            destructive = true,
            onConfirm = {
                vm.emptyTrash()
                confirmEmpty = false
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}
