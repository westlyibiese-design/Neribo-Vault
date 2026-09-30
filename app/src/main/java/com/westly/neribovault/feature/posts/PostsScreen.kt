package com.westly.neribovault.feature.posts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.feature.posts.components.PostCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** How often the Upcoming groups are re-worked out, so they stay right past midnight. */
private const val CLOCK_TICK_MS = 30_000L

/**
 * The Posts list: a Pipeline / Upcoming switch, status chips, search, and the Undo snackbar
 * for deletes (including deletes made in the editor).
 */
@Composable
fun PostsScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenPost: (String) -> Unit,
    onNewPost: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm = neriboViewModel { c -> PostsViewModel(c.postsRepository, appContext) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen with search
    // still open does not pop the keyboard up again.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }

    // The text field edits this local copy so typing is never delayed by the database;
    // every change is forwarded to the ViewModel, which owns the real query.
    var localQuery by rememberSaveable { mutableStateOf(state.query) }

    // The current time, refreshed twice a minute, so Today / Tomorrow / Overdue stay correct.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_TICK_MS)
            nowMillis = System.currentTimeMillis()
        }
    }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
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

    val renderPost: @Composable (SocialPostEntity, Boolean, Boolean) -> Unit = { post, isOverdue, quickMark ->
        val actions = buildList<MenuAction> {
            add(
                MenuAction(
                    label = "Edit",
                    onClick = { onOpenPost(post.id) },
                    icon = Icons.Outlined.Edit,
                ),
            )
            add(
                MenuAction(
                    label = "Copy caption with hashtags",
                    onClick = {
                        context.copyToClipboard("Post caption", composePostText(post.caption, post.hashtags))
                        showMessage("Copied to clipboard")
                    },
                    icon = Icons.Outlined.ContentCopy,
                ),
            )
            add(
                MenuAction(
                    label = "Share",
                    onClick = {
                        context.shareText(
                            post.title.ifBlank { null },
                            composePostText(post.caption, post.hashtags),
                        )
                    },
                    icon = Icons.Outlined.Share,
                ),
            )
            if (post.status != STATUS_POSTED) {
                add(
                    MenuAction(
                        label = "Mark as posted",
                        onClick = {
                            vm.markPosted(post)
                            showMessage("Marked as posted")
                        },
                        icon = Icons.Outlined.Check,
                    ),
                )
            }
            add(
                MenuAction(
                    label = "Duplicate",
                    onClick = {
                        vm.duplicate(post)
                        showMessage("Duplicated as a draft")
                    },
                    icon = Icons.Outlined.FileCopy,
                ),
            )
            add(
                MenuAction(
                    label = "Delete",
                    onClick = {
                        vm.delete(post.id)
                        scope.launch { showUndo("Moved to Recently deleted", { vm.restore(post.id) }) }
                    },
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                ),
            )
        }
        PostCard(
            post = post,
            actions = actions,
            onClick = { onOpenPost(post.id) },
            isOverdue = isOverdue,
            onMarkPosted = if (quickMark) {
                {
                    vm.markPosted(post)
                    showMessage("Marked as posted")
                }
            } else {
                null
            },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Posts",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search posts",
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
            NeriboFab(onClick = onNewPost, contentDescription = "New post")
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
                    placeholder = "Search posts",
                )
            }
            PostsTabSwitch(
                selected = state.tab,
                onSelect = { vm.selectTab(it) },
                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
            if (state.tab == PostsTab.Pipeline) {
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
                        selected = state.status == null,
                        onClick = { vm.selectStatus(null) },
                    )
                    POST_STATUSES.forEach { status ->
                        NeriboChip(
                            label = postStatusLabel(status),
                            selected = state.status == status,
                            onClick = { vm.selectStatus(status) },
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.sm,
                    bottom = 96.dp,
                )
                when {
                    state.isLoading -> Unit
                    state.tab == PostsTab.Pipeline -> {
                        if (state.pipeline.isEmpty()) {
                            PipelineEmptyState(
                                state = state,
                                onNewPost = onNewPost,
                                onClearFilter = { vm.selectStatus(null) },
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(spacing.md),
                            ) {
                                items(state.pipeline, key = { it.id }) { post ->
                                    renderPost(post, false, false)
                                }
                            }
                        }
                    }
                    else -> {
                        val groups = remember(state.scheduled, nowMillis) {
                            groupUpcoming(state.scheduled, nowMillis)
                        }
                        if (groups.isEmpty()) {
                            UpcomingEmptyState(state = state, onNewPost = onNewPost)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(spacing.md),
                            ) {
                                groups.forEach { group ->
                                    item(key = "header-${group.bucket.name}") {
                                        if (group.bucket == UpcomingBucket.Overdue) {
                                            OverdueHeader(text = group.bucket.header)
                                        } else {
                                            SectionHeader(group.bucket.header)
                                        }
                                    }
                                    items(group.posts, key = { it.id }) { post ->
                                        renderPost(post, group.bucket == UpcomingBucket.Overdue, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The two-segment Pipeline / Upcoming control. */
@Composable
private fun PostsTabSwitch(
    selected: PostsTab,
    onSelect: (PostsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val outerShape = MaterialTheme.shapes.medium
    val innerShape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(colors.surfaceVariant, outerShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PostsTab.values().forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(innerShape)
                    .background(if (isSelected) colors.surface else colors.surfaceVariant, innerShape)
                    .border(
                        BorderStroke(1.dp, if (isSelected) colors.outlineVariant else colors.surfaceVariant),
                        innerShape,
                    )
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (tab == PostsTab.Pipeline) "Pipeline" else "Upcoming",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The warning-toned header of the Overdue group. */
@Composable
private fun OverdueHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NeriboTheme.extraColors.warning,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

@Composable
private fun PipelineEmptyState(
    state: PostsUiState,
    onNewPost: () -> Unit,
    onClearFilter: () -> Unit,
) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.status != null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No posts here",
            message = "No posts have this status yet.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Show all posts",
            onAction = onClearFilter,
        )
        else -> EmptyState(
            icon = Icons.Outlined.Edit,
            title = "Plan what you share",
            message = "Start with an idea.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New post",
            onAction = onNewPost,
        )
    }
}

@Composable
private fun UpcomingEmptyState(state: PostsUiState, onNewPost: () -> Unit) {
    if (state.query.isNotBlank()) {
        EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No scheduled post matches \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        EmptyState(
            icon = Icons.Outlined.Event,
            title = "Nothing scheduled",
            message = "Pick a time for your next post.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New post",
            onAction = onNewPost,
        )
    }
}
