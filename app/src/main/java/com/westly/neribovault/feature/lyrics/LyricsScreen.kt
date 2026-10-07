package com.westly.neribovault.feature.lyrics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.core.lock.rememberVaultLockEnabled
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.feature.lyrics.components.SongCard
import com.westly.neribovault.feature.lyrics.components.SongDetailsDialog
import com.westly.neribovault.feature.lyrics.engine.CheckResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Lyrics list: search, status filters, one card per song, create, edit details, duplicate and
 * delete with Undo. Debug builds also get the sample songs and the engine self-test in the overflow menu.
 */
@Composable
fun LyricsScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenSong: (String) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> LyricsViewModel(c.songsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val lockEnabled by rememberVaultLockEnabled(VAULT_ID)
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf(state.query) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    LaunchedEffect(deletedIdFlow) {
        deletedIdFlow.collect { id ->
            if (id != null) {
                onDeletedIdConsumed()
                showUndo("Moved to Recently deleted") { vm.restore(id) }
            }
        }
    }

    LaunchedEffect(state.isSearchOpen, focusSearchOnOpen) {
        if (state.isSearchOpen && focusSearchOnOpen) {
            focusSearchOnOpen = false
            runCatching { searchFocus.requestFocus() }
        }
    }

    BackHandler(enabled = state.isSearchOpen) {
        localQuery = ""
        vm.closeSearch()
    }

    val menuActions = buildList<MenuAction> {
        add(
            MenuAction(
                label = "Lyrics lock",
                onClick = { showLockSheet = true },
                icon = Icons.Outlined.Lock,
            ),
        )
        add(
            MenuAction(
                label = "Recently deleted",
                onClick = onOpenTrash,
                icon = Icons.Outlined.History,
            ),
        )
        if (BuildConfig.DEBUG) {
            for (sample in DebugSample.values()) {
                add(
                    MenuAction(
                        label = debugLabel(sample),
                        onClick = {
                            vm.addSample(sample)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("Sample added")
                            }
                        },
                    ),
                )
            }
            add(MenuAction(label = "Run engine self-test", onClick = { vm.runSelfTest() }))
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = VAULT_NAME,
                onBack = onBack,
                actions = {
                    if (lockEnabled) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Lock,
                            contentDescription = "Lyrics lock is on",
                            onClick = { showLockSheet = true },
                        )
                    }
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search songs",
                        onClick = {
                            if (state.isSearchOpen) {
                                localQuery = ""
                                vm.closeSearch()
                            } else {
                                focusSearchOnOpen = true
                                vm.openSearch()
                            }
                        },
                    )
                    OverflowMenu(actions = menuActions)
                },
            )
        },
        floatingActionButton = {
            NeriboFab(onClick = { showNewDialog = true }, contentDescription = "New song")
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isSearchOpen) {
                NeriboSearchField(
                    query = localQuery,
                    onQueryChange = { value ->
                        localQuery = value
                        vm.onQueryChange(value)
                    },
                    modifier = Modifier
                        .padding(horizontal = spacing.screen)
                        .focusRequester(searchFocus),
                    placeholder = "Search songs",
                )
            }
            if (state.hasAny) {
                StatusFilterRow(
                    selected = state.statusFilter,
                    onSelect = { vm.onStatusFilterChange(it) },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isFiltering = state.query.isNotBlank() || state.statusFilter.isNotEmpty()
                when {
                    state.isLoading -> Unit
                    state.items.isEmpty() && isFiltering -> EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matches",
                        message = if (state.query.isNotBlank()) {
                            "Nothing found for \u201C${state.query.trim()}\u201D with this filter."
                        } else {
                            "No songs with this status yet."
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.items.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.MusicNote,
                        title = "Every song starts with one line",
                        message = "Write your first song section by section, then save it as a lyric sheet.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "New song",
                        onAction = { showNewDialog = true },
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        items(state.items, key = { it.song.id }) { item ->
                            val id = item.song.id
                            SongCard(
                                item = item,
                                actions = listOf(
                                    MenuAction(
                                        label = "Open",
                                        onClick = { onOpenSong(id) },
                                        icon = Icons.Outlined.FolderOpen,
                                    ),
                                    MenuAction(
                                        label = "Details",
                                        onClick = { detailsId = id },
                                        icon = Icons.Outlined.Info,
                                    ),
                                    MenuAction(
                                        label = "Duplicate",
                                        onClick = { vm.duplicate(id) },
                                        icon = Icons.Outlined.ContentCopy,
                                    ),
                                    MenuAction(
                                        label = "Delete",
                                        onClick = {
                                            vm.delete(id)
                                            scope.launch {
                                                showUndo("Moved to Recently deleted") { vm.restore(id) }
                                            }
                                        },
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                    ),
                                ),
                                onClick = { onOpenSong(id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        SongDetailsDialog(
            isNew = true,
            initialTitle = "",
            initialWriter = state.defaultWriter,
            onDismiss = { showNewDialog = false },
            onConfirm = { title, writer ->
                showNewDialog = false
                vm.create(title, writer) { id -> onOpenSong(id) }
            },
        )
    }

    val detailsItem = detailsId?.let { id -> state.items.firstOrNull { it.song.id == id } }
    if (detailsItem != null) {
        SongDetailsDialog(
            isNew = false,
            initialTitle = detailsItem.song.title,
            initialWriter = detailsItem.song.writer,
            onDismiss = { detailsId = null },
            onConfirm = { title, writer ->
                vm.updateDetails(detailsItem.song.id, title, writer)
                detailsId = null
            },
        )
    }

    if (showLockSheet) {
        VaultLockSettingsSheet(
            vaultId = VAULT_ID,
            vaultName = VAULT_NAME,
            onDismiss = { showLockSheet = false },
        )
    }

    val selfTest = state.selfTest
    if (selfTest != null) {
        SelfTestDialog(results = selfTest, onDismiss = { vm.dismissSelfTest() })
    }
}

/** All, Idea, Draft and Finished chips in a horizontally scrolling row. */
@Composable
private fun StatusFilterRow(selected: String, onSelect: (String) -> Unit) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboChip(label = "All", selected = selected.isEmpty(), onClick = { onSelect("") })
        NeriboChip(label = "Idea", selected = selected == STATUS_IDEA, onClick = { onSelect(STATUS_IDEA) })
        NeriboChip(label = "Draft", selected = selected == STATUS_DRAFT, onClick = { onSelect(STATUS_DRAFT) })
        NeriboChip(label = "Finished", selected = selected == STATUS_FINISHED, onClick = { onSelect(STATUS_FINISHED) })
    }
}

private fun debugLabel(sample: DebugSample): String = when (sample) {
    DebugSample.A -> "Add sample A"
    DebugSample.B -> "Add sample B (long)"
    DebugSample.C -> "Add sample C (outlier test)"
}

/** Debug only: one line per engine check, then a summary such as "32 of 32 passed". */
@Composable
private fun SelfTestDialog(results: List<CheckResult>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val passed = results.count { it.passed }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Done",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        },
        title = { Text(text = "Engine self-test", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "$passed of ${results.size} passed",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                for (result in results) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = if (result.passed) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = if (result.passed) "Passed" else "Failed",
                            modifier = Modifier.size(18.dp),
                            tint = if (result.passed) NeriboTheme.extraColors.success else colors.error,
                        )
                        Column(modifier = Modifier.padding(start = spacing.sm)) {
                            Text(
                                text = result.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface,
                            )
                            if (!result.passed) {
                                Text(
                                    text = result.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
