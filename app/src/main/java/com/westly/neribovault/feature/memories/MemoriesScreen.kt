package com.westly.neribovault.feature.memories

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.memories.components.MemoryCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Memories list: search, a row of tag and people filters, and the memories grouped by month,
 * newest first, with the Undo snackbar for deletes (including deletes made elsewhere).
 */
@Composable
fun MemoriesScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onEditMemory: (String) -> Unit,
    onNewMemory: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> MemoriesViewModel(c.memoriesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen never
    // pops the keyboard open on its own.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }

    // The text field edits this local copy so typing is never delayed by the database.
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
                title = "Memories",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search memories",
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
            NeriboFab(onClick = onNewMemory, contentDescription = "New memory")
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
                    placeholder = "Title, story, place, person or tag",
                )
            }
            if (state.filters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeriboChip(
                        label = "All",
                        selected = state.selectedFilter == null,
                        onClick = { vm.clearFilter() },
                    )
                    state.filters.forEach { filter ->
                        NeriboChip(
                            label = filter.value,
                            selected = isSelectedFilter(state.selectedFilter, filter),
                            onClick = { vm.toggleFilter(filter) },
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> MemoriesEmptyState(state = state, onNewMemory = onNewMemory)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        state.groups.forEach { group ->
                            item(key = "header-${group.key}") { SectionHeader(group.title) }
                            items(group.memories, key = { it.id }) { memory ->
                                MemoryCard(
                                    memory = memory,
                                    onClick = { onOpenMemory(memory.id) },
                                    onEdit = { onEditMemory(memory.id) },
                                    onDelete = {
                                        vm.delete(memory.id)
                                        scope.launch {
                                            showUndo("Moved to Recently deleted", { vm.restore(memory.id) })
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoriesEmptyState(state: MemoriesUiState, onNewMemory: () -> Unit) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.isFiltering -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No memories fit this filter yet.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> EmptyState(
            icon = Icons.Outlined.PhotoLibrary,
            title = "No memories yet",
            message = "The moments that matter deserve a place. Add your first memory.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New memory",
            onAction = onNewMemory,
        )
    }
}

/** True when [chosen] is the same filter as [candidate], ignoring letter case. */
private fun isSelectedFilter(chosen: MemoryFilter?, candidate: MemoryFilter): Boolean =
    chosen != null &&
        chosen.isPerson == candidate.isPerson &&
        chosen.value.equals(candidate.value, ignoreCase = true)
