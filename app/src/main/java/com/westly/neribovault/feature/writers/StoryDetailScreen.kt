package com.westly.neribovault.feature.writers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.feature.writers.characters.StoryCharactersTab
import com.westly.neribovault.feature.writers.components.ChapterRow
import com.westly.neribovault.feature.writers.components.RenameChapterDialog
import com.westly.neribovault.feature.writers.components.StoryProgress
import com.westly.neribovault.feature.writers.components.StoryTabRow
import com.westly.neribovault.feature.writers.notes.StoryNotesTab
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One story: a header with synopsis, genre, status and progress, then the Chapters, Characters
 * and Notes tabs. The New chapter button only appears on the Chapters tab.
 */
@Composable
fun StoryDetailScreen(
    storyId: String,
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onEditStory: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onNewChapter: () -> Unit,
    onOpenCharacter: (characterId: String) -> Unit,
    onOpenNote: (noteId: String) -> Unit,
    onStoryDeleted: (deletedId: String) -> Unit,
) {
    val vm = neriboViewModel(key = storyId) { c -> StoryDetailViewModel(storyId, c.storiesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    // A chapter deleted inside the chapter editor arrives here as a saved-state value.
    LaunchedEffect(deletedIdFlow) {
        deletedIdFlow.collect { id ->
            if (id != null) {
                onDeletedIdConsumed()
                showUndo("Chapter deleted") { vm.restoreChapter(id) }
            }
        }
    }

    val story = state.story
    if (story == null) {
        // Still loading, or the story was just deleted and the screen is closing.
        NeriboScaffold(
            topBar = { NeriboTopBar(title = "Story", onBack = onBack) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val exportStory: () -> Unit = {
        val text = vm.exportText()
        if (text == null) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Add a chapter first, then export.")
            }
        } else {
            val shared = runCatching { context.shareText(story.title.ifBlank { null }, text) }
            if (shared.isFailure) {
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("This story is too long to share as text.")
                }
            }
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = story.title.ifBlank { "Untitled story" },
                onBack = onBack,
                actions = {
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Edit details",
                                onClick = onEditStory,
                                icon = Icons.Outlined.Edit,
                            ),
                            MenuAction(
                                label = "Export as text",
                                onClick = exportStory,
                                icon = Icons.Outlined.Share,
                            ),
                            MenuAction(
                                label = "Delete",
                                onClick = { vm.deleteStory(onDone = onStoryDeleted) },
                                icon = Icons.Outlined.Delete,
                                destructive = true,
                            ),
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            if (state.tab == StoryTab.Chapters) {
                NeriboFab(
                    onClick = onNewChapter,
                    contentDescription = "New chapter",
                    text = "New chapter",
                )
            }
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            StoryHeader(story = story, totalWords = state.totalWords)
            StoryTabRow(
                labels = StoryTab.values().map { it.label },
                selectedIndex = state.tab.ordinal,
                onSelect = { index -> vm.selectTab(StoryTab.values()[index]) },
                modifier = Modifier.padding(horizontal = NeriboTheme.spacing.screen),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.tab) {
                    StoryTab.Chapters -> ChaptersTab(
                        chapters = state.chapters,
                        onOpenChapter = onOpenChapter,
                        onNewChapter = onNewChapter,
                        onRename = { id -> renameTargetId = id },
                        onMove = { id, direction -> vm.moveChapter(id, direction) },
                        onDelete = { id ->
                            vm.deleteChapter(id)
                            scope.launch { showUndo("Chapter deleted") { vm.restoreChapter(id) } }
                        },
                    )
                    StoryTab.Characters -> StoryCharactersTab(
                        storyId = storyId,
                        onOpenCharacter = onOpenCharacter,
                        modifier = Modifier.fillMaxSize(),
                    )
                    StoryTab.Notes -> StoryNotesTab(
                        storyId = storyId,
                        onOpenNote = onOpenNote,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    renameTargetId?.let { id ->
        val target = state.chapters.firstOrNull { it.id == id }
        if (target == null) {
            renameTargetId = null
        } else {
            RenameChapterDialog(
                initialTitle = target.title,
                onConfirm = { newTitle ->
                    vm.renameChapter(id, newTitle)
                    renameTargetId = null
                },
                onDismiss = { renameTargetId = null },
            )
        }
    }
}

@Composable
private fun StoryHeader(story: StoryEntity, totalWords: Int) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen)
            .padding(top = spacing.xs, bottom = spacing.md),
    ) {
        val synopsis = story.synopsis.trim()
        if (synopsis.isNotEmpty()) {
            Text(
                text = synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(top = if (synopsis.isEmpty()) 0.dp else spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = story.genre,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            StatusBadge(
                text = StoryOptions.statusLabel(story.status),
                tone = StoryOptions.statusTone(story.status),
            )
        }
        StoryProgress(
            words = totalWords,
            target = story.targetWordCount,
            modifier = Modifier.padding(top = spacing.md),
        )
    }
}

@Composable
private fun ChaptersTab(
    chapters: List<StoryChapterEntity>,
    onOpenChapter: (String) -> Unit,
    onNewChapter: () -> Unit,
    onRename: (String) -> Unit,
    onMove: (chapterId: String, direction: Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    if (chapters.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Book,
            title = "No chapters yet",
            message = "Chapter one is waiting. Begin whenever the words come.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New chapter",
            onAction = onNewChapter,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.md,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
            ChapterRow(
                number = index + 1,
                chapter = chapter,
                canMoveUp = index > 0,
                canMoveDown = index < chapters.lastIndex,
                onClick = { onOpenChapter(chapter.id) },
                onRename = { onRename(chapter.id) },
                onMoveUp = { onMove(chapter.id, -1) },
                onMoveDown = { onMove(chapter.id, 1) },
                onDelete = { onDelete(chapter.id) },
            )
        }
    }
}
