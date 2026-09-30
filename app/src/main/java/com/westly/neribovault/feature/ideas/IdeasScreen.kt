package com.westly.neribovault.feature.ideas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.feature.ideas.components.IdeaCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Ideas list: category chips, an optional status filter, search, pinned and other ideas,
 * and the Undo snackbar for deletes (including deletes made in the editor).
 */
@Composable
fun IdeasScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenIdea: (String) -> Unit,
    onNewIdea: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> IdeasViewModel(c.ideasRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen with search
    // still open does not pop the keyboard up again.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var showStatusSheet by rememberSaveable { mutableStateOf(false) }

    // The text field edits this local copy so typing is never delayed by the database;
    // every change is forwarded to the ViewModel, which owns the real query.
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

    val renderIdea: @Composable (IdeaEntity) -> Unit = { idea ->
        val actions = listOf(
            MenuAction(
                label = if (idea.isPinned) "Unpin" else "Pin",
                onClick = { vm.togglePinned(idea) },
                icon = Icons.Outlined.PushPin,
            ),
            MenuAction(
                label = "Copy",
                onClick = {
                    context.copyToClipboard("Idea", idea.toPlainText())
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
            MenuAction(
                label = "Share",
                onClick = { context.shareText(idea.title.ifBlank { null }, idea.toPlainText()) },
                icon = Icons.Outlined.Share,
            ),
            MenuAction(
                label = "Delete",
                onClick = {
                    vm.delete(idea.id)
                    scope.launch { showUndo("Moved to Recently deleted", { vm.restore(idea.id) }) }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
        IdeaCard(
            idea = idea,
            actions = actions,
            onClick = { onOpenIdea(idea.id) },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Ideas",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search ideas",
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
                                label = "Filter by status",
                                onClick = { showStatusSheet = true },
                                icon = Icons.Outlined.FilterList,
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
            NeriboFab(onClick = onNewIdea, contentDescription = "New idea")
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
                    placeholder = "Search ideas",
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
                val statusFilter = state.status
                if (statusFilter != null) {
                    NeriboChip(
                        label = "Status: ${ideaStatusLabel(statusFilter)} \u00D7",
                        selected = true,
                        onClick = { vm.selectStatus(null) },
                    )
                }
                NeriboChip(
                    label = "All",
                    selected = state.category == null,
                    onClick = { vm.selectCategory(null) },
                )
                IDEA_CATEGORIES.forEach { category ->
                    NeriboChip(
                        label = ideaCategoryLabel(category),
                        selected = state.category == category,
                        onClick = { vm.selectCategory(category) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> IdeasEmptyState(
                        state = state,
                        onNewIdea = onNewIdea,
                        onClearFilters = { vm.clearFilters() },
                    )
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
                        if (state.pinned.isNotEmpty()) {
                            item(key = "header-pinned") { SectionHeader("PINNED") }
                            items(state.pinned, key = { it.id }) { idea -> renderIdea(idea) }
                            if (state.others.isNotEmpty()) {
                                item(key = "header-others") { SectionHeader("OTHERS") }
                            }
                        }
                        items(state.others, key = { it.id }) { idea -> renderIdea(idea) }
                    }
                }
            }
        }
    }

    if (showStatusSheet) {
        StatusFilterSheet(
            selected = state.status,
            onSelect = { value ->
                vm.selectStatus(value)
                showStatusSheet = false
            },
            onDismiss = { showStatusSheet = false },
        )
    }
}

@Composable
private fun IdeasEmptyState(
    state: IdeasUiState,
    onNewIdea: () -> Unit,
    onClearFilters: () -> Unit,
) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.category != null || state.status != null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No ideas here",
            message = "No ideas match this filter yet.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Show all ideas",
            onAction = onClearFilters,
        )
        else -> EmptyState(
            icon = Icons.Outlined.Lightbulb,
            title = "No sparks yet",
            message = "Catch the next one before it fades. A mobile-money tool for market women in Onitsha, or a short story about a danfo driver in Lagos.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New idea",
            onAction = onNewIdea,
        )
    }
}

/** Bottom sheet listing "All statuses" and the five idea statuses. */
@Composable
private fun StatusFilterSheet(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Filter by status",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        StatusFilterRow(label = "All statuses", isSelected = selected == null, onClick = { onSelect(null) })
        IDEA_STATUSES.forEach { status ->
            StatusFilterRow(
                label = ideaStatusLabel(status),
                isSelected = selected == status,
                onClick = { onSelect(status) },
            )
        }
    }
}

@Composable
private fun StatusFilterRow(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) colors.primary else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(20.dp),
                tint = colors.primary,
            )
        }
    }
}
