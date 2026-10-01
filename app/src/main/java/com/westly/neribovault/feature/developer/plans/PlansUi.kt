package com.westly.neribovault.feature.developer.plans

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
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
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.shareText
import kotlinx.coroutines.launch

private val DOC_TITLE_PLACEHOLDERS = listOf(
    "Offline sync for the Ariaria market POS",
    "Q4 roadmap for the dispatch app",
    "How the Benin City booking flow fits together",
)
private const val DOC_BODY_PLACEHOLDER =
    "Write the plan here.\n\n- [ ] Sketch the invoice screen\n- [ ] Test on a low-end phone\n- [ ] Send to Adaeze for feedback"

private val FOLDER_TITLE_PLACEHOLDERS = listOf(
    "Efe's boutique storefront",
    "Lagos dispatch API",
    "Church bulletin website",
)
private const val OUTLINE_PLACEHOLDER =
    "src/\n  components/\n    Navbar.tsx\n  App.tsx\npackage.json"

private const val CHECKLIST_VIEW_ON = "Edit view"
private const val CHECKLIST_VIEW_OFF = "Checklist view"

/**
 * The Plans tab of a project: PLANNING DOCS and FOLDER PLANS, each with its own "new" button and
 * its own cards. Deletes show an Undo snackbar that belongs to this tab.
 */
@Composable
fun ProjectPlansTab(
    projectId: String,
    onOpenPlanningDoc: (docId: String) -> Unit,
    onOpenFolderPlan: (planId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = neriboViewModel(key = "dev-plans-tab-$projectId") { c ->
        ProjectPlansViewModel(projectId, c.planningDocsRepository, c.folderPlansRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    // An item deleted from its editor comes back here through PlansDeleteNotice.
    LaunchedEffect(projectId) {
        PlansDeleteNotice.pending.collect { notice ->
            if (notice != null && notice.projectId == projectId) {
                PlansDeleteNotice.clear()
                if (PlansDeleteNotice.isFresh(notice)) {
                    val undo: () -> Unit = when (notice.kind) {
                        PlansDeleteNotice.Kind.Planning -> ({ vm.restoreDoc(notice.id) })
                        PlansDeleteNotice.Kind.Folder -> ({ vm.restorePlan(notice.id) })
                    }
                    showUndo("Moved to Recently deleted", undo)
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
                item(key = "docs-header") { SectionHeader("PLANNING DOCS") }
                item(key = "docs-new") {
                    NeriboButton(
                        text = "New planning doc",
                        onClick = { onOpenPlanningDoc(PLAN_NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Description,
                    )
                }
                if (state.docs.isEmpty()) {
                    item(key = "docs-empty") {
                        Text(
                            text = "No planning docs yet. A roadmap for the next release is a good first page.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.docs.size, key = { index -> "doc-${state.docs[index].id}" }) { index ->
                        val doc = state.docs[index]
                        PlanningDocCard(
                            doc = doc,
                            actions = listOf(
                                MenuAction(
                                    label = "Open",
                                    onClick = { onOpenPlanningDoc(doc.id) },
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
                                            showUndo("Moved to Recently deleted", { vm.restoreDoc(doc.id) })
                                        }
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                            onClick = { onOpenPlanningDoc(doc.id) },
                        )
                    }
                }
                item(key = "plans-spacer") { Spacer(modifier = Modifier.height(spacing.sm)) }
                item(key = "plans-header") { SectionHeader("FOLDER PLANS") }
                item(key = "plans-new") {
                    NeriboButton(
                        text = "New folder plan",
                        onClick = { onOpenFolderPlan(PLAN_NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Folder,
                    )
                }
                if (state.plans.isEmpty()) {
                    item(key = "plans-empty") {
                        Text(
                            text = "No folder plans yet. Sketch the structure before you write the first line.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.plans.size, key = { index -> "plan-${state.plans[index].id}" }) { index ->
                        val plan = state.plans[index]
                        FolderPlanCard(
                            plan = plan,
                            actions = listOf(
                                MenuAction(
                                    label = "Open",
                                    onClick = { onOpenFolderPlan(plan.id) },
                                    icon = Icons.Outlined.Edit,
                                ),
                                MenuAction(
                                    label = "Duplicate",
                                    onClick = { vm.duplicatePlan(plan) },
                                    icon = Icons.Outlined.FileCopy,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        vm.deletePlan(plan.id)
                                        scope.launch {
                                            showUndo("Moved to Recently deleted", { vm.restorePlan(plan.id) })
                                        }
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                            onClick = { onOpenFolderPlan(plan.id) },
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
// Planning document editor
// ---------------------------------------------------------------------------------------------

/**
 * Full-screen planning document editor: serif title, kind chips and a large body. Lines that
 * begin with `- [ ]` or `- [x]` become real checkboxes in the Checklist view. Autosaves 600ms
 * after the last change and whenever the screen stops. `docId == "new"` creates a doc in
 * [projectId].
 */
@Composable
fun PlanningDocEditorScreen(projectId: String, docId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = neriboViewModel(key = "dev-plan-editor-$docId") { c ->
        PlanningDocEditorViewModel(projectId, docId, c.planningDocsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var checklistMode by rememberSaveable { mutableStateOf(false) }

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
                context.copyToClipboard("Planning doc", vm.plainText())
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
                        PlansDeleteNotice.post(PlansDeleteNotice.Kind.Planning, projectId, deletedId)
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
                title = if (vm.isNew) "New plan" else "Plan",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    PlanSaveStatus.Idle -> null
                    PlanSaveStatus.Saving -> "Saving\u2026"
                    PlanSaveStatus.Saved -> "Saved"
                },
                actions = {
                    NeriboIconButton(
                        icon = if (checklistMode) Icons.Outlined.Edit else Icons.Outlined.CheckCircle,
                        contentDescription = if (checklistMode) CHECKLIST_VIEW_ON else CHECKLIST_VIEW_OFF,
                        onClick = { checklistMode = !checklistMode },
                        tint = if (checklistMode) {
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
            PlanningDocEditorContent(
                vm = vm,
                state = state,
                padding = padding,
                checklistMode = checklistMode,
            )
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
private fun PlanningDocEditorContent(
    vm: PlanningDocEditorViewModel,
    state: PlanningDocEditorUiState,
    padding: PaddingValues,
    checklistMode: Boolean,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentBody, TextRange(vm.currentBody.length)))
    }
    val titlePlaceholder = remember { DOC_TITLE_PLACEHOLDERS.random() }
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (checklistMode) {
                ChecklistView(
                    title = titleValue.text,
                    body = bodyValue.text,
                    onToggle = { lineIndex ->
                        val toggled = toggleChecklistLine(bodyValue.text, lineIndex)
                        if (toggled != bodyValue.text) {
                            bodyValue = bodyValue.copy(text = toggled)
                            vm.onBodyChange(toggled)
                        }
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen),
                ) {
                    PlanBorderlessField(
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
                        placeholder = titlePlaceholder,
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
                        maxLines = 4,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PLAN_KINDS.forEach { kind ->
                            NeriboChip(
                                label = planKindLabel(kind),
                                selected = state.kind == kind,
                                onClick = { vm.setKind(kind) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.md))
                    PlanBorderlessField(
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
            }
        }
        if (state.total > 0) {
            PlanEditorFooter(text = "${state.done} of ${state.total} done")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Folder plan editor
// ---------------------------------------------------------------------------------------------

/**
 * The folder planner. The owner types an indented outline (two spaces or a tab per level; a
 * trailing `/` marks a folder) and sees a live tree. The outline text is what gets saved, so the
 * plan always round-trips exactly. `planId == "new"` creates a plan in [projectId].
 */
@Composable
fun FolderPlanEditorScreen(projectId: String, planId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = neriboViewModel(key = "dev-folderplan-editor-$planId") { c ->
        FolderPlanEditorViewModel(projectId, planId, c.folderPlansRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTemplates by rememberSaveable { mutableStateOf(false) }

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
    val copyOutput: (String, String) -> Unit = { label, text ->
        if (text.isBlank()) {
            notify("Nothing to copy yet")
        } else {
            context.copyToClipboard(label, text)
            notify("Copied to clipboard")
        }
    }

    val actions = buildList<MenuAction> {
        add(
            MenuAction(
                label = "Copy as tree",
                onClick = { copyOutput("Folder tree", renderTree(parseOutline(vm.currentOutline))) },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Copy as mkdir commands",
                onClick = {
                    copyOutput("mkdir commands", buildMkdirCommands(parseOutline(vm.currentOutline)))
                },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Copy outline",
                onClick = { copyOutput("Folder outline", vm.currentOutline) },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Share",
                onClick = {
                    val tree = renderTree(parseOutline(vm.currentOutline))
                    if (tree.isBlank()) {
                        notify("Nothing to share yet")
                    } else {
                        context.shareText(vm.currentTitle.trim().ifEmpty { null }, tree)
                    }
                },
                icon = Icons.Outlined.Share,
            ),
        )
        if (vm.isNew) {
            add(
                MenuAction(
                    label = "Use a template",
                    onClick = { showTemplates = true },
                    icon = Icons.Outlined.Folder,
                ),
            )
        }
        add(
            MenuAction(
                label = "Duplicate",
                onClick = {
                    vm.duplicate { created ->
                        notify(if (created) "Duplicate created" else "Write something first")
                    }
                },
                icon = Icons.Outlined.FileCopy,
            ),
        )
        add(
            MenuAction(
                label = "Delete",
                onClick = {
                    vm.deletePlan { deletedId ->
                        if (deletedId != null) {
                            PlansDeleteNotice.post(PlansDeleteNotice.Kind.Folder, projectId, deletedId)
                        }
                        onBack()
                    }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New folder plan" else "Folder plan",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    PlanSaveStatus.Idle -> null
                    PlanSaveStatus.Saving -> "Saving\u2026"
                    PlanSaveStatus.Saved -> "Saved"
                },
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            FolderPlanEditorContent(
                vm = vm,
                padding = padding,
                showTemplates = showTemplates,
                onShowTemplates = { showTemplates = true },
                onDismissTemplates = { showTemplates = false },
            )
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
private fun FolderPlanEditorContent(
    vm: FolderPlanEditorViewModel,
    padding: PaddingValues,
    showTemplates: Boolean,
    onShowTemplates: () -> Unit,
    onDismissTemplates: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var outlineValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentOutline, TextRange(vm.currentOutline.length)))
    }
    var showPreview by rememberSaveable { mutableStateOf(false) }

    val titlePlaceholder = remember { FOLDER_TITLE_PLACEHOLDERS.random() }
    val titleFocus = remember { FocusRequester() }
    val outlineFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onOutlineChange(outlineValue.text)
        if (vm.isNew && titleValue.text.isEmpty() && outlineValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val applyOutline: (TextFieldValue) -> Unit = { updated ->
        outlineValue = updated
        vm.onOutlineChange(updated.text)
    }
    val nodes = remember(outlineValue.text) { parseOutline(outlineValue.text) }
    val treeText = remember(nodes) { renderTree(nodes) }
    val counts = remember(nodes) { countTree(nodes) }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val monoStyle = planMonoStyle()

    val outlinePane: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboButton(
                    text = "Tab",
                    onClick = { applyOutline(indentLineAtCursor(outlineValue)) },
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "Shift+Tab",
                    onClick = { applyOutline(outdentLineAtCursor(outlineValue)) },
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "New folder",
                    onClick = { applyOutline(newFolderAtCursor(outlineValue)) },
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "New file",
                    onClick = { applyOutline(newFileAtCursor(outlineValue)) },
                    style = ButtonStyle.Secondary,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                if (vm.isNew && outlineValue.text.isEmpty()) {
                    NeriboButton(
                        text = "Start from a template",
                        onClick = onShowTemplates,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Text,
                    )
                }
                PlanBorderlessField(
                    value = outlineValue,
                    onValueChange = applyOutline,
                    textStyle = monoStyle,
                    placeholder = OUTLINE_PLACEHOLDER,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .focusRequester(outlineFocus),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                    ),
                )
            }
        }
    }

    val previewPane: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.sm),
        ) {
            if (treeText.isEmpty()) {
                Text(
                    text = "Your tree appears here as you type the outline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else {
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(spacing.lg),
                    ) {
                        Text(text = treeText, style = monoStyle, softWrap = false)
                    }
                }
                Text(
                    text = "${counts.first} folders \u00B7 ${counts.second} files",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.sm),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
            PlanBorderlessField(
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
                placeholder = titlePlaceholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { outlineFocus.requestFocus() } },
                ),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (maxWidth >= 600.dp) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) { outlinePane() }
                    NeriboDivider()
                    Box(modifier = Modifier.weight(1f)) { previewPane() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.screen),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeriboChip(
                            label = "Edit",
                            selected = !showPreview,
                            onClick = { showPreview = false },
                        )
                        NeriboChip(
                            label = "Preview",
                            selected = showPreview,
                            onClick = { showPreview = true },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (showPreview) previewPane() else outlinePane()
                    }
                }
            }
        }
    }

    if (showTemplates) {
        NeriboBottomSheet(onDismiss = onDismissTemplates) {
            Text(
                text = "Start from a template",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            FOLDER_TEMPLATES.forEach { template ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable {
                            applyOutline(
                                TextFieldValue(template.outline, TextRange(template.outline.length)),
                            )
                            if (titleValue.text.isBlank()) {
                                titleValue = TextFieldValue(template.label, TextRange(template.label.length))
                                vm.onTitleChange(template.label)
                            }
                            onDismissTemplates()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = template.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}
