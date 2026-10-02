package com.westly.neribovault.feature.developer.docs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.core.util.snippet
import kotlinx.coroutines.launch

private const val DOC_TITLE_PLACEHOLDER = "Title"
private const val DOC_BODY_PLACEHOLDER = "Write here"

private const val PROMPT_TITLE_PLACEHOLDER = "Title"
private const val PROMPT_BODY_PLACEHOLDER = "Write your prompt"

private const val UNDO_MESSAGE = "Moved to Recently deleted"

/** Shows [message] with an Undo action and runs [onUndo] if it is tapped. */
private suspend fun showUndoSnackbar(
    hostState: SnackbarHostState,
    message: String,
    onUndo: () -> Unit,
) {
    hostState.currentSnackbarData?.dismiss()
    val result = hostState.showSnackbar(
        message = message,
        actionLabel = "Undo",
        duration = SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) onUndo()
}

// ---------------------------------------------------------------------------------------------
// Docs tab
// ---------------------------------------------------------------------------------------------

/**
 * The Docs tab of a project: DOCUMENTS and PROMPTS, each with its own "new" button. A prompt row
 * has a quick Copy button; a prompt with variables opens the fill-in sheet first.
 */
@Composable
fun ProjectDocsTab(
    projectId: String,
    onOpenDocument: (docId: String) -> Unit,
    onOpenPrompt: (promptId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = neriboViewModel(key = "dev-docs-tab-$projectId") { c ->
        ProjectDocsViewModel(projectId, c.projectDocumentsRepository, c.promptsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copyPrompt = rememberPromptCopier(snackbarHostState)

    // An item deleted from its editor comes back here through DocsDeleteNotice.
    LaunchedEffect(projectId) {
        DocsDeleteNotice.pending.collect { notice ->
            if (notice != null && notice.projectId == projectId) {
                DocsDeleteNotice.clear()
                if (DocsDeleteNotice.isFresh(notice)) {
                    val undo: () -> Unit = when (notice.kind) {
                        DocsDeleteNotice.Kind.Document -> ({ vm.restoreDoc(notice.id) })
                        DocsDeleteNotice.Kind.Prompt -> ({ vm.restorePrompt(notice.id) })
                    }
                    showUndoSnackbar(snackbarHostState, UNDO_MESSAGE, undo)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!state.isLoading) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.xs,
                    bottom = spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "docs-header") { SectionHeader("DOCUMENTS") }
                item(key = "docs-new") {
                    NeriboButton(
                        text = "New document",
                        onClick = { onOpenDocument(DOC_NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Description,
                    )
                }
                if (state.docs.isEmpty()) {
                    item(key = "docs-empty") {
                        Text(
                            text = "No documents yet. A README draft is a good place to start.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.docs, key = { doc -> "doc-${doc.id}" }) { doc ->
                        DocumentCard(
                            doc = doc,
                            actions = listOf(
                                MenuAction(
                                    label = "Open",
                                    onClick = { onOpenDocument(doc.id) },
                                    icon = Icons.Outlined.Edit,
                                ),
                                MenuAction(
                                    label = "Duplicate",
                                    onClick = { vm.duplicateDoc(doc) },
                                    icon = Icons.Outlined.FileCopy,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        vm.deleteDoc(doc.id)
                                        scope.launch {
                                            showUndoSnackbar(snackbarHostState, UNDO_MESSAGE) {
                                                vm.restoreDoc(doc.id)
                                            }
                                        }
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                            onClick = { onOpenDocument(doc.id) },
                        )
                    }
                }
                item(key = "prompts-spacer") { Spacer(modifier = Modifier.height(spacing.sm)) }
                item(key = "prompts-header") { SectionHeader("PROMPTS") }
                item(key = "prompts-new") {
                    NeriboButton(
                        text = "New prompt",
                        onClick = { onOpenPrompt(DOC_NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Code,
                    )
                }
                if (state.prompts.isEmpty()) {
                    item(key = "prompts-empty") {
                        Text(
                            text = "No prompts for this project yet. Save the ones you keep retyping.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.prompts, key = { prompt -> "prompt-${prompt.id}" }) { prompt ->
                        PromptRow(
                            prompt = prompt,
                            actions = listOf(
                                MenuAction(
                                    label = "Open",
                                    onClick = { onOpenPrompt(prompt.id) },
                                    icon = Icons.Outlined.Edit,
                                ),
                                MenuAction(
                                    label = if (prompt.isFavorite) "Remove favorite" else "Favorite",
                                    onClick = { vm.toggleFavorite(prompt.id) },
                                    icon = Icons.Outlined.Star,
                                ),
                                MenuAction(
                                    label = "Duplicate",
                                    onClick = { vm.duplicatePrompt(prompt) },
                                    icon = Icons.Outlined.FileCopy,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        vm.deletePrompt(prompt.id)
                                        scope.launch {
                                            showUndoSnackbar(snackbarHostState, UNDO_MESSAGE) {
                                                vm.restorePrompt(prompt.id)
                                            }
                                        }
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                            onCopy = { copyPrompt(prompt.title, prompt.body) },
                            onClick = { onOpenPrompt(prompt.id) },
                        )
                    }
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

// ---------------------------------------------------------------------------------------------
// Project document editor
// ---------------------------------------------------------------------------------------------

/**
 * Full-screen document editor: serif title, kind chips, a large body and a word count. Autosaves
 * 600ms after the last change and whenever the screen stops. `docId == "new"` creates a document
 * in [projectId].
 */
@Composable
fun ProjectDocEditorScreen(projectId: String, docId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = neriboViewModel(key = "dev-doc-editor-$docId") { c ->
        ProjectDocEditorViewModel(projectId, docId, c.projectDocumentsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
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

    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val actions = listOf(
        MenuAction(
            label = "Copy",
            onClick = {
                context.copyToClipboard("Document", vm.plainText())
                notify("Copied to clipboard")
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share",
            onClick = {
                context.shareText(vm.currentTitle.trim().ifEmpty { null }, vm.plainText())
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Duplicate",
            onClick = {
                vm.duplicate { created ->
                    notify(if (created) "Duplicate created" else "Write something first")
                }
            },
            icon = Icons.Outlined.FileCopy,
        ),
        MenuAction(
            label = "Delete",
            onClick = {
                vm.deleteDoc { deletedId ->
                    if (deletedId != null) {
                        DocsDeleteNotice.post(DocsDeleteNotice.Kind.Document, projectId, deletedId)
                    }
                    onBack()
                }
            },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New document" else "Document",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    DocSaveStatus.Idle -> null
                    DocSaveStatus.Saving -> "Saving\u2026"
                    DocSaveStatus.Saved -> "Saved"
                },
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            ProjectDocEditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun ProjectDocEditorContent(
    vm: ProjectDocEditorViewModel,
    state: ProjectDocEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentBody, TextRange(vm.currentBody.length)))
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
            Spacer(modifier = Modifier.height(spacing.md))
            SectionHeader("Title")
            Spacer(modifier = Modifier.height(spacing.sm))
            DocBorderlessField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) {
                        new.copy(text = new.text.replace('\n', ' '))
                    } else {
                        new
                    }
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                textStyle = titleStyle,
                placeholder = DOC_TITLE_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { bodyFocus.requestFocus() } },
                ),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboDivider()
            Spacer(modifier = Modifier.height(spacing.lg))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DOC_KINDS.forEach { kind ->
                    NeriboChip(
                        label = docKindLabel(kind),
                        selected = state.kind == kind,
                        onClick = { vm.setKind(kind) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("Content")
            Spacer(modifier = Modifier.height(spacing.sm))
            DocBorderlessField(
                value = bodyValue,
                onValueChange = { new ->
                    bodyValue = new
                    vm.onBodyChange(new.text)
                },
                textStyle = bodyStyle,
                placeholder = DOC_BODY_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp)
                    .focusRequester(bodyFocus),
            )
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
        DocEditorFooter(text = wordCountLabel(state.wordCount))
    }
}

// ---------------------------------------------------------------------------------------------
// Prompt editor
// ---------------------------------------------------------------------------------------------

/**
 * Full-screen prompt editor: title, category chips, a project selector, a large monospace-friendly
 * body and a live row of the `{{variables}}` found in it. `promptId == "new"` creates a prompt;
 * [projectId] null means a global prompt (or "no project yet" for a new one).
 */
@Composable
fun PromptEditorScreen(projectId: String?, promptId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "dev-prompt-editor-$promptId") { c ->
        PromptEditorViewModel(projectId, promptId, c.promptsRepository, c.projectsRepository)
    }
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copyPrompt = rememberPromptCopier(snackbarHostState)

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val notify: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val actions = listOf(
        MenuAction(
            label = "Copy",
            onClick = { copyPrompt(vm.currentTitle, vm.currentBody) },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share",
            onClick = {
                if (vm.currentBody.isBlank()) {
                    notify("Nothing to share yet")
                } else {
                    context.shareText(vm.currentTitle.trim().ifEmpty { null }, vm.currentBody)
                }
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Duplicate",
            onClick = {
                vm.duplicate { created ->
                    notify(if (created) "Duplicate created" else "Write something first")
                }
            },
            icon = Icons.Outlined.FileCopy,
        ),
        MenuAction(
            label = "Delete",
            onClick = {
                vm.deletePrompt { deletedId ->
                    if (deletedId != null) {
                        DocsDeleteNotice.post(
                            DocsDeleteNotice.Kind.Prompt,
                            vm.state.value.projectId,
                            deletedId,
                        )
                    }
                    onBack()
                }
            },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New prompt" else "Prompt",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    DocSaveStatus.Idle -> null
                    DocSaveStatus.Saving -> "Saving\u2026"
                    DocSaveStatus.Saved -> "Saved"
                },
                actions = {
                    NeriboIconButton(
                        icon = if (state.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (state.isFavorite) "Remove from favorites" else "Add to favorites",
                        onClick = { vm.toggleFavorite() },
                        tint = if (state.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    OverflowMenu(actions = actions)
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            PromptEditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }
}

@Composable
private fun PromptEditorContent(
    vm: PromptEditorViewModel,
    state: PromptEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val projects by vm.projects.collectAsStateWithLifecycle()
    var showProjectSheet by rememberSaveable { mutableStateOf(false) }

    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentBody, TextRange(vm.currentBody.length)))
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
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        color = colors.onBackground,
    )
    val projectName = projects.firstOrNull { it.id == state.projectId }
        ?.name?.trim()?.ifEmpty { "Untitled project" }

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
            Spacer(modifier = Modifier.height(spacing.md))
            SectionHeader("Title")
            Spacer(modifier = Modifier.height(spacing.sm))
            DocBorderlessField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) {
                        new.copy(text = new.text.replace('\n', ' '))
                    } else {
                        new
                    }
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                textStyle = titleStyle,
                placeholder = PROMPT_TITLE_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { bodyFocus.requestFocus() } },
                ),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboDivider()
            Spacer(modifier = Modifier.height(spacing.lg))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PROMPT_CATEGORIES.forEach { category ->
                    NeriboChip(
                        label = promptCategoryLabel(category),
                        selected = state.category == category,
                        onClick = { vm.setCategory(category) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { showProjectSheet = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PROJECT",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = spacing.md),
                )
                Text(
                    text = projectName ?: if (state.projectId == null) "No project" else "Linked project",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "Choose project",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("Content")
            Spacer(modifier = Modifier.height(spacing.sm))
            DocBorderlessField(
                value = bodyValue,
                onValueChange = { new ->
                    bodyValue = new
                    vm.onBodyChange(new.text)
                },
                textStyle = bodyStyle,
                placeholder = PROMPT_BODY_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp)
                    .focusRequester(bodyFocus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
        NeriboDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .heightIn(min = 48.dp)
                .padding(horizontal = spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.variables.isEmpty()) {
                Text(
                    text = "Wrap a word in {{double braces}} to make it a variable.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            } else {
                Text(
                    text = "VARIABLES",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                state.variables.forEach { name ->
                    StatusBadge(text = "{{" + name + "}}", tone = BadgeTone.Accent)
                }
            }
        }
    }

    if (showProjectSheet) {
        PromptProjectSheet(
            projects = projects,
            selectedId = state.projectId,
            onSelect = { id ->
                vm.setProject(id)
                showProjectSheet = false
            },
            onDismiss = { showProjectSheet = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Prompts library
// ---------------------------------------------------------------------------------------------

/**
 * Every prompt across all projects: search, filter chips (Favorites, All and the categories),
 * a star toggle and a Copy button on each row, and a New prompt button. Favorites come first,
 * then the most recently changed.
 */
@Composable
fun PromptsLibraryScreen(
    onOpenPrompt: (projectId: String?, promptId: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm = neriboViewModel(key = "dev-prompts-library") { c ->
        PromptsLibraryViewModel(c.promptsRepository, c.projectsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val copyPrompt = rememberPromptCopier(snackbarHostState)
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    // A prompt deleted from its editor comes back here through DocsDeleteNotice.
    LaunchedEffect(Unit) {
        DocsDeleteNotice.pending.collect { notice ->
            if (notice != null && notice.kind == DocsDeleteNotice.Kind.Prompt) {
                DocsDeleteNotice.clear()
                if (DocsDeleteNotice.isFresh(notice)) {
                    showUndoSnackbar(snackbarHostState, UNDO_MESSAGE) { vm.restore(notice.id) }
                }
            }
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Prompts",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = Icons.Outlined.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search prompts",
                        onClick = {
                            if (searchOpen) vm.setQuery("")
                            searchOpen = !searchOpen
                        },
                        tint = if (searchOpen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        },
        floatingActionButton = {
            NeriboFab(
                onClick = { onOpenPrompt(null, DOC_NEW_ID) },
                contentDescription = "New prompt",
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searchOpen) {
                NeriboSearchField(
                    query = state.query,
                    onQueryChange = { vm.setQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screen),
                    placeholder = "Search prompts",
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
                    label = "Favorites",
                    selected = state.filter == FILTER_FAVORITES,
                    onClick = { vm.setFilter(FILTER_FAVORITES) },
                )
                NeriboChip(
                    label = "All",
                    selected = state.filter == FILTER_ALL,
                    onClick = { vm.setFilter(FILTER_ALL) },
                )
                PROMPT_CATEGORIES.forEach { category ->
                    NeriboChip(
                        label = promptCategoryLabel(category),
                        selected = state.filter == category,
                        onClick = { vm.setFilter(category) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    state.isLoading -> Unit
                    state.totalCount == 0 -> EmptyState(
                        icon = Icons.Outlined.Code,
                        title = "No prompts yet",
                        message = "Save the prompts you keep retyping, with {{variables}} for the parts that change.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "New prompt",
                        onAction = { onOpenPrompt(null, DOC_NEW_ID) },
                    )
                    state.items.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "Nothing matches",
                        message = "Try another word, or switch the filter.",
                        modifier = Modifier.fillMaxSize(),
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
                        items(state.items, key = { item -> item.prompt.id }) { item ->
                            PromptLibraryCard(
                                item = item,
                                onClick = { onOpenPrompt(item.prompt.projectId, item.prompt.id) },
                                onToggleFavorite = { vm.toggleFavorite(item.prompt.id) },
                                onCopy = { copyPrompt(item.prompt.title, item.prompt.body) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One prompt in the library: star, title, Copy, category, project name and a short preview. */
@Composable
private fun PromptLibraryCard(
    item: PromptListItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val prompt = item.prompt
    val hasTitle = prompt.title.isNotBlank()
    val preview = snippet(prompt.body, 160)
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) prompt.title.trim() else "Untitled prompt",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            NeriboIconButton(
                icon = if (prompt.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (prompt.isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = onToggleFavorite,
                tint = if (prompt.isFavorite) colors.primary else colors.onSurfaceVariant,
            )
            NeriboIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copy prompt",
                onClick = onCopy,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusBadge(text = promptCategoryLabel(prompt.category))
            val projectName = item.projectName
            Text(
                text = if (projectName != null) {
                    projectName + " \u00B7 " + formatRelative(prompt.updatedAt)
                } else {
                    formatRelative(prompt.updatedAt)
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}
