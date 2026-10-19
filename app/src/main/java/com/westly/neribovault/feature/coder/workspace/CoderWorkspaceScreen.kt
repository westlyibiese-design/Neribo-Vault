package com.westly.neribovault.feature.coder.workspace

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.coder.ai.AiSelectionSnapshot
import com.westly.neribovault.feature.coder.ai.CoderAiPanel
import com.westly.neribovault.feature.coder.ai.CoderAiViewModel
import com.westly.neribovault.feature.coder.ai.aiSelectionSnapshotOf
import com.westly.neribovault.feature.coder.ai.aiSnapshotApplies
import com.westly.neribovault.feature.coder.build.CoderBuildAiHandoff
import com.westly.neribovault.feature.coder.editor.CoderEditorPanel
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.ExtraKeysBar
import com.westly.neribovault.feature.coder.editor.smart.EditorSettingsSheet
import com.westly.neribovault.feature.coder.editor.smart.FindReplaceBar
import com.westly.neribovault.feature.coder.editor.smart.SuggestionStrip
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.guidance.CoderTipHost
import com.westly.neribovault.feature.coder.guidance.TipContext
import com.westly.neribovault.feature.coder.guidance.TipScreen
import com.westly.neribovault.feature.coder.guidance.TipTyping
import com.westly.neribovault.feature.coder.link.CoderLinkSheet
import com.westly.neribovault.feature.coder.link.CoderLinkState
import com.westly.neribovault.feature.coder.link.CoderLinkViewModel
import com.westly.neribovault.feature.coder.preview.CoderConsoleMessage
import com.westly.neribovault.feature.coder.preview.CoderLocalServer
import com.westly.neribovault.feature.coder.preview.CoderPreviewPanel
import com.westly.neribovault.feature.coder.preview.CoderPreviewSession
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import com.westly.neribovault.feature.coder.preview.SPLIT_DEFAULT_RATIO
import com.westly.neribovault.feature.coder.preview.SplitLayout
import com.westly.neribovault.feature.coder.zip.CoderExportResult
import com.westly.neribovault.feature.coder.zip.CoderTrust
import com.westly.neribovault.feature.coder.zip.CoderZipExporter
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import com.westly.neribovault.feature.coder.zip.ZipProgressDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspaceDialog { None, Chooser, NewFile, NewFolder, Rename, Delete, RenameProject }

/** A tab close that waits for the person to confirm, because a file in it could not be saved. */
private sealed class PendingClose {
    class One(val path: String) : PendingClose()
    class Others(val keep: String) : PendingClose()
    object All : PendingClose()
}

/**
 * One project. In Files mode it shows the file tree; in Editor mode it shows a strip of open files (tabs), a
 * side drawer with the file tree and the code editor of the active tab. Back closes the find bar, then the
 * drawer, then saves and returns to the file list (the tabs stay open) and then leaves the project.
 */
@Composable
fun CoderWorkspaceScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenBuild: () -> Unit,
) {
    val vm = neriboViewModel(key = "coder-workspace-$projectId") { c ->
        CoderWorkspaceViewModel(projectId, c.coderProjectsRepository, c.coderFileStore, c.coderPrefs)
    }
    val aiVm = neriboViewModel(key = "coder-ai-$projectId") { c ->
        CoderAiViewModel(
            projectId,
            c.coderAiSettings,
            c.coderFileStore,
            c.coderProjectsRepository,
            c.projectsRepository,
            c.planningDocsRepository,
            c.projectDocumentsRepository,
            c.coderPrefs,
        )
    }
    val linkVm = neriboViewModel(key = "coder-link-$projectId") { c ->
        CoderLinkViewModel(projectId, c.coderProjectsRepository, c.projectsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val linkState by linkVm.linkState.collectAsStateWithLifecycle()
    val editorValue by vm.editorValue.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val find by vm.find.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // The open tabs, kept as plain paths so they can be reopened after the process was killed.
    var savedTabPaths by rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var savedActivePath by rememberSaveable { mutableStateOf<String?>(null) }
    var savedEditorVisible by rememberSaveable { mutableStateOf(false) }
    val restoredPaths = remember { savedTabPaths }
    val restoredActive = remember { savedActivePath }
    val restoredVisible = remember { savedEditorVisible }
    var restoreHandled by remember { mutableStateOf(restoredPaths.isEmpty()) }
    var expandedFolders by rememberSaveable(
        stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it }),
    ) { mutableStateOf(emptyList<String>()) }
    var dialog by rememberSaveable { mutableStateOf(WorkspaceDialog.None) }
    var dialogPath by rememberSaveable { mutableStateOf("") }
    var showTrustDialog by rememberSaveable { mutableStateOf(false) }
    var aiOpen by rememberSaveable { mutableStateOf(false) }
    // The selection as it was when AI help was opened; the editor loses its selection once it leaves the screen.
    var aiSnapshot by remember { mutableStateOf<AiSelectionSnapshot?>(null) }
    var showLinkSheet by rememberSaveable { mutableStateOf(false) }
    var showEditorSettings by rememberSaveable { mutableStateOf(false) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingClose by remember { mutableStateOf<PendingClose?>(null) }
    var findFieldFocused by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // True once the suggestion strip has had something to show; the "suggestions" tip waits for it.
    var suggestionShownOnce by remember { mutableStateOf(false) }
    LaunchedEffect(suggestions) { if (suggestions.isNotEmpty()) suggestionShownOnce = true }
    val exporter = remember(context) {
        CoderZipExporter(context.applicationContext, (context.applicationContext as NeriboApp).container.coderFileStore)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null && !exporting) {
            exporting = true
            scope.launch {
                val result = try {
                    exporter.export(projectId, uri)
                } finally {
                    exporting = false
                }
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    when (result) {
                        is CoderExportResult.Success ->
                            if (result.fileCount == 1) "Saved ZIP with 1 file" else "Saved ZIP with ${result.fileCount} files"
                        is CoderExportResult.Failure -> result.message
                    },
                )
            }
        }
    }

    LifecycleSaveEffect(onSave = { vm.flush() })

    // A message the build wizard prepared for AI help (never sent): put it in the input and show the AI screen.
    LaunchedEffect(projectId) {
        val prepared = CoderBuildAiHandoff.take(projectId)
        if (prepared != null) {
            aiVm.setInput(prepared)
            aiSnapshot = null
            aiOpen = true
        }
    }

    // After the process was killed, open the files that were open before. It finishes even when the screen turns meanwhile.
    LaunchedEffect(Unit) {
        if (restoredPaths.isNotEmpty() && vm.state.value.tabs.isEmpty()) {
            withContext(NonCancellable) { vm.restoreTabs(restoredPaths, restoredActive, restoredVisible) }
        }
        restoreHandled = true
    }
    val tabPaths = remember(state.tabs) { state.tabs.map { it.path } }
    LaunchedEffect(tabPaths, state.openPath, state.editorVisible, restoreHandled) {
        if (restoreHandled) {
            savedTabPaths = tabPaths
            savedActivePath = state.openPath
            savedEditorVisible = state.editorVisible
        }
    }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    // The editor is on screen only in Editor mode. With the file list showing, the tabs stay open but openPath is null here.
    val openPath = if (state.editorVisible) state.openPath else null
    val previewEntry = state.previewEntry

    // Live preview next to the editor: the switch, the editor's share, the chosen size and page, and a counter
    // that goes up each time a save finishes (the preview reloads from it).
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val splitOn by prefs.splitEnabled.collectAsStateWithLifecycle(initialValue = false)
    var splitRatio by rememberSaveable { mutableStateOf(SPLIT_DEFAULT_RATIO) }
    val previewSession = rememberSaveable(saver = CoderPreviewSession.Saver) { CoderPreviewSession() }
    var saveTick by rememberSaveable { mutableStateOf(0) }
    var lastSaveStatus by remember { mutableStateOf(state.saveStatus) }
    LaunchedEffect(state.saveStatus) {
        if (lastSaveStatus == CoderSaveStatus.Saving && state.saveStatus == CoderSaveStatus.Saved) saveTick += 1
        lastSaveStatus = state.saveStatus
    }
    // The pane follows the last page, Markdown or JSON file that was open; with none it shows index.html when there is one.
    var followedPage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openPath) {
        if (openPath != null && isPreviewableFile(openPath)) followedPage = openPath
    }
    val splitHasRoot by produceState(initialValue = false, projectId, state.entries) {
        value = hasRunnableRoot(context, projectId)
    }
    val paneEntry = followedPage ?: if (splitHasRoot) RUN_INDEX else null
    // The page served to the phone's browser stops when the person leaves the project (a rotation is not leaving)
    // or when the project is gone.
    val hostActivity = remember(context) { context.findHostActivity() }
    DisposableEffect(Unit) {
        onDispose {
            if (hostActivity?.isChangingConfigurations != true) CoderLocalServer.shared(context).stop()
        }
    }
    LaunchedEffect(state.isLoading, state.project == null) {
        if (!state.isLoading && state.project == null) CoderLocalServer.shared(context).stop()
    }
    // While the preview is showing it handles Back itself (page history first, then closing).
    BackHandler(enabled = openPath != null && previewEntry == null && !aiOpen) { vm.showFiles() }
    // The drawer closes before Back returns to the file list; declared after the handler above so that it is asked first.
    BackHandler(enabled = openPath != null && previewEntry == null && !aiOpen && drawerOpen) { drawerOpen = false }
    // The find bar closes first of all; this is declared last, so it is asked before the two above.
    BackHandler(enabled = openPath != null && previewEntry == null && !aiOpen && find.open) { vm.closeFind() }
    LaunchedEffect(find.open) { if (!find.open) findFieldFocused = false }
    LaunchedEffect(openPath) { if (openPath == null) drawerOpen = false }
    val openAiHelp: () -> Unit = {
        val shown = vm.state.value
        aiSnapshot = aiSelectionSnapshotOf(if (shown.editorVisible) shown.openPath else null, vm.editorValue.value)
        drawerOpen = false
        aiOpen = true
    }
    val closeAiHelp: () -> Unit = {
        aiOpen = false
        aiSnapshot = null
    }
    val askAiFromPreview: (CoderConsoleMessage) -> Unit = { message ->
        vm.stopPreview()
        aiVm.prepareFromError(message)
        aiSnapshot = null
        aiOpen = true
    }
    // The AI help screen takes Back first and then returns to where the person was.
    BackHandler(enabled = aiOpen) { closeAiHelp() }
    if (aiOpen) {
        CoderAiPanel(
            viewModel = aiVm,
            projectId = projectId,
            projectName = state.project?.name ?: "Project",
            entries = state.entries,
            openPath = openPath,
            editorValue = editorValue,
            selectionSnapshot = aiSnapshot,
            snackbarHostState = snackbarHostState,
            onBack = closeAiHelp,
            onOpenAiSettings = onOpenAiSettings,
            onInsertAtCursor = { vm.insertAtCursor(it) },
            onReplaceSelection = { code ->
                val snapshot = aiSnapshot
                if (snapshot != null && aiSnapshotApplies(snapshot, vm.state.value.openPath, vm.editorValue.value.text)) {
                    vm.replaceRange(snapshot.start, snapshot.end, code)
                }
            },
            onReplaceAll = { vm.replaceAll(it) },
            onOpenAndReplace = { path, text -> vm.openAndReplaceAll(path, text) },
            onCreateFile = { path, text -> vm.createFile(path, text) },
        )
        return
    }
    if (previewEntry != null) {
        CoderPreviewPanel(
            projectId = projectId,
            entryPath = previewEntry,
            onClose = { vm.stopPreview() },
            onAskAi = askAiFromPreview,
            saveTick = saveTick,
            entries = state.entries,
            session = previewSession,
            snackbarHostState = snackbarHostState,
            onOpenBuild = onOpenBuild,
        )
        return
    }

    val existingPaths = remember(state.entries) { state.entries.map { it.path }.toSet() }
    val project = state.project
    val canBrowse = !state.isLoading && project != null
    // A tip never appears while a dialog or sheet is open.
    val tipsBlocked = dialog != WorkspaceDialog.None || showEditorSettings || showLinkSheet || showTrustDialog ||
        pendingClose != null || exporting
    // On a narrow phone the top bar has no room for one more button, so Find moves into the menu.
    val findInTopBar = LocalConfiguration.current.screenWidthDp >= FIND_IN_TOP_BAR_MIN_WIDTH_DP

    // Run shows the open file when it is a page, Markdown, SVG or JSON; otherwise the project's index.html.
    val runPreview: () -> Unit = {
        focusManager.clearFocus()
        drawerOpen = false
        scope.launch {
            val shown = vm.state.value
            val open = if (shown.editorVisible) shown.openPath else null
            val entry = when {
                open != null && isPreviewableFile(open) -> open
                hasRunnableRoot(context, projectId) -> RUN_INDEX
                else -> null
            }
            if (entry != null) {
                vm.startPreview(entry)
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(MSG_NOTHING_TO_RUN)
            }
        }
    }
    // A tab whose file could not be saved asks before it closes; every other tab closes at once (after saving).
    val requestCloseTab: (String) -> Unit = { path ->
        if (state.tabs.firstOrNull { it.path == path }?.saveFailed == true) {
            pendingClose = PendingClose.One(path)
        } else {
            vm.closeTab(path)
        }
    }
    val requestCloseOthers: (String) -> Unit = { keep ->
        if (state.tabs.any { it.path != keep && it.saveFailed }) {
            pendingClose = PendingClose.Others(keep)
        } else {
            vm.closeOtherTabs(keep)
        }
    }
    val requestCloseAll: () -> Unit = {
        if (state.tabs.any { it.saveFailed }) pendingClose = PendingClose.All else vm.closeAllTabs()
    }
    // A project that came from a ZIP asks once per app session before its code runs.
    val onRun: () -> Unit = {
        if (project?.templateId == CoderZipRules.TEMPLATE_ID && !CoderTrust.isTrusted(projectId)) {
            showTrustDialog = true
        } else {
            runPreview()
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = project?.name ?: "Coder",
                subtitle = openPath,
                onBack = {
                    if (openPath != null && find.open) {
                        vm.closeFind()
                    } else if (openPath != null && drawerOpen) {
                        drawerOpen = false
                    } else if (openPath != null) {
                        vm.showFiles()
                    } else {
                        onBack()
                    }
                },
                actions = {
                    if (openPath != null) {
                        EditorActions(
                            canUndo = state.canUndo,
                            canRedo = state.canRedo,
                            status = state.saveStatus,
                            onUndo = { vm.undo() },
                            onRedo = { vm.redo() },
                            onRetry = { scope.launch { vm.saveNow() } },
                        )
                        if (findInTopBar) {
                            FindAction(isOpen = find.open, onClick = { if (find.open) vm.closeFind() else vm.openFind() })
                        }
                        AiHelpAction(onClick = openAiHelp)
                        RunAction(onClick = onRun)
                        OverflowMenu(
                            actions = (if (findInTopBar) emptyList() else listOf(MenuAction("Find", { vm.openFind() }))) +
                                listOf(
                                    MenuAction(
                                        label = if (splitOn) "Live preview: on" else "Live preview: off",
                                        onClick = { scope.launch { prefs.setSplitEnabled(!splitOn) } },
                                        icon = Icons.Outlined.Visibility,
                                    ),
                                    MenuAction("Editor settings", { showEditorSettings = true }),
                                ),
                        )
                    } else if (canBrowse) {
                        AiHelpAction(onClick = openAiHelp)
                        RunAction(onClick = onRun)
                        OverflowMenu(
                            actions = listOf(
                                MenuAction("Rename project", { dialog = WorkspaceDialog.RenameProject }),
                                MenuAction("New file", {
                                    dialogPath = ""
                                    dialog = WorkspaceDialog.NewFile
                                }),
                                MenuAction("New folder", {
                                    dialogPath = ""
                                    dialog = WorkspaceDialog.NewFolder
                                }),
                                MenuAction("Export as ZIP", {
                                    exportLauncher.launch(exportFileName(project?.name.orEmpty()))
                                }),
                                MenuAction("GitHub", onOpenGitHub),
                                MenuAction("Build with GitHub", onOpenBuild),
                                MenuAction("Environment", onOpenEnvironment),
                            ) + linkMenuActions(
                                linkState = linkState,
                                onChoose = { showLinkSheet = true },
                                onUnlink = { linkVm.unlink() },
                            ),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (canBrowse && openPath == null) {
                NeriboFab(onClick = { dialog = WorkspaceDialog.Chooser }, contentDescription = "New file or folder")
            }
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize().padding(padding)) { LoadingState() }
            project == null -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "This project isn't available",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            openPath != null -> {
                val language = remember(openPath) { CoderLanguage.fromPath(openPath) }
                // The explanation of the underlined mistake the caret is on; it goes away when the caret moves off.
                val caret = editorValue.selection
                val issueMessage = remember(issues, caret) {
                    if (!caret.collapsed) null else issues.firstOrNull { caret.start >= it.start && caret.start <= it.end }?.message
                }
                // Where the file was scrolled to the last time it was shown; read again whenever the active file changes.
                val (scrollX, scrollY) = remember(openPath) { vm.scrollOf(openPath) }
                // With the keyboard open on a narrow screen the preview gives all its room to the editor.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    val editorBody: @Composable ColumnScope.() -> Unit = {
                        CoderTipHost(
                            screen = TipScreen.Editor,
                            context = TipContext(
                                screen = TipScreen.Editor,
                                fileOpen = true,
                                suggestionShownOnce = suggestionShownOnce,
                                tabCount = state.tabs.size,
                                htmlFileOpen = isHtmlFile(openPath),
                                busy = tipsBlocked || drawerOpen || find.open,
                            ),
                            modifier = Modifier.padding(horizontal = NeriboTheme.spacing.screen, vertical = NeriboTheme.spacing.xs),
                            typing = { TipTyping(vm.lastTypedAtMillis, vm.typedCharCount) },
                        )
                        TabStrip(
                            tabs = state.tabs,
                            activePath = openPath,
                            onActivate = { vm.activateTab(it) },
                            onClose = requestCloseTab,
                            onCloseOthers = requestCloseOthers,
                            onCloseAll = requestCloseAll,
                            onToggleFiles = {
                                if (drawerOpen) {
                                    drawerOpen = false
                                } else {
                                    focusManager.clearFocus()
                                    drawerOpen = true
                                }
                            },
                        )
                        if (find.open) {
                            FindReplaceBar(
                                state = find,
                                onQueryChange = { vm.setQuery(it) },
                                onReplacementChange = { vm.setReplacement(it) },
                                onToggleCase = { vm.toggleCase() },
                                onToggleWholeWord = { vm.toggleWholeWord() },
                                onPrevious = { vm.previous() },
                                onNext = { vm.next() },
                                onReplace = { vm.replaceCurrent() },
                                onReplaceAll = { vm.replaceAllMatches() },
                                onClose = { vm.closeFind() },
                                onFieldFocusChange = { findFieldFocused = it },
                            )
                        }
                        // Keyed by the file so that scroll position and focus never carry over from another tab.
                        key(openPath) {
                            CoderEditorPanel(
                                value = editorValue,
                                onValueChange = { vm.onEditorChange(it) },
                                language = language,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                matches = find.matches,
                                currentMatch = find.current,
                                issues = issues,
                                revealTick = find.reveal,
                                initialScrollX = scrollX,
                                initialScrollY = scrollY,
                                onScrollChanged = { x, y -> vm.updateScroll(openPath, x, y) },
                            )
                        }
                        // While a find box has the keyboard the strip and the extra keys are hidden: they would type
                        // into the file, not into the box, and the editor needs the room.
                        if (!findFieldFocused) {
                            SuggestionStrip(
                                suggestions = suggestions,
                                issueMessage = issueMessage,
                                onAccept = { vm.acceptSuggestion(it) },
                            )
                            ExtraKeysBar(onInsert = { vm.insertAtCursor(it) })
                        }
                    }
                    if (splitOn) {
                        SplitLayout(
                            ratio = splitRatio,
                            onRatioChange = { splitRatio = it },
                            modifier = Modifier.fillMaxSize().imePadding(),
                            collapseSecondWhenNarrow = imeVisible,
                            first = { Column(modifier = Modifier.fillMaxSize()) { editorBody() } },
                            second = {
                                if (paneEntry == null) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().padding(NeriboTheme.spacing.lg),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = MSG_NOTHING_TO_RUN,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                } else {
                                    CoderPreviewPanel(
                                        projectId = projectId,
                                        entryPath = paneEntry,
                                        onClose = { scope.launch { prefs.setSplitEnabled(false) } },
                                        onAskAi = askAiFromPreview,
                                        embedded = true,
                                        saveTick = saveTick,
                                        entries = state.entries,
                                        session = previewSession,
                                        snackbarHostState = snackbarHostState,
                                        onOpenBuild = onOpenBuild,
                                    )
                                }
                            },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize().imePadding()) { editorBody() }
                    }
                    FileDrawer(
                        open = drawerOpen,
                        projectName = project?.name.orEmpty(),
                        entries = state.entries,
                        expandedFolders = expandedFolders,
                        activePath = openPath,
                        onToggleFolder = { path ->
                            expandedFolders = if (path in expandedFolders) expandedFolders - path else expandedFolders + path
                        },
                        onOpenFile = {
                            vm.openFile(it)
                            drawerOpen = false
                        },
                        onRename = {
                            dialogPath = it.path
                            dialog = WorkspaceDialog.Rename
                        },
                        onDelete = {
                            dialogPath = it.path
                            dialog = WorkspaceDialog.Delete
                        },
                        onNewFileIn = {
                            dialogPath = it
                            dialog = WorkspaceDialog.NewFile
                        },
                        onNewFolderIn = {
                            dialogPath = it
                            dialog = WorkspaceDialog.NewFolder
                        },
                        onClose = { drawerOpen = false },
                    )
                }
            }
            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                CoderTipHost(
                    screen = TipScreen.Files,
                    context = TipContext(
                        screen = TipScreen.Files,
                        fileListShown = state.entries.isNotEmpty(),
                        busy = tipsBlocked,
                    ),
                    modifier = Modifier.padding(horizontal = NeriboTheme.spacing.screen, vertical = NeriboTheme.spacing.xs),
                )
                LinkedLabel(state = linkState, onUnlink = { linkVm.unlink() })
                if (state.tabs.isEmpty() && state.entries.isNotEmpty()) {
                    Text(
                        text = "Tap a file to start editing.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NeriboTheme.spacing.screen, vertical = NeriboTheme.spacing.xs),
                    )
                }
                FileTreePanel(
                    entries = state.entries,
                    expandedFolders = expandedFolders,
                    onToggleFolder = { path ->
                        expandedFolders = if (path in expandedFolders) expandedFolders - path else expandedFolders + path
                    },
                    onOpenFile = { vm.openFile(it) },
                    onRename = {
                        dialogPath = it.path
                        dialog = WorkspaceDialog.Rename
                    },
                    onDelete = {
                        dialogPath = it.path
                        dialog = WorkspaceDialog.Delete
                    },
                    onNewFileIn = {
                        dialogPath = it
                        dialog = WorkspaceDialog.NewFile
                    },
                    onNewFolderIn = {
                        dialogPath = it
                        dialog = WorkspaceDialog.NewFolder
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val dismissDialog = { dialog = WorkspaceDialog.None }
    when (dialog) {
        WorkspaceDialog.None -> Unit
        WorkspaceDialog.Chooser -> NewItemSheet(
            onNewFile = {
                dialogPath = ""
                dialog = WorkspaceDialog.NewFile
            },
            onNewFolder = {
                dialogPath = ""
                dialog = WorkspaceDialog.NewFolder
            },
            onDismiss = dismissDialog,
        )
        WorkspaceDialog.NewFile -> NameEntryDialog(
            title = "New file",
            label = "File name",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                vm.createFile(CoderPaths.join(dialogPath, name))
                dismissDialog()
                if (dialogPath.isNotEmpty() && dialogPath !in expandedFolders) expandedFolders = expandedFolders + dialogPath
            },
            onDismiss = dismissDialog,
            validate = { checkEntryName(it, dialogPath, existingPaths) },
        )
        WorkspaceDialog.NewFolder -> NameEntryDialog(
            title = "New folder",
            label = "Folder name",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                vm.createFolder(CoderPaths.join(dialogPath, name))
                dismissDialog()
                if (dialogPath.isNotEmpty() && dialogPath !in expandedFolders) expandedFolders = expandedFolders + dialogPath
            },
            onDismiss = dismissDialog,
            validate = { checkEntryName(it, dialogPath, existingPaths) },
        )
        WorkspaceDialog.Rename -> {
            val parent = CoderPaths.parentOf(dialogPath)
            NameEntryDialog(
                title = "Rename",
                label = "New name",
                initialValue = CoderPaths.nameOf(dialogPath),
                confirmLabel = "Rename",
                onConfirm = { name ->
                    if (name != CoderPaths.nameOf(dialogPath)) vm.renameEntry(dialogPath, CoderPaths.join(parent, name))
                    dismissDialog()
                },
                onDismiss = dismissDialog,
                validate = { checkEntryName(it, parent, existingPaths, ownPath = dialogPath) },
            )
        }
        WorkspaceDialog.Delete -> {
            val entry = state.entries.firstOrNull { it.path == dialogPath }
            if (entry == null) {
                LaunchedEffect(Unit) { dialog = WorkspaceDialog.None }
            } else {
                DeleteEntryDialog(
                    entry = entry,
                    onConfirm = {
                        vm.deleteEntry(entry.path)
                        dismissDialog()
                    },
                    onDismiss = dismissDialog,
                )
            }
        }
        WorkspaceDialog.RenameProject -> NameEntryDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = project?.name.orEmpty(),
            confirmLabel = "Rename",
            onConfirm = { name ->
                vm.renameProject(name)
                dismissDialog()
            },
            onDismiss = dismissDialog,
            maxLength = CoderLimits.MAX_PROJECT_NAME_LENGTH,
        )
    }

    pendingClose?.let { request ->
        ConfirmDialog(
            title = "Close without saving?",
            message = if (request is PendingClose.One) {
                "This file couldn't be saved. Close it anyway?"
            } else {
                "Some files couldn't be saved. Close them anyway?"
            },
            confirmLabel = "Close anyway",
            onConfirm = {
                pendingClose = null
                when (request) {
                    is PendingClose.One -> vm.closeTab(request.path, discard = true)
                    is PendingClose.Others -> vm.closeOtherTabs(request.keep, discard = true)
                    PendingClose.All -> vm.closeAllTabs(discard = true)
                }
            },
            onDismiss = { pendingClose = null },
            destructive = true,
            dismissLabel = "Keep open",
        )
    }
    if (showEditorSettings) {
        EditorSettingsSheet(onDismiss = { showEditorSettings = false })
    }
    if (showLinkSheet) {
        CoderLinkSheet(viewModel = linkVm, onDismiss = { showLinkSheet = false })
    }
    if (showTrustDialog) {
        ConfirmDialog(
            title = "Run this project?",
            message = "This project came from a ZIP file. Running it runs its code on your phone, inside a safe preview that cannot reach your Neribo Vault data. Only run projects you trust.",
            confirmLabel = "Run",
            onConfirm = {
                CoderTrust.trust(projectId)
                showTrustDialog = false
                runPreview()
            },
            onDismiss = { showTrustDialog = false },
            dismissLabel = "Cancel",
        )
    }
    if (exporting) {
        ZipProgressDialog(title = "Saving ZIP\u2026", onCancel = null)
    }
}

private const val RUN_INDEX = "index.html"
private const val FIND_IN_TOP_BAR_MIN_WIDTH_DP = 400
private const val MSG_NOTHING_TO_RUN = "Add an index.html file to preview this project."
private val PREVIEWABLE_EXTENSIONS = setOf("html", "htm", "md", "markdown", "svg", "json")

/** The suggested file name for an export: the project name with anything but letters, digits, spaces, "-" and "_" replaced by "_". */
private fun exportFileName(projectName: String): String {
    val safe = projectName.map { ch -> if (ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_') ch else '_' }
        .joinToString("")
        .trim()
    return (if (safe.isEmpty()) "project" else safe) + ".zip"
}

/** True for HTML pages, the files that Run shows directly. */
private fun isHtmlFile(path: String): Boolean =
    CoderPaths.nameOf(path).substringAfterLast('.', "").lowercase().let { it == "html" || it == "htm" }

/** True for files the preview can show on their own. */
private fun isPreviewableFile(path: String): Boolean =
    CoderPaths.nameOf(path).substringAfterLast('.', "").lowercase() in PREVIEWABLE_EXTENSIONS

/** True when the project has something to show as a website. Does disk work off the main thread. */
private suspend fun hasRunnableRoot(context: android.content.Context, projectId: String): Boolean {
    val store = (context.applicationContext as NeriboApp).container.coderFileStore
    return withContext(Dispatchers.IO) { CoderPreviewRoot.find(store, projectId) != null }
}

/** The menu items for the Developer link: one to link, or two to change and remove an existing link. */
private fun linkMenuActions(linkState: CoderLinkState, onChoose: () -> Unit, onUnlink: () -> Unit): List<MenuAction> =
    when (linkState) {
        CoderLinkState.Unlinked -> listOf(MenuAction("Link to Developer project", onChoose))
        is CoderLinkState.Linked, CoderLinkState.Missing -> listOf(
            MenuAction("Change linked project", onChoose),
            MenuAction("Unlink from Developer project", onUnlink),
        )
    }

/** One line under the top bar: which Developer project this project is linked to, or that it is gone (with Unlink). Nothing when not linked. */
@Composable
private fun LinkedLabel(state: CoderLinkState, onUnlink: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    when (state) {
        CoderLinkState.Unlinked -> Unit
        is CoderLinkState.Linked -> Text(
            text = "Linked to ${state.name}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen, vertical = spacing.xs),
        )
        CoderLinkState.Missing -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = spacing.screen, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Linked project is no longer available",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUnlink) {
                Text(text = "Unlink", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

/** The Activity behind [this] context, or null when there is none. Used to tell a rotation from leaving the screen. */
private fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** The Run button in the top bar. */
@Composable
private fun RunAction(onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.PlayArrow,
        contentDescription = "Run",
        onClick = onClick,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** The Find button in the top bar; it shows in the accent color while the find bar is open. */
@Composable
private fun FindAction(isOpen: Boolean, onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.Search,
        contentDescription = "Find",
        onClick = onClick,
        tint = if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The AI help button in the top bar. */
@Composable
private fun AiHelpAction(onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.Lightbulb,
        contentDescription = "AI help",
        onClick = onClick,
    )
}

/** Undo, Redo and the save status, shown in the top bar while a file is open. */
@Composable
private fun RowScope.EditorActions(
    canUndo: Boolean,
    canRedo: Boolean,
    status: CoderSaveStatus,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dim = colors.onSurfaceVariant.copy(alpha = 0.38f)
    SaveStatusIndicator(status = status, onRetry = onRetry)
    NeriboIconButton(
        icon = Icons.Outlined.Undo,
        contentDescription = "Undo",
        onClick = { if (canUndo) onUndo() },
        tint = if (canUndo) colors.onSurfaceVariant else dim,
    )
    NeriboIconButton(
        icon = Icons.Outlined.Redo,
        contentDescription = "Redo",
        onClick = { if (canRedo) onRedo() },
        tint = if (canRedo) colors.onSurfaceVariant else dim,
    )
}

@Composable
private fun SaveStatusIndicator(status: CoderSaveStatus, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        CoderSaveStatus.Saving -> Text(
            text = "Saving\u2026",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        CoderSaveStatus.Saved -> Text(
            text = "Saved",
            style = MaterialTheme.typography.labelSmall,
            color = NeriboTheme.extraColors.success,
        )
        CoderSaveStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Couldn't save",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
            TextButton(onClick = onRetry) {
                Text(text = "Retry", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
