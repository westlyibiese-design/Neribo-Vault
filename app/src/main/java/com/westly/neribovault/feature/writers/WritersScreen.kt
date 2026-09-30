package com.westly.neribovault.feature.writers

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
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
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
import com.westly.neribovault.feature.writers.components.StoryCard
import com.westly.neribovault.feature.writers.components.WritingIdeasRow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Writers stories list: status chips, search, story cards with chapter and word stats, the
 * Writing ideas row, and the Undo snackbar for deletes (including deletes made on the story
 * screen).
 */
@Composable
fun WritersScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenStory: (String) -> Unit,
    onNewStory: () -> Unit,
    onEditStory: (String) -> Unit,
    onOpenIdeas: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> WritersViewModel(c.storiesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only when the person taps the search icon, so coming back to this screen never
    // pops the keyboard open on its own.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    // The text field edits this local copy so typing is never delayed; changes are forwarded
    // to the ViewModel, which owns the real query.
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
                showUndo("Moved to Recently deleted") { vm.restore(id) }
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
                title = "Writers",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search stories",
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
                                label = "Writing ideas",
                                onClick = onOpenIdeas,
                                icon = Icons.Outlined.Lightbulb,
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
            NeriboFab(
                onClick = onNewStory,
                contentDescription = "New story",
                text = "New story",
            )
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
                    placeholder = "Search stories",
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StoryFilter.values().forEach { option ->
                    NeriboChip(
                        label = option.label,
                        selected = state.filter == option,
                        onClick = { vm.selectFilter(option) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty && state.isFiltering -> FilteredEmptyState(state = state)
                    state.isEmpty -> Column(modifier = Modifier.fillMaxSize()) {
                        WritingIdeasRow(
                            onClick = onOpenIdeas,
                            modifier = Modifier.padding(
                                start = spacing.screen,
                                end = spacing.screen,
                                top = spacing.sm,
                            ),
                        )
                        EmptyState(
                            icon = Icons.Outlined.Book,
                            title = "No stories yet",
                            message = "Every great story starts with a title. Begin yours.",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            actionLabel = "New story",
                            onAction = onNewStory,
                        )
                    }
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
                        if (!state.isFiltering) {
                            item(key = "writing-ideas-row") {
                                WritingIdeasRow(onClick = onOpenIdeas)
                            }
                        }
                        items(state.stories, key = { it.id }) { story ->
                            StoryCard(
                                story = story,
                                stats = state.stats[story.id],
                                actions = listOf(
                                    MenuAction(
                                        label = "Edit details",
                                        onClick = { onEditStory(story.id) },
                                        icon = Icons.Outlined.Edit,
                                    ),
                                    MenuAction(
                                        label = "Delete",
                                        onClick = {
                                            vm.delete(story.id)
                                            scope.launch {
                                                showUndo("Moved to Recently deleted") {
                                                    vm.restore(story.id)
                                                }
                                            }
                                        },
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                    ),
                                ),
                                onClick = { onOpenStory(story.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilteredEmptyState(state: WritersUiState) {
    val message = if (state.query.isNotBlank()) {
        "Nothing found for \u201C${state.query.trim()}\u201D."
    } else {
        "No stories are marked ${state.filter.label.lowercase()} yet."
    }
    EmptyState(
        icon = Icons.Outlined.Search,
        title = "No matches",
        message = message,
        modifier = Modifier.fillMaxSize(),
    )
}
