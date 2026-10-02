package com.westly.neribovault.feature.developer.bugs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.BugEntity
import kotlinx.coroutines.launch

private const val TITLE_PLACEHOLDER = "Title"
private const val DESCRIPTION_PLACEHOLDER = "Write here"
private const val STEPS_PLACEHOLDER = "List the steps"
private const val RESOLUTION_PLACEHOLDER = "Write how it was fixed"

/**
 * The Bugs tab of a project: a "Report a bug" button, Open / Resolved / All chips and the bug
 * cards, most severe first. Deletes show an Undo snackbar that belongs to this tab.
 */
@Composable
fun ProjectBugsTab(
    projectId: String,
    onOpenBug: (bugId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = neriboViewModel(key = "dev-bugs-tab-$projectId") { c ->
        ProjectBugsViewModel(projectId, c.bugsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var statusSheetBugId by rememberSaveable { mutableStateOf<String?>(null) }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    // A bug deleted from its editor comes back here through BugDeleteNotice.
    LaunchedEffect(projectId) {
        BugDeleteNotice.pending.collect { notice ->
            if (notice != null && notice.projectId == projectId) {
                BugDeleteNotice.clear()
                if (BugDeleteNotice.isFresh(notice)) {
                    showUndo("Moved to Recently deleted", { vm.restore(notice.bugId) })
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            NeriboButton(
                text = "Report a bug",
                onClick = { onOpenBug(BUG_NEW_ID) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen),
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.BugReport,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BugFilter.values().forEach { filter ->
                    NeriboChip(
                        label = filter.label,
                        selected = state.filter == filter,
                        onClick = { vm.setFilter(filter) },
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
                    state.bugs.isEmpty() -> BugsEmptyState(filter = state.filter)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        items(state.bugs, key = { it.id }) { bug ->
                            BugCard(
                                bug = bug,
                                actions = listOf(
                                    MenuAction(
                                        label = "Open",
                                        onClick = { onOpenBug(bug.id) },
                                        icon = Icons.Outlined.Edit,
                                    ),
                                    MenuAction(
                                        label = "Change status",
                                        onClick = { statusSheetBugId = bug.id },
                                        icon = Icons.Outlined.Flag,
                                    ),
                                    MenuAction(
                                        label = "Delete",
                                        onClick = {
                                            vm.delete(bug.id)
                                            scope.launch {
                                                showUndo("Moved to Recently deleted", { vm.restore(bug.id) })
                                            }
                                        },
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                    ),
                                ),
                                onClick = { onOpenBug(bug.id) },
                            )
                        }
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

    val sheetBug: BugEntity? = statusSheetBugId?.let { id -> state.bugs.firstOrNull { it.id == id } }
    if (sheetBug != null) {
        BugStatusSheet(
            current = sheetBug.status,
            onSelect = { status ->
                vm.changeStatus(sheetBug.id, status)
                statusSheetBugId = null
            },
            onDismiss = { statusSheetBugId = null },
        )
    }
}

@Composable
private fun BugsEmptyState(filter: BugFilter) {
    when (filter) {
        BugFilter.Open -> EmptyState(
            icon = Icons.Outlined.BugReport,
            title = "No open bugs",
            message = "Enjoy it while it lasts.",
            modifier = Modifier.fillMaxSize(),
        )
        BugFilter.Resolved -> EmptyState(
            icon = Icons.Outlined.BugReport,
            title = "Nothing resolved yet",
            message = "Fixed bugs gather here, with the note on how you fixed them.",
            modifier = Modifier.fillMaxSize(),
        )
        BugFilter.All -> EmptyState(
            icon = Icons.Outlined.BugReport,
            title = "No bugs reported",
            message = "When something breaks, write down the steps to reproduce it.",
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Full-screen bug editor: a serif title, severity and status chips, the description, steps to
 * reproduce and (once Resolved or Closed) a resolution note. Autosaves 600ms after the last
 * change and whenever the screen stops. `bugId == "new"` creates a bug in [projectId].
 */
@Composable
fun BugEditorScreen(
    projectId: String,
    bugId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm = neriboViewModel(key = "dev-bug-editor-$bugId") { c ->
        BugEditorViewModel(projectId, bugId, c.bugsRepository)
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

    val actions = listOf(
        MenuAction(
            label = "Copy as text",
            onClick = {
                context.copyToClipboard("Bug report", vm.plainText())
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
                context.shareText(vm.currentTitle.trim().ifEmpty { null }, vm.plainText())
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete",
            onClick = {
                vm.deleteBug { deletedId ->
                    if (deletedId != null) BugDeleteNotice.post(projectId, deletedId)
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
                title = if (vm.isNew) "Report a bug" else "Bug",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    BugSaveStatus.Idle -> null
                    BugSaveStatus.Saving -> "Saving\u2026"
                    BugSaveStatus.Saved -> "Saved"
                },
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            BugEditorContent(vm = vm, state = state, padding = padding)
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
private fun BugEditorContent(
    vm: BugEditorViewModel,
    state: BugEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var descriptionValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentDescription, TextRange(vm.currentDescription.length)))
    }
    var stepsValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentSteps, TextRange(vm.currentSteps.length)))
    }
    var resolutionValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentResolution, TextRange(vm.currentResolution.length)))
    }

    val titleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onDescriptionChange(descriptionValue.text)
        vm.onStepsChange(stepsValue.text)
        vm.onResolutionChange(resolutionValue.text)
        // Quick capture: a brand-new bug opens with the title focused.
        if (vm.isNew && titleValue.text.isEmpty() && descriptionValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val resolvedAt = state.resolvedAt

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        Spacer(modifier = Modifier.height(spacing.md))
        SectionHeader("Title")
        Spacer(modifier = Modifier.height(spacing.sm))
        BugBorderlessField(
            value = titleValue,
            onValueChange = { new ->
                val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                titleValue = cleaned
                vm.onTitleChange(cleaned.text)
            },
            textStyle = titleStyle,
            placeholder = TITLE_PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(titleFocus),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { runCatching { descriptionFocus.requestFocus() } },
            ),
            maxLines = 3,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboDivider()
        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("SEVERITY")
        BugChipRow {
            BUG_SEVERITIES.forEach { severity ->
                NeriboChip(
                    label = severityLabel(severity),
                    selected = state.severity == severity,
                    onClick = { vm.setSeverity(severity) },
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        SectionHeader("STATUS")
        BugChipRow {
            BUG_STATUSES.forEach { status ->
                NeriboChip(
                    label = bugStatusLabel(status),
                    selected = state.status == status,
                    onClick = { vm.setStatus(status) },
                )
            }
        }
        if (resolvedAt != null && isFinishedStatus(state.status)) {
            Text(
                text = "${if (state.status == BUG_CLOSED) "Closed" else "Resolved"} on ${formatDate(resolvedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("DESCRIPTION")
        Spacer(modifier = Modifier.height(spacing.xs))
        BugBorderlessField(
            value = descriptionValue,
            onValueChange = { new ->
                descriptionValue = new
                vm.onDescriptionChange(new.text)
            },
            textStyle = bodyStyle,
            placeholder = DESCRIPTION_PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .focusRequester(descriptionFocus),
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        SectionHeader("STEPS TO REPRODUCE")
        Spacer(modifier = Modifier.height(spacing.xs))
        BugBorderlessField(
            value = stepsValue,
            onValueChange = { new ->
                stepsValue = new
                vm.onStepsChange(new.text)
            },
            textStyle = bodyStyle,
            placeholder = STEPS_PLACEHOLDER,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp),
        )
        if (isFinishedStatus(state.status)) {
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("RESOLUTION")
            Spacer(modifier = Modifier.height(spacing.xs))
            BugBorderlessField(
                value = resolutionValue,
                onValueChange = { new ->
                    resolutionValue = new
                    vm.onResolutionChange(new.text)
                },
                textStyle = bodyStyle,
                placeholder = RESOLUTION_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
        }
        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

/** A horizontally scrolling row of chips. */
@Composable
private fun BugChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
