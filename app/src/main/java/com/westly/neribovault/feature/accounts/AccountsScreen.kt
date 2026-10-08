package com.westly.neribovault.feature.accounts

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
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.platform.LocalContext
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
import com.westly.neribovault.feature.accounts.components.AccountCard
import com.westly.neribovault.feature.accounts.components.NewAccountSheet
import com.westly.neribovault.feature.accounts.security.AccountsVault
import com.westly.neribovault.feature.accounts.security.CheckResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Accounts list: private search, status and platform chips, one card per account, create from
 * a platform picker, pin, duplicate and delete with Undo. Debug builds also get the engine
 * self-test in the overflow menu.
 */
@Composable
fun AccountsScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) {
        AccountsVault.attach(context)
        true
    }
    val vm = neriboViewModel { c -> AccountsViewModel(c.accountsRepository, c.accountItemsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val lockEnabled by rememberVaultLockEnabled(VAULT_ID)
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf(state.query) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var showNewSheet by rememberSaveable { mutableStateOf(false) }

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
        add(MenuAction(label = "Accounts lock", onClick = { showLockSheet = true }, icon = Icons.Outlined.Lock))
        add(MenuAction(label = "Accounts PIN", onClick = onOpenSecurity, icon = Icons.Outlined.Fingerprint))
        add(MenuAction(label = "Password health", onClick = onOpenHealth, icon = Icons.Outlined.Check))
        add(MenuAction(label = "Activity", onClick = onOpenActivity, icon = Icons.Outlined.Schedule))
        add(MenuAction(label = "Recently deleted", onClick = onOpenTrash, icon = Icons.Outlined.History))
        if (BuildConfig.DEBUG) {
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
                            contentDescription = "Accounts lock is on",
                            onClick = { showLockSheet = true },
                        )
                    }
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search accounts",
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
            NeriboFab(onClick = { showNewSheet = true }, contentDescription = "Add account")
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
                    placeholder = "Search accounts",
                )
            }
            if (state.hasAny) {
                FilterRow(
                    state = state,
                    onStatus = { vm.onStatusFilterChange(it) },
                    onPlatform = { vm.onPlatformFilterToggle(it) },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isFiltering = state.query.isNotBlank() ||
                    state.statusFilter.isNotEmpty() || state.platformFilter.isNotEmpty()
                when {
                    state.isLoading -> Unit
                    state.items.isEmpty() && isFiltering -> EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "No matches",
                        message = if (state.query.isNotBlank()) {
                            "Nothing found for \u201C${state.query.trim()}\u201D with these filters."
                        } else {
                            "No accounts match these filters."
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.items.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.AccountCircle,
                        title = "Know where you are signed in",
                        message = "Keep your platforms, logins and projects in one private place.",
                        modifier = Modifier.fillMaxSize(),
                        actionLabel = "Add account",
                        onAction = { showNewSheet = true },
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
                        items(state.items, key = { it.account.id }) { item ->
                            val id = item.account.id
                            val pinned = item.account.isPinned
                            AccountCard(
                                item = item,
                                actions = listOf(
                                    MenuAction(
                                        label = "Open",
                                        onClick = { onOpenAccount(id) },
                                        icon = Icons.Outlined.FolderOpen,
                                    ),
                                    MenuAction(
                                        label = if (pinned) "Unpin" else "Pin",
                                        onClick = { vm.setPinned(id, !pinned) },
                                        icon = Icons.Outlined.PushPin,
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
                                onClick = { onOpenAccount(id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewSheet) {
        NewAccountSheet(
            onDismiss = { showNewSheet = false },
            onCreate = { preset, platformName, accountName ->
                showNewSheet = false
                vm.create(preset, platformName, accountName) { id -> onOpenAccount(id) }
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

/** All, Active, Inactive, Closed, then one chip per platform in use. */
@Composable
private fun FilterRow(
    state: AccountsUiState,
    onStatus: (String) -> Unit,
    onPlatform: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboChip(label = "All", selected = state.statusFilter.isEmpty(), onClick = { onStatus("") })
        listOf(
            ACCOUNT_STATUS_ACTIVE to "Active",
            ACCOUNT_STATUS_INACTIVE to "Inactive",
            ACCOUNT_STATUS_CLOSED to "Closed",
        ).forEach { (value, label) ->
            val selected = state.statusFilter == value
            NeriboChip(label = label, selected = selected, onClick = { onStatus(if (selected) "" else value) })
        }
        state.platformChips.forEach { chip ->
            NeriboChip(
                label = chip.label.ifEmpty { "Unnamed" },
                selected = state.platformFilter == chip.key,
                onClick = { onPlatform(chip.key) },
            )
        }
    }
}

/** Debug only: one line per engine check, then a summary such as "10 of 10 passed". */
@Composable
private fun SelfTestDialog(results: List<CheckResult>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val passed = results.count { it.passed }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done", style = MaterialTheme.typography.labelLarge, color = colors.primary)
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
                            Text(text = result.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                            if (!result.passed) {
                                Text(text = result.detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
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
