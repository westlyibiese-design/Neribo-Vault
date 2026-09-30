package com.westly.neribovault.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.countWords
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.feature.notes.components.TagEditor
import kotlinx.coroutines.launch

private const val TITLE_PLACEHOLDER = "Title"
private const val BODY_PLACEHOLDER =
    "Start writing. A verse, a to-do list, the plan for Saturday's owambe..."

/**
 * Full-screen note editor: borderless serif title, body, tags and a slim footer.
 * Autosaves 600ms after the last change and whenever the screen stops. No Save button.
 */
@Composable
fun NoteEditorScreen(
    noteId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = noteId) { c -> NoteEditorViewModel(noteId, c.notesRepository) }
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

    val actions = listOfNotNull(
        if (state.isArchived) {
            null
        } else {
            MenuAction(
                label = if (state.isPinned) "Unpin" else "Pin",
                onClick = { vm.togglePinned() },
                icon = Icons.Outlined.PushPin,
            )
        },
        MenuAction(
            label = if (state.isArchived) "Unarchive" else "Archive",
            onClick = {
                val nowArchived = !state.isArchived
                vm.toggleArchived()
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        if (nowArchived) "Note archived" else "Note unarchived",
                    )
                }
            },
            icon = if (state.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
        ),
        MenuAction(
            label = "Copy note",
            onClick = {
                context.copyToClipboard("Note", composeNoteText(vm.currentTitle, vm.currentBody))
                scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share note",
            onClick = {
                context.shareText(
                    vm.currentTitle.ifBlank { null },
                    composeNoteText(vm.currentTitle, vm.currentBody),
                )
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete",
            onClick = { vm.deleteNote(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New note" else "Note",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = subtitleFor(state),
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            EditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

private fun subtitleFor(state: NoteEditorUiState): String? {
    val status = when (state.saveStatus) {
        SaveStatus.Idle -> null
        SaveStatus.Saving -> "Saving\u2026"
        SaveStatus.Saved -> "Saved"
    }
    return listOfNotNull(if (state.isArchived) "Archived" else null, status)
        .joinToString(" \u00B7 ")
        .ifEmpty { null }
}

@Composable
private fun EditorContent(
    vm: NoteEditorViewModel,
    state: NoteEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // The two fields keep their own text so typing is never delayed; every change is also
    // sent to the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle))
    }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentBody))
    }
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onBodyChange(bodyValue.text)
        if (vm.isNew && titleValue.text.isEmpty() && bodyValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val wordCount = remember(bodyValue.text) { countWords(bodyValue.text) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
        ) {
            BasicTextField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                textStyle = titleStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { bodyFocus.requestFocus() } },
                ),
                maxLines = 4,
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (titleValue.text.isEmpty()) {
                            Text(
                                text = TITLE_PLACEHOLDER,
                                style = titleStyle.copy(color = colors.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            BasicTextField(
                value = bodyValue,
                onValueChange = { new ->
                    bodyValue = new
                    vm.onBodyChange(new.text)
                },
                modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
                textStyle = bodyStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                // Tapping the empty space below the text puts the cursor at the end.
                                bodyValue = bodyValue.copy(selection = TextRange(bodyValue.text.length))
                                runCatching { bodyFocus.requestFocus() }
                            },
                    ) {
                        if (bodyValue.text.isEmpty()) {
                            Text(
                                text = BODY_PLACEHOLDER,
                                style = bodyStyle.copy(color = colors.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.height(spacing.md))
            TagEditor(
                tags = state.tags,
                onAddTag = { vm.addTag(it) },
                onRemoveTag = { vm.removeTag(it) },
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }
        EditorFooter(wordCount = wordCount, updatedAt = state.updatedAt)
    }
}

@Composable
private fun EditorFooter(wordCount: Int, updatedAt: Long?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column {
        NeriboDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (wordCount == 1) "1 word" else "$wordCount words",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (updatedAt != null) {
                Text(
                    text = "Edited ${formatRelative(updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
