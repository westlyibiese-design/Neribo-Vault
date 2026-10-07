package com.westly.neribovault.feature.lyrics.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.feature.lyrics.UNTITLED_SONG
import com.westly.neribovault.feature.lyrics.components.SongDetailsDialog
import com.westly.neribovault.feature.lyrics.countLabel
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import kotlinx.coroutines.launch

/**
 * A read-only viewer that shows a song section by section. Part L2 replaces this file with the
 * writing screen and keeps this exact signature; the four "open" callbacks are not used yet.
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
    val vm = neriboViewModel(key = "lyrics-viewer-$songId") { c -> SongViewerViewModel(c.songsRepository, songId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val song = state.song

    val menuActions = buildList<MenuAction> {
        if (song != null) {
            add(
                MenuAction(
                    label = "Copy lyrics",
                    onClick = {
                        val header = buildString {
                            append(song.title.trim().ifEmpty { UNTITLED_SONG })
                            if (song.writer.isNotBlank()) append("\nby ").append(song.writer.trim())
                        }
                        context.copyToClipboard("Lyrics", header + "\n\n" + LyricsFormat.serialize(state.sections))
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Copied")
                        }
                    },
                    icon = Icons.Outlined.ContentCopy,
                ),
            )
            add(
                MenuAction(
                    label = "Details",
                    onClick = { showDetails = true },
                    icon = Icons.Outlined.Info,
                ),
            )
            add(
                MenuAction(
                    label = "Delete",
                    onClick = {
                        leaving = true
                        scope.launch {
                            vm.delete()
                            onBack()
                        }
                    },
                    icon = Icons.Outlined.Delete,
                    destructive = true,
                ),
            )
        }
    }

    val stats = state.stats
    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = song?.title?.trim()?.ifEmpty { UNTITLED_SONG } ?: "Song",
                onBack = onBack,
                subtitle = if (song != null) {
                    countLabel(stats.sections, "section") + " \u00B7 " +
                        countLabel(stats.lines, "line") + " \u00B7 " +
                        countLabel(stats.words, "word")
                } else {
                    null
                },
                actions = {
                    if (menuActions.isNotEmpty()) OverflowMenu(actions = menuActions)
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                leaving || state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
                song == null -> EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "Song not found",
                    message = "This song may have been deleted or moved.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                state.sections.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "This song is empty",
                    message = "The writing screen arrives in the next update.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> SongText(
                    sectionTexts = state.sections.map { it.text },
                    labels = state.labels,
                )
            }
        }
    }

    if (showDetails && song != null) {
        SongDetailsDialog(
            isNew = false,
            initialTitle = song.title,
            initialWriter = song.writer,
            onDismiss = { showDetails = false },
            onConfirm = { title, writer ->
                vm.updateDetails(title, writer)
                showDetails = false
            },
        )
    }
}

/** The song text look: a small upper-case label, the serif lines, then 28dp before the next section. */
@Composable
private fun SongText(sectionTexts: List<String>, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.md,
            bottom = spacing.xxl,
        ),
    ) {
        itemsIndexed(sectionTexts, key = { index, _ -> index }) { index, text ->
            Text(
                text = labels.getOrElse(index) { "" }.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xs),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Serif,
                    fontSize = 18.sp,
                    lineHeight = 28.sp,
                ),
                color = colors.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
