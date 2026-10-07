package com.westly.neribovault.feature.lyrics.sheet

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.shareText
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MAX_PAGE_WIDTH_PX = 1200

/** Shows the real PDF pages of the lyric sheet; saves, shares or copies the song. */
@Composable
fun LyricSheetScreen(songId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val previewDir = File(context.applicationContext.cacheDir, "preview")
    val vm = neriboViewModel(key = "lyrics-sheet-$songId") { c ->
        LyricSheetViewModel(c.songsRepository, songId, previewDir)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var large by rememberSaveable { mutableStateOf(false) }
    var showLabels by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(large, showLabels) {
        vm.setOptions(LyricSheetOptions(large = large, showLabels = showLabels))
    }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val pageWidthPx = (configuration.screenWidthDp * density.density).roundToInt()
        .coerceIn(1, MAX_PAGE_WIDTH_PX)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) vm.savePdf(context.contentResolver, uri)
    }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // The list starts with the info card (index 0), so page N is item N.
    val firstVisiblePage by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceAtLeast(1) }
    }
    val ready = state.phase == SheetPhase.Ready
    val subtitle = if (ready && state.pageCount > 0) {
        "Page ${firstVisiblePage.coerceAtMost(state.pageCount)} of ${state.pageCount}"
    } else {
        null
    }
    val shareTitle = state.title.trim().ifEmpty { "Lyrics" }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Lyric sheet",
                onBack = onBack,
                subtitle = subtitle,
                actions = {
                    NeriboButton(
                        text = "Save PDF",
                        onClick = { saveLauncher.launch(pdfFileName(state.title)) },
                        enabled = ready && !state.isSaving,
                        style = ButtonStyle.Text,
                        leadingIcon = Icons.Outlined.Save,
                    )
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Share PDF",
                                onClick = { if (ready) vm.sharePdf(context) },
                                icon = Icons.Outlined.Share,
                            ),
                            MenuAction(
                                label = "Copy as text",
                                onClick = {
                                    scope.launch {
                                        val text = vm.plainText() ?: return@launch
                                        context.copyToClipboard("Lyrics", text)
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar("Copied")
                                    }
                                },
                                icon = Icons.Outlined.ContentCopy,
                            ),
                            MenuAction(
                                label = "Share as text",
                                onClick = {
                                    scope.launch {
                                        val text = vm.plainText() ?: return@launch
                                        try {
                                            context.shareText(shareTitle, text)
                                        } catch (e: Exception) {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            snackbarHostState.showSnackbar("Could not share the text.")
                                        }
                                    }
                                },
                                icon = Icons.Outlined.Description,
                            ),
                        ),
                    )
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.phase != SheetPhase.Empty) {
                OptionsCard(
                    large = large,
                    onLargeChange = { large = it },
                    showLabels = showLabels,
                    onShowLabelsChange = { showLabels = it },
                )
            }
            when (state.phase) {
                SheetPhase.Loading -> LoadingWithText(Modifier.weight(1f).fillMaxWidth())
                SheetPhase.Empty -> EmptyState(
                    icon = Icons.Outlined.Description,
                    title = "Nothing to preview yet",
                    message = "Write a few lines first, then come back.",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    actionLabel = "Back",
                    onAction = onBack,
                )
                SheetPhase.Error -> EmptyState(
                    icon = Icons.Outlined.Info,
                    title = "Could not prepare the pages",
                    message = "Something went wrong while building your PDF.",
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    actionLabel = "Retry",
                    onAction = { vm.retry() },
                )
                SheetPhase.Ready -> PagePreviewList(
                    pageCount = state.pageCount,
                    generation = state.generation,
                    pageWidthPx = pageWidthPx,
                    listState = listState,
                    loadPage = { index, width -> vm.loadPage(index, width) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xxl),
                )
            }
        }
    }
}

/** Text size chips and the section-label switch. */
@Composable
private fun OptionsCard(
    large: Boolean,
    onLargeChange: (Boolean) -> Unit,
    showLabels: Boolean,
    onShowLabelsChange: (Boolean) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    NeriboCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.sm),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "Text size",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                NeriboChip(label = "Normal", selected = !large, onClick = { onLargeChange(false) })
                NeriboChip(label = "Large", selected = large, onClick = { onLargeChange(true) })
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Show section labels",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = showLabels, onCheckedChange = onShowLabelsChange)
            }
        }
    }
}

@Composable
private fun LoadingWithText(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        LoadingState()
        Text(
            text = "Preparing your pages...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 72.dp),
        )
    }
}

/**
 * The info card followed by one white card per PDF page on a neutral background. Pages are
 * rendered only when they scroll into view. Page cards stay white in dark mode.
 */
@Composable
private fun PagePreviewList(
    pageCount: Int,
    generation: Int,
    pageWidthPx: Int,
    listState: LazyListState,
    loadPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val spacing = NeriboTheme.spacing
    LazyColumn(
        state = listState,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "info") {
            NeriboCard(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen)) {
                Text(
                    text = "This is how your lyric sheet will look. Every page carries: " +
                        LyricSheetPdfWriter.FOOTER_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }
        items(items = (0 until pageCount).toList(), key = { "page-$it" }) { index ->
            PageCard(
                index = index,
                generation = generation,
                pageWidthPx = pageWidthPx,
                loadPage = loadPage,
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
        }
    }
}

@Composable
private fun PageCard(
    index: Int,
    generation: Int,
    pageWidthPx: Int,
    loadPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, generation, pageWidthPx) {
        val bitmap = loadPage(index, pageWidthPx)
        if (bitmap != null) image = bitmap.asImageBitmap()
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(595f / 842f)
            .clip(shape)
            .background(Color.White, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    ) {
        val shown = image
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
