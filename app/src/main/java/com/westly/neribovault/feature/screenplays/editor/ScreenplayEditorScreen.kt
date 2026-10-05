package com.westly.neribovault.feature.screenplays.editor

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
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
import com.westly.neribovault.feature.screenplays.ScreenplaysRoutes
import com.westly.neribovault.feature.screenplays.UNTITLED_SCREENPLAY
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The screenplay writing screen: one block per paragraph, an element bar and suggestions above the
 * keyboard, smart Enter and Backspace, undo and redo, and automatic saving.
 */
@Composable
fun ScreenplayEditorScreen(
    screenplayId: String,
    onBack: () -> Unit,
    onOpenTitlePage: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenPreview: () -> Unit,
) {
    val vm = neriboViewModel(key = "screenplay-editor-$screenplayId") { c ->
        ScreenplayEditorViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var leaving by remember { mutableStateOf(false) }

    LifecycleSaveEffect(onSave = { vm.flush() })

    // The Scenes screen asks the editor to jump to a block through the back stack entry.
    val savedStateHandle = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.savedStateHandle
    val jumpFlow: StateFlow<Int?> = remember(savedStateHandle) {
        savedStateHandle?.getStateFlow<Int?>(ScreenplaysRoutes.KEY_JUMP_TO_BLOCK, null) ?: MutableStateFlow<Int?>(null)
    }
    val jumpIndex by jumpFlow.collectAsStateWithLifecycle()
    LaunchedEffect(jumpIndex) {
        val index = jumpIndex
        if (index != null) {
            vm.jumpToBlock(index)
            savedStateHandle?.remove<Int>(ScreenplaysRoutes.KEY_JUMP_TO_BLOCK)
        }
    }

    // Keep the focused block on screen when focus moves; drop the text focus for page breaks.
    LaunchedEffect(state.focusTarget?.token) {
        val target = state.focusTarget ?: return@LaunchedEffect
        if (!target.textFocus) focusManager.clearFocus()
        val index = state.blocks.indexOfFirst { it.id == target.blockId }
        if (index >= 0) ensureVisible(listState, index)
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(300)
            val index = state.blocks.indexOfFirst { it.id == state.focusedId }
            if (index >= 0) ensureVisible(listState, index)
        }
    }

    fun navigateAfterSave(open: () -> Unit) {
        scope.launch {
            vm.flushNow()
            open()
        }
    }

    val menuActions = listOf(
        MenuAction("Title page", { navigateAfterSave(onOpenTitlePage) }, Icons.Outlined.Description),
        MenuAction("Scenes and characters", { navigateAfterSave(onOpenScenes) }, Icons.Outlined.ViewList),
        MenuAction("Script stats", { navigateAfterSave(onOpenStats) }, Icons.Outlined.Info),
        MenuAction("Preview and export", { navigateAfterSave(onOpenPreview) }, Icons.Outlined.Share),
        MenuAction("Insert page break", { vm.insertPageBreak() }, Icons.Outlined.Add),
        MenuAction(
            label = "Copy as Fountain text",
            onClick = {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val text = vm.currentFountain()
                    if (text.isBlank()) {
                        snackbarHostState.showSnackbar("Nothing to copy yet")
                    } else {
                        context.copyToClipboard("Screenplay", text)
                        snackbarHostState.showSnackbar("Copied")
                    }
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete screenplay",
            onClick = {
                scope.launch {
                    leaving = true
                    vm.deleteScreenplay()
                    onBack()
                }
            },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    val focusedIndex = state.blocks.indexOfFirst { it.id == state.focusedId }
    val focusedBlock = if (focusedIndex >= 0) state.blocks[focusedIndex] else null
    val suggestions = remember(state.blocks, state.focusedId) {
        if (focusedBlock == null) emptyList() else EditorRules.suggestions(state.blocks, state.focusedId)
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = state.title.ifBlank { UNTITLED_SCREENPLAY },
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = if (state.isLoading || state.notFound) null else if (state.isSaving) "Saving\u2026" else "Saved",
                actions = {
                    if (!state.isLoading && !state.notFound) {
                        HistoryButton(Icons.Outlined.Undo, "Undo", state.canUndo) { vm.undo() }
                        HistoryButton(Icons.Outlined.Redo, "Redo", state.canRedo) { vm.redo() }
                        OverflowMenu(actions = menuActions)
                    }
                },
            )
        },
        bottomBar = {
            if (!leaving && focusedBlock != null) {
                BottomPanel(
                    focused = focusedBlock,
                    previousType = state.blocks.getOrNull(focusedIndex - 1)?.type,
                    suggestions = suggestions,
                    onSuggestion = { vm.applySuggestion(it.newText) },
                    onChangeType = { vm.changeType(it) },
                    onDeletePageBreak = { vm.deletePageBreak() },
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
                    icon = Icons.Outlined.Movie,
                    title = "Screenplay not found",
                    message = "It may have been deleted or moved.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                else -> BlockList(
                    state = state,
                    listState = listState,
                    vm = vm,
                )
            }
        }
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
private fun BlockList(
    state: EditorUiState,
    listState: LazyListState,
    vm: ScreenplayEditorViewModel,
) {
    val spacing = NeriboTheme.spacing
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentWidth = maxWidth - spacing.screen * 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.md,
                bottom = 120.dp,
            ),
        ) {
            itemsIndexed(state.blocks, key = { _, block -> block.id }) { index, block ->
                val target = state.focusTarget
                val flash = state.flash
                BlockRow(
                    block = block,
                    previousType = if (index > 0) state.blocks[index - 1].type else null,
                    contentWidth = contentWidth,
                    isFocused = state.focusedId == block.id,
                    focusTarget = if (target != null && target.blockId == block.id) target else null,
                    flashToken = if (flash != null && flash.blockId == block.id) flash.token else 0L,
                    syncRevision = state.syncRevision,
                    onEdit = { edit -> vm.onFieldEdit(block.id, edit) },
                    onFocused = { vm.onFocused(block.id) },
                    onPageBreakTap = { vm.focusPageBreak(block.id) },
                    onCursor = { offset -> vm.reportCursor(offset) },
                    consumeCursor = { request -> vm.consumeCursor(request) },
                )
            }
            item(key = "tail") {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { vm.onTapBelow() },
                        ),
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
