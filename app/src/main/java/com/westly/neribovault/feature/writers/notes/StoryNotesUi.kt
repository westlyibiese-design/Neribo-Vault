package com.westly.neribovault.feature.writers.notes

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.StoryNoteEntity
import kotlinx.coroutines.launch

private const val NEW_ID = "new"

/**
 * The Notes tab of a story: an Add note button, category filter chips, and one card per note
 * (title, two-line preview, category and when it changed). Deleting shows an Undo snackbar.
 */
@Composable
fun StoryNotesTab(
    storyId: String,
    onOpenNote: (noteId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = neriboViewModel(key = "notes:$storyId") { c ->
        StoryNotesTabViewModel(storyId, c.storyNotesRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showUndo: suspend (String) -> Unit = { id ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = "Moved to Recently deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.restore(id)
    }

    // A note deleted inside the editor is announced here once this tab is back on screen.
    LaunchedEffect(Unit) {
        NoteUndoBus.deletedId.collect { id ->
            if (id != null) {
                NoteUndoBus.deletedId.value = null
                showUndo(id)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> Unit
            state.totalCount == 0 -> EmptyState(
                icon = Icons.Outlined.Edit,
                title = "No notes yet",
                message = "Keep research, plot beats and settings here so the story stays consistent.",
                modifier = Modifier.fillMaxSize(),
                actionLabel = "Add note",
                onAction = { onOpenNote(NEW_ID) },
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
                item(key = "add-note") {
                    NeriboButton(
                        text = "Add note",
                        onClick = { onOpenNote(NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Add,
                    )
                }
                item(key = "note-filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NoteFilter.values().forEach { option ->
                            NeriboChip(
                                label = option.label,
                                selected = state.filter == option,
                                onClick = { vm.selectFilter(option) },
                            )
                        }
                    }
                }
                if (state.notes.isEmpty()) {
                    item(key = "no-matches") {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = "No matches",
                            message = "No ${state.filter.label.lowercase()} notes in this story yet.",
                        )
                    }
                }
                items(state.notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        actions = listOf(
                            MenuAction(
                                label = "Edit",
                                onClick = { onOpenNote(note.id) },
                                icon = Icons.Outlined.Edit,
                            ),
                            MenuAction(
                                label = "Delete",
                                onClick = {
                                    vm.delete(note.id)
                                    scope.launch { showUndo(note.id) }
                                },
                                icon = Icons.Outlined.Delete,
                                destructive = true,
                            ),
                        ),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(spacing.lg),
        ) { data ->
            Snackbar(snackbarData = data, shape = MaterialTheme.shapes.medium)
        }
    }
}

@Composable
private fun NoteCard(
    note: StoryNoteEntity,
    onClick: () -> Unit,
    actions: List<MenuAction>,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val preview = snippet(note.body, 160)
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
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
                    text = note.title.ifBlank { "Untitled note" },
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
                    StatusBadge(text = NoteCategories.label(note.category))
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = formatRelative(note.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            OverflowMenu(actions = actions)
        }
    }
}

/**
 * The story note editor: title in the serif style, category chips and a large body. Autosaves
 * 600ms after the last change and whenever the screen stops. `noteId == "new"` creates a note;
 * an empty new one is discarded.
 */
@Composable
fun StoryNoteEditorScreen(
    storyId: String,
    noteId: String,
    onBack: () -> Unit,
) {
    val vm = neriboViewModel(key = "note:$storyId:$noteId") { c ->
        StoryNoteEditorViewModel(storyId, noteId, c.storyNotesRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val actions = listOf(
        MenuAction(
            label = "Copy",
            onClick = {
                val draft = vm.currentDraft
                context.copyToClipboard("Story note", composeNoteText(draft.title, draft.body))
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share",
            onClick = {
                val draft = vm.currentDraft
                val title = draft.title.trim().ifBlank { null }
                context.shareText(title, composeNoteText(draft.title, draft.body))
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete note",
            onClick = { vm.delete(onDone = onBack) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )
    val saveLabel = when (state.saveStatus) {
        NoteSaveStatus.Idle -> null
        NoteSaveStatus.Saving -> "Saving\u2026"
        NoteSaveStatus.Saved -> "Saved"
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New note" else "Story note",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = if (state.isLoaded) saveLabel else null,
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            NoteEditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun NoteEditorContent(
    vm: StoryNoteEditorViewModel,
    state: StoryNoteEditorUiState,
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
        // Only a brand-new, empty note opens the keyboard by itself.
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
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (title.isEmpty()) {
                        Text(
                            text = "Note title, e.g. Onitsha market in the 1990s",
                            style = titleStyle.copy(color = colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Category")
        Spacer(modifier = Modifier.height(spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteCategories.all.forEach { category ->
                NeriboChip(
                    label = NoteCategories.label(category),
                    selected = state.category == category,
                    onClick = { vm.onCategoryChange(category) },
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
                .heightIn(min = 320.dp),
            textStyle = bodyStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (body.isEmpty()) {
                        Text(
                            text = "Plot beats, a place to remember, research from the library in Benin City\u2026",
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
