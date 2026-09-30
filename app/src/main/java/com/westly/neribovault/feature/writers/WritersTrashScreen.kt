package com.westly.neribovault.feature.writers

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
import kotlinx.coroutines.launch

/** Recently deleted stories: restore, delete forever, or empty the trash. */
@Composable
fun WritersTrashScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> WritersTrashViewModel(c.storiesRepository) }
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
                    if (state.stories.isNotEmpty()) {
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
                    state.stories.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Delete,
                        title = "Nothing deleted",
                        message = "Stories you delete wait here for 30 days before they are gone for good.",
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
                        items(state.stories, key = { it.id }) { story ->
                            val deletedOn = formatDate(story.deletedAt ?: story.updatedAt)
                            TrashRow(
                                title = story.title.ifBlank { "Untitled story" },
                                subtitle = "Deleted $deletedOn \u00B7 ${story.genre}",
                                onRestore = {
                                    vm.restore(story.id)
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar("Story restored")
                                    }
                                },
                                onDeleteForever = { pendingDeleteId = story.id },
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
            message = "This story and all its chapters, characters and notes will be permanently deleted. This can't be undone.",
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
            message = "Every story in Recently deleted will be permanently deleted, with its chapters, characters and notes. This can't be undone.",
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
