package com.westly.neribovault.feature.notes

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
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
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
import androidx.compose.ui.platform.LocalContext
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
import com.westly.neribovault.data.local.entity.NoteEntity
import com.westly.neribovault.feature.notes.components.NoteCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Notes list: scope chips, optional tag chips and search, pinned and other notes,
 * and the Undo snackbar for deletes (including deletes made in the editor).
 */
@Composable
fun NotesScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> NotesViewModel(c.notesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon. Search state lives in the ViewModel and
    // survives navigation, so focusing on "isSearchOpen" alone re-opened the keyboard every
    // time you came back to this screen.
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

    val renderNote: @Composable (NoteEntity) -> Unit = { note ->
        val actions = listOfNotNull(
            if (note.isArchived) {
                null
            } else {
                MenuAction(
                    label = if (note.isPinned) "Unpin" else "Pin",
                    onClick = { vm.togglePinned(note) },
                    icon = Icons.Outlined.PushPin,
                )
            },
            MenuAction(
                label = if (note.isArchived) "Unarchive" else "Archive",
                onClick = {
                    val archive = !note.isArchived
                    vm.setArchived(note.id, archive)
                    scope.launch {
                        showUndo(
                            if (archive) "Note archived" else "Note unarchived",
                            { vm.setArchived(note.id, !archive) },
                        )
                    }
                },
                icon = if (note.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
            ),
            MenuAction(
                label = "Copy",
                onClick = {
                    context.copyToClipboard("Note", note.toPlainText())
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
            MenuAction(
                label = "Share",
                onClick = { context.shareText(note.title.ifBlank { null }, note.toPlainText()) },
                icon = Icons.Outlined.Share,
            ),
            MenuAction(
                label = "Delete",
                onClick = {
                    vm.delete(note.id)
                    scope.launch { showUndo("Moved to Recently deleted", { vm.restore(note.id) }) }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
        NoteCard(
            note = note,
            actions = actions,
            onClick = { onOpenNote(note.id) },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Notes",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search notes",
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
            NeriboFab(onClick = onNewNote, contentDescription = "New note")
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
                    placeholder = "Search notes",
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
                NeriboChip(
                    label = "Notes",
                    selected = state.scope == NotesScope.Notes,
                    onClick = { vm.selectScope(NotesScope.Notes) },
                )
                NeriboChip(
                    label = "Archived",
                    selected = state.scope == NotesScope.Archived,
                    onClick = { vm.selectScope(NotesScope.Archived) },
                )
            }
            if (state.availableTags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.availableTags.forEach { tag ->
                        NeriboChip(
                            label = tag,
                            selected = state.selectedTag == tag,
                            onClick = { vm.toggleTag(tag) },
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> NotesEmptyState(state = state, onNewNote = onNewNote)
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
                            item(key = "header-pinned") { SectionHeader("Pinned") }
                            items(state.pinned, key = { it.id }) { note -> renderNote(note) }
                            if (state.others.isNotEmpty()) {
                                item(key = "header-others") { SectionHeader("Others") }
                            }
                        }
                        items(state.others, key = { it.id }) { note -> renderNote(note) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesEmptyState(state: NotesUiState, onNewNote: () -> Unit) {
    val tag = state.selectedTag
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        tag != null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No notes here are tagged \u201C$tag\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.scope == NotesScope.Archived -> EmptyState(
            icon = Icons.Outlined.Archive,
            title = "Archive is empty",
            message = "Notes you archive rest here, safe and out of the way.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> EmptyState(
            icon = Icons.Outlined.Description,
            title = "Nothing here yet",
            message = "Your first thought is one tap away.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New note",
            onAction = onNewNote,
        )
    }
}
