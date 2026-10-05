package com.westly.neribovault.feature.screenplays.editor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.feature.screenplays.UNTITLED_SCREENPLAY
import com.westly.neribovault.feature.screenplays.components.ScreenplayDetailsDialog
import com.westly.neribovault.feature.screenplays.countLabel
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import kotlinx.coroutines.launch

/**
 * A read-only viewer that part S2 replaces with the real editor. It draws a screenplay in
 * screenplay format. The four tool callbacks are accepted for the final signature but unused here.
 * Deleting hands the id back through [onBack]'s caller, which shows the Undo snackbar on the list.
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
    val vm = neriboViewModel(key = "screenplay-viewer-$screenplayId") { c ->
        ScreenplayViewerViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val screenplay = state.screenplay

    val menuActions = if (screenplay == null) {
        emptyList()
    } else {
        listOf(
            MenuAction(
                label = "Copy as Fountain text",
                onClick = {
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        if (screenplay.content.isBlank()) {
                            snackbarHostState.showSnackbar("Nothing to copy yet")
                        } else {
                            context.copyToClipboard("Screenplay", screenplay.content)
                            snackbarHostState.showSnackbar("Copied")
                        }
                    }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
            MenuAction(
                label = "Details",
                onClick = { showDetails = true },
                icon = Icons.Outlined.Info,
            ),
            MenuAction(
                label = "Delete",
                onClick = {
                    scope.launch {
                        leaving = true
                        vm.softDelete()
                        onBack()
                    }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
    }

    val subtitle = if (screenplay == null) {
        null
    } else if (state.counts.isEmpty) {
        "Empty"
    } else {
        countLabel(state.counts.pages, "page") + " \u00B7 " +
            countLabel(state.counts.scenes, "scene") + " \u00B7 " +
            countLabel(state.counts.characters, "character")
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = screenplay?.title?.ifBlank { UNTITLED_SCREENPLAY } ?: "Screenplay",
                onBack = onBack,
                subtitle = subtitle,
                actions = {
                    if (menuActions.isNotEmpty()) OverflowMenu(actions = menuActions)
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                leaving -> Unit
                state.isLoading -> LoadingState()
                screenplay == null -> EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "Screenplay not found",
                    message = "It may have been deleted or moved.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                state.blocks.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "This screenplay is empty",
                    message = "The writing screen arrives in the next update.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> ScriptViewer(blocks = state.blocks)
            }
        }
    }

    if (showDetails && screenplay != null) {
        ScreenplayDetailsDialog(
            isNew = false,
            initialTitle = screenplay.title,
            initialAuthor = screenplay.author,
            onDismiss = { showDetails = false },
            onConfirm = { title, author ->
                vm.updateDetails(title, author)
                showDetails = false
            },
        )
    }
}

/** The parsed blocks, drawn the way the editor will draw them, without any editing. */
@Composable
private fun ScriptViewer(blocks: List<ScriptBlock>) {
    val spacing = NeriboTheme.spacing
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val contentWidth = maxWidth - spacing.screen * 2
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.md,
                bottom = 48.dp,
            ),
        ) {
            itemsIndexed(blocks) { index, block ->
                val previous: ScriptBlock? = if (index > 0) blocks[index - 1] else null
                ScriptBlockView(block = block, previous = previous, contentWidth = contentWidth)
            }
        }
    }
}

@Composable
private fun ScriptBlockView(block: ScriptBlock, previous: ScriptBlock?, contentWidth: Dp) {
    if (block.type == BlockType.PAGE_BREAK) {
        PageBreakDivider()
        return
    }
    val previousType = previous?.type
    val followsCue = (block.type == BlockType.PARENTHETICAL || block.type == BlockType.DIALOGUE) &&
        (
            previousType == BlockType.CHARACTER ||
                previousType == BlockType.PARENTHETICAL ||
                previousType == BlockType.DIALOGUE
            )
    val top = when {
        block.type == BlockType.SCENE_HEADING -> 24.dp
        followsCue -> 0.dp
        else -> 12.dp
    }
    val startFraction = when (block.type) {
        BlockType.CHARACTER -> 0.367f
        BlockType.PARENTHETICAL -> 0.267f
        BlockType.DIALOGUE -> 0.167f
        else -> 0f
    }
    val endFraction = when (block.type) {
        BlockType.PARENTHETICAL -> 0.333f
        BlockType.DIALOGUE -> 0.25f
        else -> 0f
    }
    val shown = when (block.type) {
        BlockType.SCENE_HEADING, BlockType.CHARACTER, BlockType.TRANSITION -> block.text.uppercase()
        BlockType.PARENTHETICAL -> "(" + block.text + ")"
        else -> block.text
    }
    val rightAligned = block.type == BlockType.TRANSITION &&
        !block.text.trim().equals("FADE IN:", ignoreCase = true)

    Text(
        text = shown,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = contentWidth * startFraction,
                end = contentWidth * endFraction,
                top = top,
            ),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = if (block.type == BlockType.SCENE_HEADING) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = if (rightAligned) TextAlign.End else TextAlign.Start,
        ),
    )
}

@Composable
private fun PageBreakDivider() {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboDivider(modifier = Modifier.weight(1f))
        Text(
            text = "PAGE BREAK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.sm),
        )
        NeriboDivider(modifier = Modifier.weight(1f))
    }
}
