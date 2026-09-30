package com.westly.neribovault.feature.goals

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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
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
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.feature.goals.components.GoalCard
import com.westly.neribovault.feature.goals.components.rememberDayTick
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Goals list: scope chips (Active, Paused, Completed), category chips, search, pinned and
 * other goals, and the Undo snackbar for deletes (including deletes made on the detail screen).
 */
@Composable
fun GoalsScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> GoalsViewModel(c.goalsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val dayTick = rememberDayTick()
    // Set only by the user tapping the search icon, so coming back to this screen with search
    // still open does not pop the keyboard up again.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }

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

    val changeStatus: (GoalEntity, String, String) -> Unit = { goal, newStatus, message ->
        val previous = goal.status
        vm.setStatus(goal.id, newStatus)
        scope.launch { showUndo(message, { vm.setStatus(goal.id, previous) }) }
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

    val renderGoal: @Composable (GoalListItem) -> Unit = { item ->
        val goal = item.goal
        val actions = buildList<MenuAction> {
            add(
                MenuAction(
                    label = if (goal.isPinned) "Unpin" else "Pin",
                    onClick = { vm.togglePinned(goal) },
                    icon = Icons.Outlined.PushPin,
                ),
            )
            when (goal.status) {
                "active" -> {
                    add(
                        MenuAction(
                            label = "Pause",
                            onClick = { changeStatus(goal, "paused", "Goal paused") },
                            icon = Icons.Outlined.Pause,
                        ),
                    )
                    add(
                        MenuAction(
                            label = "Mark completed",
                            onClick = { changeStatus(goal, "completed", "Goal completed") },
                            icon = Icons.Outlined.CheckCircle,
                        ),
                    )
                }
                "paused" -> {
                    add(
                        MenuAction(
                            label = "Resume",
                            onClick = { changeStatus(goal, "active", "Goal resumed") },
                            icon = Icons.Outlined.PlayArrow,
                        ),
                    )
                    add(
                        MenuAction(
                            label = "Mark completed",
                            onClick = { changeStatus(goal, "completed", "Goal completed") },
                            icon = Icons.Outlined.CheckCircle,
                        ),
                    )
                }
                "completed" -> add(
                    MenuAction(
                        label = "Reopen",
                        onClick = { changeStatus(goal, "active", "Goal reopened") },
                        icon = Icons.Outlined.Refresh,
                    ),
                )
                else -> Unit
            }
            add(
                MenuAction(
                    label = "Delete",
                    onClick = {
                        vm.delete(goal.id)
                        scope.launch { showUndo("Moved to Recently deleted", { vm.restore(goal.id) }) }
                    },
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                ),
            )
        }
        GoalCard(
            item = item,
            dayTick = dayTick,
            actions = actions,
            onClick = { onOpenGoal(goal.id) },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Goals",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search goals",
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
            NeriboFab(onClick = onNewGoal, contentDescription = "New goal")
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
                    placeholder = "Search goals",
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
                GoalsScope.values().forEach { option ->
                    NeriboChip(
                        label = option.label,
                        selected = state.scope == option,
                        onClick = { vm.selectScope(option) },
                    )
                }
            }
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
                    selected = state.category == null,
                    onClick = { vm.selectCategory(null) },
                )
                GOAL_CATEGORIES.forEach { category ->
                    NeriboChip(
                        label = goalCategoryLabel(category),
                        selected = state.category == category,
                        onClick = { vm.selectCategory(category) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> GoalsEmptyState(
                        state = state,
                        onNewGoal = onNewGoal,
                        onClearCategory = { vm.clearCategory() },
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
                            items(state.pinned, key = { it.goal.id }) { item -> renderGoal(item) }
                            if (state.others.isNotEmpty()) {
                                item(key = "header-others") { SectionHeader("OTHERS") }
                            }
                        }
                        items(state.others, key = { it.goal.id }) { item -> renderGoal(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalsEmptyState(
    state: GoalsUiState,
    onNewGoal: () -> Unit,
    onClearCategory: () -> Unit,
) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.category != null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No goals here",
            message = "No ${state.scope.label.lowercase()} goals in ${goalCategoryLabel(state.category).lowercase()} yet.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Show all categories",
            onAction = onClearCategory,
        )
        state.scope == GoalsScope.Paused -> EmptyState(
            icon = Icons.Outlined.Pause,
            title = "Nothing paused",
            message = "A goal you set aside for a season rests here until you are ready.",
            modifier = Modifier.fillMaxSize(),
        )
        state.scope == GoalsScope.Completed -> EmptyState(
            icon = Icons.Outlined.CheckCircle,
            title = "No finished goals yet",
            message = "When you complete a goal, it will be kept here to look back on.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> EmptyState(
            icon = Icons.Outlined.Flag,
            title = "No goals yet",
            message = "What are you working toward? Save \u20A6500,000 for a laptop, or finish the first draft of your book.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New goal",
            onAction = onNewGoal,
        )
    }
}
