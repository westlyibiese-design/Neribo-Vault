package com.westly.neribovault.feature.developer.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.formatRelative

/** Recent secrets activity: what happened and when, never which secret or what value. */
@Composable
fun SecretActivityScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> SecretActivityViewModel(c.auditRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Secret activity",
                onBack = onBack,
                actions = {
                    if (state.entries.isNotEmpty()) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Clear activity",
                                    onClick = { confirmClear = true },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Unit
                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = "No activity yet",
                    message = "When secrets are revealed, copied or changed, it shows up here.",
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
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(state.entries, key = { it.id }) { entry ->
                        NeriboCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activityText(entry.action),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = formatRelative(entry.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear activity?",
            message = "The activity list will be emptied. This can't be undone.",
            confirmLabel = "Clear activity",
            destructive = true,
            onConfirm = {
                vm.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}
