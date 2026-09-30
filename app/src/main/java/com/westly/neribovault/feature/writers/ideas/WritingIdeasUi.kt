package com.westly.neribovault.feature.writers.ideas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.ui.components.TrashRow
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.WritingIdeaEntity
import kotlinx.coroutines.launch

private const val NEW_ID = "new"
private const val MODE_LIST = "list"
private const val MODE_EDITOR = "editor"
private const val MODE_TRASH = "trash"

/**
 * The Writing ideas screen: status chips, search, idea cards, a New idea button, an editor
 * that opens inside this screen, and a Recently deleted section. It needs no navigation
 * routes of its own; Back leaves the editor or the trash first, then the screen.
 */
@Composable
fun WritingIdeasScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> WritingIdeasViewModel(c.writingIdeasRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var mode by rememberSaveable { mutableStateOf(MODE_LIST) }
    var editingId by rememberSaveable { mutableStateOf(NEW_ID) }
    var editorKey by rememberSaveable { mutableStateOf("") }
    // The search box edits this local copy so typing is never delayed; changes are forwarded to
    // the ViewModel, which owns the real query.
    var localQuery by rememberSaveable { mutableStateOf("") }
    // Set only when the person taps the search icon, so coming back never pops the keyboard.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }

    val showUndo: (String) -> Unit = { id ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = "Moved to Recently deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.restore(id)
        }
    }

    LaunchedEffect(Unit) {
        // A no-op unless the query was restored after the process was killed.
        if (localQuery.isNotEmpty()) {
            vm.openSearch()
            vm.onQueryChange(localQuery)
        }
    }

    when (mode) {
        MODE_EDITOR -> IdeaEditorPane(
            ideaId = editingId,
            editorKey = editorKey,
            onClose = { mode = MODE_LIST },
            onDeleted = { id ->
                mode = MODE_LIST
                if (id != null) showUndo(id)
            },
        )
        MODE_TRASH -> IdeasTrashPane(vm = vm, onClose = { mode = MODE_LIST })
        else -> IdeasListPane(
            vm = vm,
            state = state,
            snackbarHostState = snackbarHostState,
            localQuery = localQuery,
            focusSearchOnOpen = focusSearchOnOpen,
            onFocusSearchConsumed = { focusSearchOnOpen = false },
            onToggleSearch = {
                if (state.isSearchOpen) {
                    localQuery = ""
                    vm.closeSearch()
                } else {
                    focusSearchOnOpen = true
                    vm.openSearch()
                }
            },
            onQueryChange = { value ->
                localQuery = value
                vm.onQueryChange(value)
            },
            onBack = onBack,
            onOpenIdea = { id ->
                editingId = id
                editorKey = newId()
                mode = MODE_EDITOR
            },
            onNewIdea = {
                editingId = NEW_ID
                editorKey = newId()
                mode = MODE_EDITOR
            },
            onOpenTrash = { mode = MODE_TRASH },
            onIdeaDeleted = { id ->
                vm.delete(id)
                showUndo(id)
            },
        )
    }
}

@Composable
private fun IdeasListPane(
    vm: WritingIdeasViewModel,
    state: WritingIdeasUiState,
    snackbarHostState: SnackbarHostState,
    localQuery: String,
    focusSearchOnOpen: Boolean,
    onFocusSearchConsumed: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenIdea: (String) -> Unit,
    onNewIdea: () -> Unit,
    onOpenTrash: () -> Unit,
    onIdeaDeleted: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(state.isSearchOpen, focusSearchOnOpen) {
        if (state.isSearchOpen && focusSearchOnOpen) {
            onFocusSearchConsumed()
            runCatching { searchFocus.requestFocus() }
        }
    }
    BackHandler(enabled = state.isSearchOpen) { onToggleSearch() }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Writing ideas",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search ideas",
                        onClick = onToggleSearch,
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
            NeriboFab(
                onClick = onNewIdea,
                contentDescription = "New idea",
                text = "New idea",
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isSearchOpen) {
                NeriboSearchField(
                    query = localQuery,
                    onQueryChange = onQueryChange,
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
                IdeaFilter.values().forEach { option ->
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
                    state.ideas.isEmpty() && state.isFiltering -> EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matches",
                        message = if (state.query.isNotBlank()) {
                            "Nothing found for \u201C${state.query.trim()}\u201D."
                        } else {
                            "No ${state.filter.label.lowercase()} ideas yet."
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.ideas.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Lightbulb,
                        title = "No ideas yet",
                        message = "A line overheard at the park, a proverb, a \u2018what if\u2019. Catch it here.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "New idea",
                        onAction = onNewIdea,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.md,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        items(state.ideas, key = { it.id }) { idea ->
                            IdeaCard(
                                idea = idea,
                                onClick = { onOpenIdea(idea.id) },
                                actions = ideaActions(
                                    idea = idea,
                                    onSetStatus = { status -> vm.setStatus(idea.id, status) },
                                    onDelete = { onIdeaDeleted(idea.id) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The card menu: mark the idea with any other status, or delete it. */
private fun ideaActions(
    idea: WritingIdeaEntity,
    onSetStatus: (String) -> Unit,
    onDelete: () -> Unit,
): List<MenuAction> {
    val statusActions = IdeaOptions.statuses
        .filter { it != idea.status }
        .map { status ->
            MenuAction(
                label = "Mark as ${IdeaOptions.statusLabel(status)}",
                onClick = { onSetStatus(status) },
            )
        }
    return statusActions + MenuAction(
        label = "Delete",
        onClick = onDelete,
        icon = Icons.Outlined.Delete,
        destructive = true,
    )
}

@Composable
private fun IdeaCard(
    idea: WritingIdeaEntity,
    onClick: () -> Unit,
    actions: List<MenuAction>,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val preview = snippet(idea.body, 140)
    val genre = idea.genre
    // Ideas that have already become stories are shown quieter.
    val muted = idea.status == "used"
    NeriboCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (muted) 0.6f else 1f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    top = spacing.md,
                    bottom = spacing.md,
                    end = spacing.xs,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = idea.title.ifBlank { "Untitled idea" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        text = IdeaOptions.statusLabel(idea.status),
                        tone = IdeaOptions.statusTone(idea.status),
                    )
                    if (genre != null) {
                        Spacer(modifier = Modifier.width(spacing.sm))
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            OverflowMenu(actions = actions)
        }
    }
}

/**
 * The idea editor, shown inside the Writing ideas screen: serif title, genre and status chips
 * and a large body. Autosaves 600ms after the last change and whenever the screen stops. A
 * new idea that is still empty when the editor closes is discarded.
 */
@Composable
private fun IdeaEditorPane(
    ideaId: String,
    editorKey: String,
    onClose: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = "idea:$editorKey") { c ->
        IdeaEditorViewModel(ideaId, c.writingIdeasRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onClose()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onClose()
    }

    val actions = listOf(
        MenuAction(
            label = "Copy idea",
            onClick = {
                val draft = vm.currentDraft
                context.copyToClipboard("Writing idea", composeIdeaText(draft.title, draft.body))
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete idea",
            onClick = { vm.delete(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )
    val saveLabel = when (state.saveStatus) {
        IdeaSaveStatus.Idle -> null
        IdeaSaveStatus.Saving -> "Saving\u2026"
        IdeaSaveStatus.Saved -> "Saved"
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New idea" else "Idea",
                onBack = {
                    vm.flush()
                    onClose()
                },
                subtitle = if (state.isLoaded) saveLabel else null,
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            IdeaEditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun IdeaEditorContent(
    vm: IdeaEditorViewModel,
    state: IdeaEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // The fields keep their own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var title by rememberSaveable { mutableStateOf(vm.currentDraft.title) }
    var body by rememberSaveable { mutableStateOf(vm.currentDraft.body) }
    val titleFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(title)
        vm.onBodyChange(body)
        // Only a brand-new, empty idea opens the keyboard by itself.
        if (vm.isNew && title.isEmpty() && body.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        BasicTextField(
            value = title,
            onValueChange = { value ->
                val cleaned = value.replace('\n', ' ')
                title = cleaned
                vm.onTitleChange(cleaned)
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocus),
            textStyle = titleStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (title.isEmpty()) {
                        Text(
                            text = "A working title, or just a spark",
                            style = titleStyle.copy(color = colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Genre")
        Spacer(modifier = Modifier.height(spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IdeaOptions.genres.forEach { genre ->
                NeriboChip(
                    label = genre,
                    selected = state.genre == genre,
                    onClick = { vm.onGenreToggle(genre) },
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Status")
        Spacer(modifier = Modifier.height(spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IdeaOptions.statuses.forEach { status ->
                NeriboChip(
                    label = IdeaOptions.statusLabel(status),
                    selected = state.status == status,
                    onClick = { vm.onStatusChange(status) },
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.lg))

        BasicTextField(
            value = body,
            onValueChange = { value ->
                body = value
                vm.onBodyChange(value)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp),
            textStyle = bodyStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (body.isEmpty()) {
                        Text(
                            text = "What if a danfo driver in Lagos found a letter meant for someone else?",
                            style = bodyStyle.copy(color = colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(modifier = Modifier.height(spacing.xxxl))
    }
}

/** Recently deleted ideas: Restore, Delete forever, and Empty trash, all with the standard copy. */
@Composable
private fun IdeasTrashPane(vm: WritingIdeasViewModel, onClose: () -> Unit) {
    val ideas by vm.trash.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Recently deleted",
                onBack = onClose,
                actions = {
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
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Items are removed permanently after 30 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
            if (ideas.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = "Nothing here",
                    message = "Ideas you delete wait here for 30 days before they are gone for good.",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = spacing.screen,
                        end = spacing.screen,
                        top = spacing.sm,
                        bottom = spacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    items(ideas, key = { it.id }) { idea ->
                        val deletedAt = idea.deletedAt ?: idea.updatedAt
                        TrashRow(
                            title = idea.title.ifBlank { "Untitled idea" },
                            subtitle = "Deleted ${formatRelative(deletedAt)}",
                            onRestore = { vm.restore(idea.id) },
                            onDeleteForever = { pendingDeleteId = idea.id },
                        )
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = "Delete forever?",
            message = "This idea will be gone for good and cannot be brought back.",
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
            message = "Every idea in Recently deleted will be gone for good.",
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
