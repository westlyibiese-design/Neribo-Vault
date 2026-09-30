package com.westly.neribovault.feature.church

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.feature.church.components.ChurchRecordCard
import com.westly.neribovault.feature.church.components.SpeakersSheet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Church list: type chips, optional speaker filter and search, pinned records first and the
 * rest grouped by month, with the Undo snackbar for deletes (including deletes made in the editor).
 */
@Composable
fun ChurchScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onNewRecord: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> ChurchViewModel(c.churchRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen never
    // pops the keyboard open on its own.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var showSpeakers by rememberSaveable { mutableStateOf(false) }

    // The text field edits this local copy so typing is never delayed by the database.
    var localQuery by rememberSaveable { mutableStateOf(state.query) }

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
                showUndo("Moved to Recently deleted", { vm.restore(id) })
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

    val renderRecord: @Composable (ChurchRecordEntity) -> Unit = { record ->
        val actions = listOf(
            MenuAction(
                label = if (record.isPinned) "Unpin" else "Pin",
                onClick = { vm.togglePinned(record) },
                icon = Icons.Outlined.PushPin,
            ),
            MenuAction(
                label = "Copy",
                onClick = {
                    context.copyToClipboard("Church record", record.toPlainText())
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
            MenuAction(
                label = "Share",
                onClick = { context.shareText(record.title.ifBlank { null }, record.toPlainText()) },
                icon = Icons.Outlined.Share,
            ),
            MenuAction(
                label = "Delete",
                onClick = {
                    vm.delete(record.id)
                    scope.launch { showUndo("Moved to Recently deleted", { vm.restore(record.id) }) }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
        ChurchRecordCard(
            record = record,
            actions = actions,
            onClick = { onOpenRecord(record.id) },
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Church",
                onBack = onBack,
                actions = {
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search records",
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
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Speakers",
                                onClick = { showSpeakers = true },
                                icon = Icons.Outlined.Person,
                            ),
                            MenuAction(
                                label = "Recently deleted",
                                onClick = onOpenTrash,
                                icon = Icons.Outlined.History,
                            ),
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            NeriboFab(onClick = onNewRecord, contentDescription = "New record")
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
                    placeholder = "Title, speaker, verse or theme",
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboChip(
                    label = "All",
                    selected = state.selectedType == null,
                    onClick = { vm.selectType(null) },
                )
                CHURCH_TYPES.forEach { type ->
                    NeriboChip(
                        label = type.label,
                        selected = state.selectedType == type.value,
                        onClick = { vm.selectType(type.value) },
                    )
                }
            }
            val speakerFilter = state.selectedSpeaker
            if (speakerFilter != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Tapping the chip clears the speaker filter.
                    NeriboChip(
                        label = "Speaker: $speakerFilter  \u00D7",
                        selected = true,
                        onClick = { vm.selectSpeaker(null) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> ChurchEmptyState(state = state, onNewRecord = onNewRecord)
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
                        if (state.pinned.isNotEmpty()) {
                            item(key = "header-pinned") { SectionHeader("Pinned") }
                            items(state.pinned, key = { it.id }) { record -> renderRecord(record) }
                        }
                        state.months.forEach { group ->
                            item(key = "header-${group.key}") { SectionHeader(group.title) }
                            items(group.records, key = { it.id }) { record -> renderRecord(record) }
                        }
                    }
                }
            }
        }
    }

    if (showSpeakers) {
        SpeakersSheet(
            speakers = state.speakers,
            selected = state.selectedSpeaker,
            onSelect = { name ->
                vm.selectSpeaker(name)
                showSpeakers = false
            },
            onClear = {
                vm.selectSpeaker(null)
                showSpeakers = false
            },
            onDismiss = { showSpeakers = false },
        )
    }
}

@Composable
private fun ChurchEmptyState(state: ChurchUiState, onNewRecord: () -> Unit) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.isFiltering -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No records fit these filters yet.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> EmptyState(
            icon = Icons.Outlined.Book,
            title = "Nothing here yet",
            message = "Keep what you hear and learn. Add your first message.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "New record",
            onAction = onNewRecord,
        )
    }
}
