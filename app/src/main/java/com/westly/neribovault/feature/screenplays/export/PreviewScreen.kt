package com.westly.neribovault.feature.screenplays.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.feature.screenplays.share.ScriptShareViewModel
import java.io.File
import kotlin.math.roundToInt

private const val MAX_PAGE_WIDTH_PX = 1200

/** Shows the real PDF pages and lets the owner save the PDF to the phone. */
@Composable
fun PreviewScreen(screenplayId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val previewDir = File(context.applicationContext.cacheDir, "preview")
    val vm = neriboViewModel(key = "preview-$screenplayId") { c ->
        PreviewViewModel(c.screenplaysRepository, screenplayId, previewDir)
    }
    val shareVm = neriboViewModel(key = "share-$screenplayId") { c ->
        ScriptShareViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val pageWidthPx = (configuration.screenWidthDp * density.density).roundToInt()
        .coerceIn(1, MAX_PAGE_WIDTH_PX)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) vm.savePdf(context.contentResolver, uri)
    }

    val fountainLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) shareVm.exportFountain(context.contentResolver, uri)
    }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(shareVm) {
        shareVm.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val firstVisiblePage by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceAtLeast(1) }
    }
    val ready = state.phase == PreviewPhase.Ready
    val subtitle = if (ready && state.pageCount > 0) {
        "Page ${firstVisiblePage.coerceAtMost(state.pageCount)} of ${state.pageCount}"
    } else {
        null
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Preview",
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
                                onClick = { shareVm.startShare(context) },
                                icon = Icons.Outlined.Share,
                            ),
                            MenuAction(
                                label = "Export .fountain file",
                                onClick = {
                                    fountainLauncher.launch(pdfFileName(state.title).removeSuffix(".pdf") + ".fountain")
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
        when (state.phase) {
            PreviewPhase.Loading -> LoadingWithText(Modifier.padding(padding))
            PreviewPhase.Empty -> EmptyState(
                icon = Icons.Outlined.Description,
                title = "Nothing to preview yet",
                message = "Write a scene first, then come back.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Back to the editor",
                onAction = onBack,
            )
            PreviewPhase.Error -> EmptyState(
                icon = Icons.Outlined.Info,
                title = "Could not prepare the pages",
                message = "Something went wrong while building your PDF.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Retry",
                onAction = { vm.retry() },
            )
            PreviewPhase.Ready -> PagePreviewList(
                pageCount = state.pageCount,
                generation = state.generation,
                pageWidthPx = pageWidthPx,
                listState = listState,
                loadPage = { index, width -> vm.loadPage(index, width) },
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(top = spacing.md, bottom = spacing.xxl),
            )
        }
    }
}

@Composable
private fun LoadingWithText(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingState()
        Text(
            text = "Preparing your pages...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 72.dp),
        )
    }
}
