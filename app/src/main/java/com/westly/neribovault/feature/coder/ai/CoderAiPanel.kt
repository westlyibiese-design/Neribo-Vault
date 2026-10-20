package com.westly.neribovault.feature.coder.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.feature.coder.GuidanceMode
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.guidance.CoderTipHost
import com.westly.neribovault.feature.coder.guidance.TipContext
import com.westly.neribovault.feature.coder.guidance.TipScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CODE_FONT_SIZE = 12.sp
private val CODE_LINE_HEIGHT = 17.sp
private const val MAX_VISIBLE_CODE_LINES = 400
private const val MESSAGE_APPLIED = "Done. Use Undo in the editor if you don't like it."
private const val SAVE_CONFIRM_TIMEOUT_MS = 5_000L

/**
 * The AI help screen of one project: the chat, the context chips, the quick buttons and the input.
 * It sits in place of the editor, file list or preview. Code from an answer is applied only when the
 * person taps a button, and every apply is one undo step in the editor ([onInsertAtCursor],
 * [onReplaceSelection], [onReplaceAll]) or a new file ([onCreateFile]). Applying never closes the screen.
 * [editorValue] holds the open file's current text; it is empty when no file is open. The selection comes
 * from [selectionSnapshot], taken when the AI help button was tapped, because the editor loses its selection
 * as soon as it leaves the screen. [onReplaceSelection] replaces exactly the snapshot's range. [onOpenAndReplace]
 * saves the open file, opens another one and replaces all of its text; it returns false when that file could not be opened.
 * An answer with several files shows a file plan: [onApplyPlan] writes the checked files with a backup (after the person
 * confirmed) and [onUndoBuild] undoes one build by its backup id. Both are done by the workspace.
 */
@Composable
fun CoderAiPanel(
    viewModel: CoderAiViewModel,
    projectId: String,
    projectName: String,
    entries: List<CoderEntry>,
    openPath: String?,
    editorValue: TextFieldValue,
    selectionSnapshot: AiSelectionSnapshot?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onInsertAtCursor: (String) -> Unit,
    onReplaceSelection: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onOpenAndReplace: suspend (path: String, text: String) -> Boolean,
    onCreateFile: (path: String, text: String) -> Unit,
    onApplyPlan: suspend (List<CoderApplyItem>) -> CoderApplyResult,
    onUndoBuild: suspend (backupId: String) -> CoderUndoResult,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val linked by viewModel.linked.collectAsStateWithLifecycle()
    val notesOn by viewModel.notesOn.collectAsStateWithLifecycle()
    val guidanceMode by viewModel.guidanceMode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val existingPaths = remember(entries) { entries.map { it.path }.toSet() }
    val existingFiles = remember(entries) { entries.filter { !it.isDirectory }.map { it.path }.toSet() }
    val currentText = editorValue.text
    // The snapshot counts only while the same file is open and the text under it is unchanged.
    val activeSnapshot = remember(selectionSnapshot, openPath, currentText) {
        selectionSnapshot?.takeIf { aiSnapshotApplies(it, openPath, currentText) }
    }
    val selection = activeSnapshot?.text
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var replaceCode by remember { mutableStateOf<String?>(null) }
    var openReplace by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var confirmPlanId by remember { mutableStateOf<Long?>(null) }

    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }
    val workspaceContext: () -> CoderAiWorkspaceContext = {
        CoderAiWorkspaceContext(
            projectName = projectName,
            filePaths = entries.filter { !it.isDirectory }.map { it.path },
            openPath = openPath,
            openText = if (openPath != null) editorValue.text else null,
            selection = selection,
        )
    }

    // The workspace reports file creation through its own message; once the new file shows up in the list, say so here.
    LaunchedEffect(existingPaths, pendingSave) {
        val path = pendingSave
        if (path != null && path in existingPaths) {
            pendingSave = null
            showMessage("Saved $path")
        }
    }
    LaunchedEffect(pendingSave) {
        if (pendingSave != null) {
            delay(SAVE_CONFIRM_TIMEOUT_MS)
            pendingSave = null
        }
    }

    val apply = ApplyOptions(
        openPath = openPath,
        hasSelection = selection != null,
        existingPaths = existingPaths,
        existingFiles = existingFiles,
        onCopy = { code ->
            clipboard.setText(AnnotatedString(code))
            showMessage("Copied")
        },
        onInsert = { code ->
            onInsertAtCursor(code)
            showMessage(MESSAGE_APPLIED)
        },
        onReplaceSelection = { code ->
            onReplaceSelection(code)
            showMessage(MESSAGE_APPLIED)
        },
        onReplaceFile = { code -> replaceCode = code },
        onOpenAndReplace = { path, code -> openReplace = path to code },
        onSaveNew = { path, code ->
            pendingSave = path
            onCreateFile(path, code)
        },
        onTogglePlan = { id, path -> viewModel.togglePlanFile(id, path) },
        onApplyPlan = { id -> confirmPlanId = id },
        onCopyPlan = { plan ->
            clipboard.setText(AnnotatedString(CoderApplyPlan.copyAllText(plan.rows)))
            showMessage("Copied")
        },
        onUndoPlan = { id -> viewModel.undoPlan(id, onUndoBuild) },
    )

    val readySetup = setup as? CoderAiSetup.Ready
    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "AI help",
                subtitle = readySetup?.let { it.provider.displayName + " \u00B7 " + it.model },
                onBack = onBack,
                actions = {
                    if (readySetup != null) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Add,
                            contentDescription = "New chat",
                            onClick = { if (state.messages.isNotEmpty()) showNewChat = true },
                            tint = if (state.messages.isNotEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when (val current = setup) {
            CoderAiSetup.Loading -> LoadingState(modifier = Modifier.padding(padding))
            CoderAiSetup.NotSetUp -> EmptyState(
                icon = Icons.Outlined.Lightbulb,
                title = "AI isn't set up yet",
                message = "Add your own key from Claude, Gemini or OpenAI to get help. The editor works fine without it.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Set up AI",
                onAction = onOpenAiSettings,
            )
            is CoderAiSetup.Ready -> ChatBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
                ready = current,
                state = state,
                openPath = openPath,
                hasSelection = selection != null,
                linked = linked,
                notesOn = notesOn,
                ideasVisible = state.messages.isEmpty() && existingFiles.isNotEmpty() && guidanceMode == GuidanceMode.Beginner,
                dialogOpen = showPicker || replaceCode != null || openReplace != null || showNewChat || confirmPlanId != null,
                apply = apply,
                onInput = { viewModel.setInput(it) },
                onIdea = { viewModel.setInput(it) },
                onToggleOpenFile = { viewModel.setIncludeOpenFile(!state.includeOpenFile) },
                onRemoveError = { viewModel.removeAttachedError() },
                onToggleNotes = { viewModel.setNotesOn(!notesOn) },
                onAddFiles = { showPicker = true },
                onQuick = { viewModel.quick(it, workspaceContext()) },
                onSend = { viewModel.send(state.input, workspaceContext()) },
                onStop = { viewModel.stop() },
                onRetry = { viewModel.retry() },
                onOpenAiSettings = onOpenAiSettings,
            )
        }
    }

    if (showPicker) {
        CoderAiFilePicker(
            projectId = projectId,
            entries = entries,
            selected = state.extraFiles,
            onConfirm = {
                viewModel.setExtraFiles(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
    val codeToReplace = replaceCode
    if (codeToReplace != null) {
        ConfirmDialog(
            title = "Replace the whole file?",
            message = "Replace the whole file? You can undo this.",
            confirmLabel = "Replace",
            onConfirm = {
                replaceCode = null
                onReplaceAll(codeToReplace)
                showMessage(MESSAGE_APPLIED)
            },
            onDismiss = { replaceCode = null },
            destructive = true,
        )
    }
    val pendingReplace = openReplace
    if (pendingReplace != null) {
        ConfirmDialog(
            title = "Replace ${pendingReplace.first}?",
            message = "The file will open in the editor and its content will be replaced. You can undo this in the editor.",
            confirmLabel = "Replace",
            onConfirm = {
                openReplace = null
                scope.launch {
                    if (onOpenAndReplace(pendingReplace.first, pendingReplace.second)) showMessage(MESSAGE_APPLIED)
                }
            },
            onDismiss = { openReplace = null },
            destructive = true,
        )
    }
    val planId = confirmPlanId
    val planToApply = if (planId == null) null else state.messages.firstOrNull { it.id == planId }?.plan
    if (planId != null && planToApply != null) {
        val counts = CoderApplyPlan.counts(planToApply)
        ConfirmDialog(
            title = "Apply the AI's files?",
            message = CoderApplyPlan.confirmText(counts.first, counts.second),
            confirmLabel = "Apply",
            onConfirm = {
                confirmPlanId = null
                viewModel.applyPlan(planId, onApplyPlan)
            },
            onDismiss = { confirmPlanId = null },
        )
    }
    if (showNewChat) {
        ConfirmDialog(
            title = "Start a new chat?",
            message = "This clears the conversation on this screen.",
            confirmLabel = "New chat",
            onConfirm = {
                showNewChat = false
                viewModel.newChat()
            },
            onDismiss = { showNewChat = false },
            destructive = true,
        )
    }
}

/** What the code cards need to know to offer the right buttons. */
private class ApplyOptions(
    val openPath: String?,
    val hasSelection: Boolean,
    val existingPaths: Set<String>,
    val existingFiles: Set<String>,
    val onCopy: (String) -> Unit,
    val onInsert: (String) -> Unit,
    val onReplaceSelection: (String) -> Unit,
    val onReplaceFile: (String) -> Unit,
    val onOpenAndReplace: (String, String) -> Unit,
    val onSaveNew: (String, String) -> Unit,
    val onTogglePlan: (Long, String) -> Unit,
    val onApplyPlan: (Long) -> Unit,
    val onCopyPlan: (CoderFilePlan) -> Unit,
    val onUndoPlan: (Long) -> Unit,
)

@Composable
private fun ChatBody(
    modifier: Modifier,
    ready: CoderAiSetup.Ready,
    state: CoderAiUiState,
    openPath: String?,
    hasSelection: Boolean,
    linked: Boolean,
    notesOn: Boolean,
    ideasVisible: Boolean,
    dialogOpen: Boolean,
    apply: ApplyOptions,
    onInput: (String) -> Unit,
    onIdea: (String) -> Unit,
    onToggleOpenFile: () -> Unit,
    onRemoveError: () -> Unit,
    onToggleNotes: () -> Unit,
    onAddFiles: () -> Unit,
    onQuick: (CoderQuickAction) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        CoderTipHost(
            screen = TipScreen.Ai,
            context = TipContext(
                screen = TipScreen.Ai,
                aiPanelIdle = !state.isSending,
                busy = state.isSending || dialogOpen,
            ),
            modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.xs),
        )
        MessageList(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = state,
            apply = apply,
            onRetry = onRetry,
            onOpenAiSettings = onOpenAiSettings,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (openPath != null) {
                NeriboChip(
                    label = "Open file: " + CoderPaths.nameOf(openPath),
                    selected = state.includeOpenFile,
                    onClick = onToggleOpenFile,
                )
            }
            if (hasSelection) {
                NeriboChip(label = "Selected text", selected = true, onClick = {})
            }
            if (state.errorAttached) {
                NeriboChip(label = "Error attached (tap to remove)", selected = true, onClick = onRemoveError)
            }
            val extraCount = state.extraFiles.size
            NeriboChip(
                label = if (extraCount == 0) "Add files\u2026" else "Add files\u2026 ($extraCount)",
                selected = extraCount > 0,
                onClick = onAddFiles,
            )
            if (linked) {
                NeriboChip(label = "Project notes", selected = notesOn, onClick = onToggleNotes)
            }
        }
        if (linked && notesOn) {
            Text(
                text = "Titles and text of your project documents are sent to ${ready.provider.company}.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
        }
        val quickEnabled = (openPath != null || hasSelection) && !state.isSending
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (action in CoderQuickAction.values()) {
                QuickChip(label = action.label, enabled = quickEnabled, onClick = { onQuick(action) })
            }
        }
        if (ideasVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (idea in CODER_AI_IDEAS) {
                    NeriboChip(label = idea, selected = false, onClick = { onIdea(idea) })
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                NeriboTextField(
                    value = state.input,
                    onValueChange = onInput,
                    placeholder = "Ask in plain words, for example: make the button red",
                    singleLine = false,
                    minLines = 1,
                    maxLines = 5,
                )
            }
            if (state.isSending) {
                NeriboButton(text = "Stop", onClick = onStop, style = ButtonStyle.Secondary)
            } else {
                NeriboButton(text = "Send", onClick = onSend, enabled = state.input.isNotBlank())
            }
        }
        val note = "Your open file and request are sent to ${ready.provider.company}." +
            if (state.shortenedNotice) " Some of your file was shortened to fit." else ""
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm),
        )
    }
}

/** A quick-action chip that looks and behaves disabled when [enabled] is false. */
@Composable
private fun QuickChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    NeriboChip(
        label = label,
        selected = false,
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { if (!enabled) disabled() },
    )
}

// ---- Message list --------------------------------------------------------------------------

private sealed interface ChatRow {
    val key: String
}

private class UserRow(val message: CoderChatMessage, override val key: String) : ChatRow
private class ProgressRow(val message: CoderChatMessage, override val key: String) : ChatRow
private class TextRow(val text: String, override val key: String) : ChatRow
private class CodeRow(val block: CoderCodeBlock, override val key: String) : ChatRow
private class OpenCodeRow(val block: OpenBlock, override val key: String) : ChatRow
private class NoticeRow(val text: String, override val key: String) : ChatRow
private class PlanItemRow(val messageId: Long, val plan: CoderFilePlan, override val key: String) : ChatRow
private class InterruptedRow(val text: String, val isLast: Boolean, override val key: String) : ChatRow

/** The rows of the list and, for each message id, the index of its first row. */
private class BuiltRows(val rows: List<ChatRow>, val firstIndex: Map<Long, Int>)

private fun buildRows(messages: List<CoderChatMessage>): BuiltRows {
    val rows = ArrayList<ChatRow>()
    val firstIndex = HashMap<Long, Int>()
    val lastId = messages.lastOrNull()?.id
    for (message in messages) {
        firstIndex[message.id] = rows.size
        if (message.role == ChatRole.User) {
            rows.add(UserRow(message, "u${message.id}"))
            continue
        }
        val progress = message.stage
        if (progress != null) rows.add(ProgressRow(message, "a${message.id}-progress"))
        val contentStart = rows.size
        val segments = if (message.segments.isEmpty() && message.openBlock == null && message.text.isNotBlank()) {
            parseReply(message.text)
        } else {
            message.segments
        }
        segments.forEachIndexed { index, segment ->
            val key = "a${message.id}-$index"
            when (segment) {
                is CoderReplySegment.Text -> rows.add(TextRow(segment.text, key))
                is CoderReplySegment.Code -> rows.add(CodeRow(segment.block, key))
            }
        }
        val open = message.openBlock
        if (open != null) rows.add(OpenCodeRow(open, "a${message.id}-open"))
        val notice = message.notice
        if (message.interrupted) {
            rows.add(InterruptedRow(notice ?: "The answer broke off.", message.id == lastId, "a${message.id}-broke"))
        } else {
            if (message.truncated && notice == null) rows.add(NoticeRow("The answer was cut short.", "a${message.id}-cut"))
            if (notice != null) rows.add(NoticeRow(notice, "a${message.id}-notice"))
        }
        val ended = progress == null || CoderAiStages.isTerminal(progress.stage)
        if (ended && rows.size == contentStart && !message.stopped && !message.interrupted) {
            rows.add(TextRow("(No text came back.)", "a${message.id}-empty"))
        }
        val plan = message.plan
        if (plan != null) rows.add(PlanItemRow(message.id, plan, "a${message.id}-plan"))
    }
    return BuiltRows(rows, firstIndex)
}

/** A scroll distance larger than any list, used to reach the end of the list. */
private const val SCROLL_TO_END_PX = 100_000f
private const val MAX_LIVE_CODE_LINES = 14

@Composable
private fun MessageList(
    modifier: Modifier,
    state: CoderAiUiState,
    apply: ApplyOptions,
    onRetry: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val built = remember(state.messages) { buildRows(state.messages) }
    val lastId = state.messages.lastOrNull()?.id
    // The list follows the end of the answer only while the person has not scrolled up.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.isSending) { if (state.isSending) follow = true }
    LaunchedEffect(listState) {
        var previous = 0L
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (scrolling, index, offset) ->
            val position = index.toLong() * 1_000_000L + offset
            if (scrolling && position < previous) follow = false
            previous = position
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { if (!it) follow = true }
    }
    LaunchedEffect(state.messages, follow) {
        if (follow) listState.scrollBy(SCROLL_TO_END_PX)
    }
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (built.rows.isEmpty() && !state.isSending) {
                item(key = "hint") {
                    Text(
                        text = "Ask a question about your code, or tap Explain, Fix, Improve or Add comments.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(built.rows, key = { it.key }) { row ->
                when (row) {
                    is UserRow -> UserBubble(
                        message = row.message,
                        isLast = row.message.id == lastId,
                        failureKind = state.failureKind,
                        onRetry = onRetry,
                        onOpenAiSettings = onOpenAiSettings,
                    )
                    is ProgressRow -> ProgressRowContent(row.message)
                    is TextRow -> Text(
                        text = row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is CodeRow -> CodeBlockCard(block = row.block, apply = apply)
                    is OpenCodeRow -> OpenCodeCard(block = row.block)
                    is NoticeRow -> Text(
                        text = row.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    is PlanItemRow -> FilePlanCard(
                        plan = row.plan,
                        onToggle = { apply.onTogglePlan(row.messageId, it) },
                        onApply = { apply.onApplyPlan(row.messageId) },
                        onCopyAll = { apply.onCopyPlan(row.plan) },
                        onUndo = { apply.onUndoPlan(row.messageId) },
                    )
                    is InterruptedRow -> Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = row.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (row.isLast) NeriboButton(text = "Retry", onClick = onRetry, style = ButtonStyle.Text)
                    }
                }
            }
        }
        if (state.isSending && !follow) {
            NeriboChip(
                label = "Jump to latest",
                selected = true,
                onClick = {
                    follow = true
                    scope.launch { listState.scrollBy(SCROLL_TO_END_PX) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = spacing.sm),
            )
        }
    }
}

/** The progress card (or, once the answer ended, its one-line summary) of an assistant message. */
@Composable
private fun ProgressRowContent(message: CoderChatMessage) {
    val progress = message.stage ?: return
    var files = 0
    var others = 0
    for (segment in message.segments) {
        if (segment is CoderReplySegment.Code && !segment.block.unfinished) {
            if (segment.block.path != null) files++ else others++
        }
    }
    val open = message.openBlock
    AiProgressCard(
        progress = progress,
        writingTarget = open?.let { it.path ?: "code" },
        filesWritten = files,
        otherBlocks = others,
        elapsedMillis = message.elapsedMillis,
    )
}

/** The card of a code block that is still being written: its name, a thin moving line and the last lines so far. */
@Composable
private fun OpenCodeCard(block: OpenBlock) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val path = block.path
    val title = if (path != null) "Writing $path\u2026" else "Writing code\u2026"
    val shown = remember(block.codeSoFar) {
        val all = block.codeSoFar.split('\n')
        all.takeLast(MAX_LIVE_CODE_LINES).joinToString("\n") to all.size
    }
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.md, end = spacing.md, top = spacing.sm),
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                .height(2.dp),
            color = colors.primary,
            trackColor = colors.surfaceVariant,
        )
        if (shown.first.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.sm)
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.surfaceVariant)
                    .horizontalScroll(rememberScrollState())
                    .padding(spacing.sm),
            ) {
                Text(
                    text = shown.first,
                    color = colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = CODE_FONT_SIZE,
                    lineHeight = CODE_LINE_HEIGHT,
                    softWrap = false,
                )
            }
            Text(
                text = "${shown.second} lines so far",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = spacing.md, end = spacing.md, bottom = spacing.sm),
            )
        }
    }
}

@Composable
private fun UserBubble(
    message: CoderChatMessage,
    isLast: Boolean,
    failureKind: CoderChatFailure?,
    onRetry: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(colors.primaryContainer)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer,
            )
        }
        if (message.failed) {
            Text(
                text = "Not sent",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                modifier = Modifier.padding(top = spacing.xs),
            )
            val reason = message.notice
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
            if (isLast) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (failureKind == CoderChatFailure.InvalidKey || failureKind == CoderChatFailure.ModelNotFound) {
                        NeriboButton(text = "Open AI settings", onClick = onOpenAiSettings, style = ButtonStyle.Text)
                    }
                    NeriboButton(text = "Retry", onClick = onRetry, style = ButtonStyle.Text)
                }
            }
        } else {
            val note = message.notice
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

// ---- Code blocks ---------------------------------------------------------------------------

@Composable
private fun CodeBlockCard(block: CoderCodeBlock, apply: ApplyOptions) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lines = remember(block.code) { block.code.split('\n') }
    val tooLong = lines.size > MAX_VISIBLE_CODE_LINES
    val shown = remember(block.code, expanded) {
        if (tooLong && !expanded) lines.take(MAX_VISIBLE_CODE_LINES).joinToString("\n") else block.code
    }
    val label = listOfNotNull(block.language.takeIf { it.isNotEmpty() }, block.path).joinToString(" \u00B7 ")
    val title = label.ifEmpty { "Code" } + if (block.unfinished) " (unfinished)" else ""
    val path = block.path
    // A block that never got its closing fence is incomplete: it can be copied but never applied.
    val buttons = if (block.unfinished) {
        AiApplyButtons(
            insertAtCursor = false,
            replaceSelection = false,
            replaceWholeFile = false,
            saveAsNewFile = false,
            openAndReplace = false,
            nameTaken = false,
        )
    } else {
        aiApplyButtonsFor(path, apply.openPath, apply.hasSelection, apply.existingPaths, apply.existingFiles)
    }

    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.md, end = spacing.md, top = spacing.sm),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm)
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceVariant)
                .horizontalScroll(rememberScrollState())
                .padding(spacing.sm),
        ) {
            Text(
                text = shown,
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = CODE_FONT_SIZE,
                lineHeight = CODE_LINE_HEIGHT,
                softWrap = false,
            )
        }
        if (tooLong) {
            NeriboButton(
                text = if (expanded) "Show less" else "Show all ${lines.size} lines",
                onClick = { expanded = !expanded },
                style = ButtonStyle.Text,
                modifier = Modifier.padding(horizontal = spacing.xs),
            )
        }
        Column(modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.xs)) {
            NeriboButton(text = "Copy", onClick = { apply.onCopy(block.code) }, style = ButtonStyle.Text)
            if (buttons.insertAtCursor) {
                NeriboButton(text = "Insert at cursor", onClick = { apply.onInsert(block.code) }, style = ButtonStyle.Text)
            }
            if (buttons.replaceSelection) {
                NeriboButton(
                    text = "Replace selection",
                    onClick = { apply.onReplaceSelection(block.code) },
                    style = ButtonStyle.Text,
                )
            }
            if (buttons.replaceWholeFile) {
                NeriboButton(
                    text = "Replace whole file",
                    onClick = { apply.onReplaceFile(block.code) },
                    style = ButtonStyle.Text,
                )
            }
            if (buttons.saveAsNewFile && path != null) {
                NeriboButton(
                    text = "Save as new file",
                    onClick = { apply.onSaveNew(path, block.code) },
                    style = ButtonStyle.Text,
                )
            }
            if (buttons.openAndReplace && path != null) {
                NeriboButton(
                    text = "Open and replace " + CoderPaths.nameOf(path),
                    onClick = { apply.onOpenAndReplace(path, block.code) },
                    style = ButtonStyle.Text,
                )
            }
            if (buttons.nameTaken && path != null) {
                Text(
                    text = "$path already exists",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                )
            }
        }
    }
}

/**
 * The text that was selected in the open file [path] when the AI help button was tapped. [start] is inclusive
 * and [end] exclusive, both counted in the file's text at that moment.
 */
data class AiSelectionSnapshot(val path: String, val start: Int, val end: Int, val text: String)

/**
 * The selection of [value] as a snapshot, or null when no file is open, the selection is collapsed or only
 * blank text is selected. A selection made backwards is turned around. Pure.
 */
fun aiSelectionSnapshotOf(path: String?, value: TextFieldValue): AiSelectionSnapshot? {
    if (path == null) return null
    val range = value.selection
    if (range.collapsed) return null
    val start = range.min.coerceIn(0, value.text.length)
    val end = range.max.coerceIn(start, value.text.length)
    val piece = value.text.substring(start, end)
    return if (piece.isBlank()) null else AiSelectionSnapshot(path, start, end, piece)
}

/** True while [snapshot] still describes the open file: the same file is open and the text under the range is unchanged. Pure. */
fun aiSnapshotApplies(snapshot: AiSelectionSnapshot, openPath: String?, currentText: String): Boolean =
    snapshot.path == openPath &&
        snapshot.start >= 0 &&
        snapshot.end <= currentText.length &&
        currentText.substring(snapshot.start, snapshot.end) == snapshot.text

/** Which apply buttons one code block of an answer gets. */
data class AiApplyButtons(
    val insertAtCursor: Boolean,
    val replaceSelection: Boolean,
    val replaceWholeFile: Boolean,
    val saveAsNewFile: Boolean,
    val openAndReplace: Boolean,
    /** True when the block's path is taken by a folder, so no button fits and a short note is shown instead. */
    val nameTaken: Boolean,
)

/**
 * Decides the buttons of a code block from what is open, selected and in the project. [existingPaths] holds
 * every file and folder; [existingFiles] only the files. A block for another existing file offers
 * "Open and replace"; a block for a new path offers "Save as new file". Pure.
 */
fun aiApplyButtonsFor(
    blockPath: String?,
    openPath: String?,
    hasSelection: Boolean,
    existingPaths: Set<String>,
    existingFiles: Set<String>,
): AiApplyButtons {
    val path = blockPath?.takeIf { CoderPaths.isValidPath(it) }
    val isOpenFile = path != null && path == openPath
    return AiApplyButtons(
        insertAtCursor = openPath != null,
        replaceSelection = hasSelection,
        replaceWholeFile = openPath != null && (path == null || isOpenFile),
        saveAsNewFile = path != null && path !in existingPaths,
        openAndReplace = path != null && !isOpenFile && path in existingFiles,
        nameTaken = path != null && !isOpenFile && path in existingPaths && path !in existingFiles,
    )
}
