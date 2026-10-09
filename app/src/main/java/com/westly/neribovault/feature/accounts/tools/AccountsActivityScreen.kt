package com.westly.neribovault.feature.accounts.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.util.formatRelative

/**
 * What was shown, copied or changed in Accounts, and when. Only actions are listed: never
 * passwords, values or names.
 */
@Composable
fun AccountsActivityScreen(onBack: () -> Unit) {
    val vm = neriboViewModel(key = "accounts-activity") { c -> AccountsActivityViewModel(c.auditRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Activity", onBack = onBack) },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.History,
                title = "No activity yet",
                message = "Showing and copying passwords will appear here.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    text = "Only actions are recorded, never passwords or names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.sm),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = row.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatRelative(row.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        NeriboDivider()
                    }
                }
            }
        }
    }
}
