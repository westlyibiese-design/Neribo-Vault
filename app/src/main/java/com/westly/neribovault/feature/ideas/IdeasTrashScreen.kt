package com.westly.neribovault.feature.ideas

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
import com.westly.neribovault.core.ui.components.TrashRow
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.core.util.snippet
import kotlinx.coroutines.launch

/** Recently deleted ideas: restore, delete forever, or empty the trash. */
@Composable
fun IdeasTrashScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> IdeasTrashViewModel(c.ideasRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Recently deleted",
                onBack = onBack,
                actions = {
                    if (state.ideas.isNotEmpty()) {
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
                    state.ideas.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Delete,
                        title = "Nothing deleted",
                        message = "Ideas you delete wait here for 30 days before they are gone for good.",
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
                        items(state.ideas, key = { it.id }) { idea ->
                            val deletedOn = formatDate(idea.deletedAt ?: idea.updatedAt)
                            val preview = snippet(idea.description.take(200), maxChars = 60)
                            TrashRow(
                                title = idea.title.ifBlank { "Untitled idea" },
                                subtitle = if (preview.isEmpty()) {
                                    "Deleted $deletedOn"
                                } else {
                                    "Deleted $deletedOn \u00B7 $preview"
                                },
                                onRestore = {
                                    vm.restore(idea.id)
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar("Idea restored")
                                    }
                                },
                                onDeleteForever = { pendingDeleteId = idea.id },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = "Delete forever?",
            message = "This idea will be permanently deleted. This can't be undone.",
            confirmLabel = "Delete forever",
            onConfirm = {
                vm.deleteForever(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
            destructive = true,
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Every idea in Recently deleted will be permanently deleted. This can't be undone.",
            confirmLabel = "Empty trash",
            onConfirm = {
                vm.emptyTrash()
                confirmEmpty = false
            },
            onDismiss = { confirmEmpty = false },
            destructive = true,
        )
    }
}
