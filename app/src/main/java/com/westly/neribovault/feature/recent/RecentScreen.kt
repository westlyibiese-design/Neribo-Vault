package com.westly.neribovault.feature.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.search.GlobalIndex
import com.westly.neribovault.core.search.IndexedItem
import com.westly.neribovault.core.search.rememberHiddenVaults
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.vault.VaultCatalog

/** The last items you touched, across every vault that is not locked. */
@Composable
fun RecentScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val vm = neriboViewModel { c -> RecentViewModel(GlobalIndex(c)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = rememberHiddenVaults()
    val spacing = NeriboTheme.spacing

    // Reload whenever the screen is shown again (and when a vault lock is turned on or off).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hidden) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh(hidden)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Recent", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> LoadingState()
                !state.hasAnyItems -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = "Nothing here yet",
                    message = "Start writing and it will appear.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> {
                    VaultFilterRow(
                        chips = state.chips,
                        selected = state.selectedVault,
                        onSelect = vm::selectVault,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        state.sections.forEachIndexed { sectionIndex, section ->
                            item(key = "header:${section.bucket.name}") {
                                SectionHeader(
                                    text = section.bucket.label,
                                    modifier = Modifier.padding(top = if (sectionIndex == 0) 0.dp else spacing.sm),
                                )
                            }
                            items(
                                items = section.items,
                                key = { entry -> "${entry.vaultId}:${entry.kind}:${entry.id}" },
                            ) { entry ->
                                RecentRow(item = entry, onClick = { onOpenRoute(entry.route) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultFilterRow(
    chips: List<RecentChip>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboChip(label = "All", selected = selected == null, onClick = { onSelect(null) })
        chips.forEach { chip ->
            NeriboChip(
                label = chip.label,
                selected = selected == chip.vaultId,
                onClick = { onSelect(chip.vaultId) },
            )
        }
    }
}

@Composable
private fun RecentRow(
    item: IndexedItem,
    onClick: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val icon = VaultCatalog.find(item.vaultId)?.icon ?: Icons.Outlined.Description
    NeriboCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.kind}  \u00B7  ${item.vaultLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = formatRelative(item.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
