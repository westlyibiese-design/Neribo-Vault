package com.westly.neribovault.feature.diary

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.core.lock.rememberVaultLockEnabled
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.feature.diary.components.DiaryEntryCard
import com.westly.neribovault.feature.diary.components.MonthCalendar
import java.time.YearMonth
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val VAULT_ID = "diary"
private const val VAULT_NAME = "Diary"

/**
 * The Diary list: entries grouped by month or a month calendar, a quiet writing streak, search,
 * and the Undo snackbar for deletes (including deletes made on the entry screen).
 */
@Composable
fun DiaryScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onNewEntry: (dateMillis: Long?) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val vm = neriboViewModel { c -> DiaryViewModel(c.diaryRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lockEnabled by rememberVaultLockEnabled(VAULT_ID)
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf(state.query) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }

    DiaryPrivacyGuard()

    // Keep "today" (streak, Today prompt, calendar ring) right after midnight or a long pause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshToday()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    val newEntryDate: Long? =
        if (state.mode == DiaryMode.Calendar) state.selectedDay.toStartOfDayMillis() else null

    val renderEntry: @Composable (DiaryEntryEntity) -> Unit = { entry ->
        val actions = listOf(
            MenuAction(
                label = "Copy",
                onClick = {
                    context.copyToClipboard("Diary entry", entry.toPlainText())
                    scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
            MenuAction(
                label = "Share",
                // No subject line, so no diary text is put in the share sheet's title.
                onClick = { context.shareText(null, entry.toPlainText()) },
                icon = Icons.Outlined.Share,
            ),
            MenuAction(
                label = "Delete",
                onClick = {
                    vm.delete(entry.id)
                    scope.launch { showUndo("Moved to Recently deleted") { vm.restore(entry.id) } }
                },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
        DiaryEntryCard(entry = entry, actions = actions, onClick = { onOpenEntry(entry.id) })
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Diary",
                onBack = onBack,
                actions = {
                    if (lockEnabled) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Lock,
                            contentDescription = "Diary lock is on",
                            onClick = { showLockSheet = true },
                        )
                    }
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search diary",
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
                    NeriboIconButton(
                        icon = if (state.mode == DiaryMode.List) Icons.Outlined.CalendarMonth else Icons.Outlined.ViewList,
                        contentDescription = if (state.mode == DiaryMode.List) "Show calendar" else "Show list",
                        onClick = { vm.toggleMode() },
                    )
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Diary lock",
                                onClick = { showLockSheet = true },
                                icon = Icons.Outlined.Lock,
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
            NeriboFab(onClick = { onNewEntry(newEntryDate) }, contentDescription = "New diary entry")
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
                    placeholder = "Search diary",
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isSearching -> SearchResults(
                        results = state.searchResults,
                        query = state.query,
                        renderEntry = renderEntry,
                    )
                    state.mode == DiaryMode.Calendar -> CalendarContent(
                        state = state,
                        vm = vm,
                        renderEntry = renderEntry,
                        onWriteForDay = { onNewEntry(state.selectedDay.toStartOfDayMillis()) },
                    )
                    state.entries.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Book,
                        title = "Your first page",
                        message = "Write a few lines about today: the rain, the light, who you spoke to.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "Write today's entry",
                        onAction = { onNewEntry(null) },
                    )
                    else -> ListContent(
                        state = state,
                        renderEntry = renderEntry,
                        onWriteToday = { onNewEntry(null) },
                    )
                }
            }
        }
    }

    if (showLockSheet) {
        VaultLockSettingsSheet(
            vaultId = VAULT_ID,
            vaultName = VAULT_NAME,
            onDismiss = { showLockSheet = false },
        )
    }
}

@Composable
private fun StreakLine(streak: Int) {
    Text(
        text = if (streak > 0) "$streak-day streak" else "Start a streak today",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ListContent(
    state: DiaryUiState,
    renderEntry: @Composable (DiaryEntryEntity) -> Unit,
    onWriteToday: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    // Entries arrive newest first, so grouping keeps months (and entries inside them) in order.
    val groups = remember(state.entries) {
        state.entries.groupBy { YearMonth.from(it.entryDate.toLocalDate()) }.toList()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.sm,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "streak") { StreakLine(streak = state.streak) }
        if (!state.hasEntryToday) {
            item(key = "today-prompt") { TodayPrompt(onClick = onWriteToday) }
        }
        groups.forEach { (month, entries) ->
            item(key = "header-$month") { SectionHeader(month.title()) }
            items(entries, key = { it.id }) { entry -> renderEntry(entry) }
        }
    }
}

@Composable
private fun TodayPrompt(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = "How was your day? Write a few lines.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CalendarContent(
    state: DiaryUiState,
    vm: DiaryViewModel,
    renderEntry: @Composable (DiaryEntryEntity) -> Unit,
    onWriteForDay: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.sm,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "streak") { StreakLine(streak = state.streak) }
        item(key = "calendar") {
            MonthCalendar(
                month = state.visibleMonth,
                today = state.today,
                selectedDay = state.selectedDay,
                entryDays = state.entryDays,
                onSelectDay = { vm.selectDay(it) },
                onPreviousMonth = { vm.previousMonth() },
                onNextMonth = { vm.nextMonth() },
            )
        }
        item(key = "day-header") {
            SectionHeader(formatDateLong(state.selectedDay.toStartOfDayMillis()))
        }
        if (state.dayEntries.isEmpty()) {
            item(key = "day-empty") {
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        Text(
                            text = "Nothing written for this day yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NeriboButton(
                            text = "Write for this day",
                            onClick = onWriteForDay,
                            leadingIcon = Icons.Outlined.Edit,
                        )
                    }
                }
            }
        } else {
            items(state.dayEntries, key = { it.id }) { entry -> renderEntry(entry) }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<DiaryEntryEntity>,
    query: String,
    renderEntry: @Composable (DiaryEntryEntity) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    if (results.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.sm,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(results, key = { it.id }) { entry -> renderEntry(entry) }
    }
}
