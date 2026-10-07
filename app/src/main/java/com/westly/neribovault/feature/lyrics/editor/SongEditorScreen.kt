package com.westly.neribovault.feature.lyrics.editor

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.feature.lyrics.LyricsRoutes
import com.westly.neribovault.feature.lyrics.UNTITLED_SONG
import com.westly.neribovault.feature.lyrics.countLabel
import com.westly.neribovault.feature.lyrics.engine.LyricsAnalysis
import com.westly.neribovault.feature.lyrics.engine.LyricsStats
import com.westly.neribovault.feature.lyrics.engine.SongSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHEET_NONE = 0
private const val SHEET_CHANGE = 1
private const val SHEET_ADD = 2

/**
 * The song writing screen: one block per section, type chips, automatic verse numbering,
 * "Enter twice" for a new section, undo and redo, and automatic saving.
 */
@Composable
fun SongEditorScreen(
    songId: String,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenPerformance: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    val vm = neriboViewModel(key = "lyrics-editor-$songId") { c ->
        SongEditorViewModel(c.songsRepository, songId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var leaving by remember { mutableStateOf(false) }
    var sheetMode by rememberSaveable { mutableStateOf(SHEET_NONE) }
    var sheetTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    LifecycleSaveEffect(onSave = { vm.flush() })

    // The Tools screen asks the editor to jump to a section through the back stack entry.
    val savedStateHandle = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.savedStateHandle
    val jumpFlow: StateFlow<Int?> = remember(savedStateHandle) {
        savedStateHandle?.getStateFlow<Int?>(LyricsRoutes.KEY_JUMP_TO_SECTION, null) ?: MutableStateFlow<Int?>(null)
    }
    val jumpIndex by jumpFlow.collectAsStateWithLifecycle()
    LaunchedEffect(jumpIndex) {
        val index = jumpIndex
        if (index != null) {
            vm.jumpToSection(index)
            savedStateHandle?.remove<Int>(LyricsRoutes.KEY_JUMP_TO_SECTION)
        }
    }

    // Keep the focused section on screen when focus moves.
    LaunchedEffect(state.focusTarget?.token) {
        val target = state.focusTarget ?: return@LaunchedEffect
        val index = state.sections.indexOfFirst { it.id == target.sectionId }
        if (index >= 0) ensureVisible(listState, index)
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(300)
            val index = state.sections.indexOfFirst { it.id == state.focusedId }
            if (index >= 0) ensureVisible(listState, index)
        }
    }

    // The quiet footer: lines and words, worked out off the main thread after a short pause.
    var stats by remember { mutableStateOf(LyricsStats(0, 0, 0, 0, 0)) }
    LaunchedEffect(state.sections) {
        val sections = state.sections
        delay(300)
        stats = withContext(Dispatchers.Default) {
            LyricsAnalysis.stats(sections.map { SongSection(it.type, it.label, it.text) })
        }
    }

    fun navigateAfterSave(open: () -> Unit) {
        scope.launch {
            vm.flushNow()
            open()
        }
    }

    val menuActions = listOf(
        MenuAction("Song details", { navigateAfterSave(onOpenDetails) }, Icons.Outlined.Info),
        MenuAction("Performance mode", { navigateAfterSave(onOpenPerformance) }, Icons.Outlined.PlayArrow),
        MenuAction("Tools", { navigateAfterSave(onOpenTools) }, Icons.Outlined.ViewList),
        MenuAction("Lyric sheet", { navigateAfterSave(onOpenSheet) }, Icons.Outlined.Description),
        MenuAction(
            label = "Copy lyrics",
            onClick = {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val lyrics = vm.currentLyrics()
                    if (lyrics.isBlank()) {
                        snackbarHostState.showSnackbar("Nothing to copy yet")
                    } else {
                        val header = buildString {
                            append(state.title.trim().ifEmpty { UNTITLED_SONG })
                            if (state.writer.isNotBlank()) append("\nby ").append(state.writer.trim())
                        }
                        context.copyToClipboard("Lyrics", header + "\n\n" + lyrics)
                        snackbarHostState.showSnackbar("Copied")
                    }
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete song",
            onClick = {
                scope.launch {
                    leaving = true
                    vm.deleteSong()
                    onBack()
                }
            },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    val ready = !state.isLoading && !state.notFound
    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = state.title.trim().ifEmpty { UNTITLED_SONG },
                onBack = {
                    scope.launch {
                        vm.flushNow()
                        onBack()
                    }
                },
                subtitle = if (!ready) null else if (state.isSaving) "Saving\u2026" else "Saved",
                actions = {
                    if (ready) {
                        HistoryButton(Icons.Outlined.Undo, "Undo", state.canUndo) { vm.undo() }
                        HistoryButton(Icons.Outlined.Redo, "Redo", state.canRedo) { vm.redo() }
                        OverflowMenu(actions = menuActions)
                    }
                },
            )
        },
        bottomBar = {
            if (!leaving && ready && state.focusedId != null) {
                AddSectionKeyboardRow(
                    onAdd = { type -> vm.addSection(type, "", state.focusedId) },
                    onMore = {
                        sheetTargetId = state.focusedId
                        sheetMode = SHEET_ADD
                    },
                )
            }
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                leaving -> Unit
                state.isLoading -> LoadingState()
                state.notFound -> EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "Song not found",
                    message = "This song may have been deleted or moved.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                else -> SectionList(
                    state = state,
                    stats = stats,
                    listState = listState,
                    vm = vm,
                    onOpenTypeSheet = { id ->
                        sheetTargetId = id
                        sheetMode = SHEET_CHANGE
                    },
                    onOpenAddSheet = {
                        sheetTargetId = null
                        sheetMode = SHEET_ADD
                    },
                )
            }
        }
    }

    val sheetTarget = state.sections.firstOrNull { it.id == sheetTargetId }
    if (sheetMode == SHEET_CHANGE && sheetTarget != null) {
        SectionTypeSheet(
            title = "Section type",
            current = sheetTarget.type,
            currentLabel = sheetTarget.label,
            onChoose = { type, label ->
                vm.changeType(sheetTarget.id, type, label)
                sheetMode = SHEET_NONE
            },
            onDismiss = { sheetMode = SHEET_NONE },
        )
    } else if (sheetMode == SHEET_ADD) {
        SectionTypeSheet(
            title = "Add a section",
            current = null,
            currentLabel = "",
            onChoose = { type, label ->
                vm.addSection(type, label, sheetTargetId)
                sheetMode = SHEET_NONE
            },
            onDismiss = { sheetMode = SHEET_NONE },
        )
    }
}

/** Scrolls so the item at [index] is on screen, doing nothing when it already is. */
private suspend fun ensureVisible(listState: LazyListState, index: Int) {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        listState.scrollToItem(index)
        return
    }
    val overflowBottom = item.offset + item.size - info.viewportEndOffset
    val overflowTop = info.viewportStartOffset - item.offset
    if (overflowBottom > 0) {
        listState.animateScrollBy(overflowBottom.toFloat())
    } else if (overflowTop > 0) {
        listState.animateScrollBy(-overflowTop.toFloat())
    }
}

@Composable
private fun SectionList(
    state: SongEditorUiState,
    stats: LyricsStats,
    listState: LazyListState,
    vm: SongEditorViewModel,
    onOpenTypeSheet: (String) -> Unit,
    onOpenAddSheet: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val labels = remember(state.sections) { EditorRules.labelsOf(state.sections) }
    val lastIndex = state.sections.lastIndex
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.md,
                bottom = 160.dp,
            ),
        ) {
            itemsIndexed(state.sections, key = { _, section -> section.id }) { index, section ->
                val target = state.focusTarget
                val flash = state.flash
                val menuActions = remember(section.id, index == 0, index == lastIndex, vm) {
                    buildList<MenuAction> {
                        add(MenuAction("Duplicate", { vm.duplicateSection(section.id) }))
                        if (index > 0) add(MenuAction("Move up", { vm.moveSectionUp(section.id) }))
                        if (index < lastIndex) add(MenuAction("Move down", { vm.moveSectionDown(section.id) }))
                        add(MenuAction("Delete", { vm.deleteSection(section.id) }, destructive = true))
                    }
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (index > 0) Spacer(modifier = Modifier.height(28.dp))
                    SectionBlock(
                        section = section,
                        label = labels.getOrElse(index) { "" },
                        isFocused = state.focusedId == section.id,
                        focusTarget = if (target != null && target.sectionId == section.id) target else null,
                        flashToken = if (flash != null && flash.sectionId == section.id) flash.token else 0L,
                        syncRevision = state.syncRevision,
                        menuActions = menuActions,
                        onTypeClick = { onOpenTypeSheet(section.id) },
                        onEdit = { edit -> vm.onFieldEdit(section.id, edit) },
                        onFocused = { vm.onFocused(section.id) },
                        onCursor = { offset -> vm.reportCursor(offset) },
                        consumeCursor = { request -> vm.consumeCursor(request) },
                    )
                }
            }
            item(key = "add-bar") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(28.dp))
                    AddSectionBar(
                        onAdd = { type -> vm.addSection(type, "", null) },
                        onMore = onOpenAddSheet,
                    )
                }
            }
            item(key = "footer") {
                Text(
                    text = countLabel(stats.lines, "line") + " \u00B7 " + countLabel(stats.words, "word"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
                )
            }
        }
    }
}

@Composable
private fun HistoryButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
        )
    }
}
