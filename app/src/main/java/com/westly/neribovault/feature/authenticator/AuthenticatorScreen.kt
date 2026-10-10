package com.westly.neribovault.feature.authenticator

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.repeatOnLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.core.lock.rememberVaultLockEnabled
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.feature.authenticator.add.AddAccountSheet
import com.westly.neribovault.feature.authenticator.components.BackupReminder
import com.westly.neribovault.feature.authenticator.components.CodeCard
import com.westly.neribovault.feature.authenticator.components.TimeBanner
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.Totp
import com.westly.neribovault.feature.authenticator.security.AuthenticatorClipboard
import com.westly.neribovault.feature.authenticator.security.AuthenticatorPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val REMINDER_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000

/**
 * The Authenticator list: live one-time codes with countdown rings, tap to copy, search, pin,
 * delete with Undo. One shared ticker drives every card once per second.
 */
@Composable
fun AuthenticatorScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val vm = neriboViewModel { c -> AuthenticatorViewModel(c.totpAccountsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    val lockEnabled by rememberVaultLockEnabled(VAULT_ID)
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var localQuery by rememberSaveable { mutableStateOf(state.query) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var autoTimeOff by remember { mutableStateOf(false) }
    var hideCodes by remember { mutableStateOf(AuthenticatorPrefs.hideCodes(context)) }
    var lastExportAt by remember { mutableLongStateOf(AuthenticatorPrefs.lastExportAt(context)) }
    var reminderUntil by remember { mutableLongStateOf(AuthenticatorPrefs.reminderDismissedUntil(context)) }

    // One shared ticker: wakes at every full second so codes change exactly at the period boundary.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000L - nowMillis % 1000L)
            }
        }
    }

    // Re-check the clock setting and the preferences on resume; wipe secrets on stop.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    autoTimeOff = Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 0
                    hideCodes = AuthenticatorPrefs.hideCodes(context)
                    lastExportAt = AuthenticatorPrefs.lastExportAt(context)
                    reminderUntil = AuthenticatorPrefs.reminderDismissedUntil(context)
                    AuthenticatorClipboard.clearIfExpired()
                }
                Lifecycle.Event.ON_STOP -> vm.onStop()
                else -> Unit
            }
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
    val announce: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
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
                label = "Hide codes until tapped",
                onClick = {
                    hideCodes = !hideCodes
                    AuthenticatorPrefs.setHideCodes(context, hideCodes)
                },
                icon = if (hideCodes) Icons.Outlined.Check else null,
            ),
        )
        add(MenuAction(label = "Authenticator lock", onClick = { showLockSheet = true }, icon = Icons.Outlined.Lock))
        add(MenuAction(label = "Backup and restore", onClick = onOpenBackup, icon = Icons.Outlined.Backup))
        add(MenuAction(label = "Recently deleted", onClick = onOpenTrash, icon = Icons.Outlined.History))
        if (BuildConfig.DEBUG) {
            add(MenuAction(label = "Run engine self-test", onClick = { vm.runSelfTest() }))
        }
    }

    val openDateSettings: () -> Unit = {
        try {
            context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            announce("Open your phone's settings and turn on automatic date and time")
        }
    }
    val showReminder = state.hasAny && lastExportAt == 0L && reminderUntil <= nowMillis

    val banners: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            if (autoTimeOff) TimeBanner(onOpenSettings = openDateSettings)
            if (showReminder) {
                BackupReminder(
                    onBackUp = onOpenBackup,
                    onLater = {
                        val until = System.currentTimeMillis() + REMINDER_DAYS_MILLIS
                        AuthenticatorPrefs.dismissReminder(context, until)
                        reminderUntil = until
                    },
                )
            }
        }
    }
    val hasBanners = autoTimeOff || showReminder

    val copyCode: (TotpAccountEntity) -> Unit = { account ->
        val secret = vm.secretFor(account)
        if (secret is SecretState.Ready) {
            val algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm } ?: OtpAlgorithm.SHA1
            val code = try {
                Totp.code(secret.secret, System.currentTimeMillis(), account.digits, account.periodSeconds, algorithm)
            } catch (e: Exception) {
                null
            }
            if (code != null) {
                AuthenticatorClipboard.copy(context, code)
                announce("Copied. Clipboard clears in 30 seconds")
            }
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
                            contentDescription = "Authenticator lock is on",
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
            NeriboFab(onClick = { showAddSheet = true }, contentDescription = "Add account")
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.items.isEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                        if (hasBanners) {
                            Box(modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.sm)) {
                                banners()
                            }
                        }
                        if (state.hasAny) {
                            EmptyState(
                                icon = Icons.Outlined.Search,
                                title = "No matches",
                                message = "Nothing found for \u201C${state.query.trim()}\u201D.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            EmptyState(
                                icon = Icons.Outlined.VerifiedUser,
                                title = "Add your first account",
                                message = "Two-step codes, made on this phone. No internet needed.",
                                modifier = Modifier.fillMaxSize(),
                                actionLabel = "Add account",
                                onAction = { showAddSheet = true },
                            )
                        }
                    }
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
                        if (hasBanners) {
                            item(key = "banners") { banners() }
                        }
                        items(state.items, key = { it.id }) { account ->
                            val id = account.id
                            val secretState = vm.secretFor(account)
                            val revealed = (state.revealedUntil[id] ?: 0L) > nowMillis
                            val hidden = hideCodes && !revealed
                            val actions = if (secretState is SecretState.Unreadable) {
                                listOf(
                                    MenuAction(
                                        label = "Remove",
                                        onClick = {
                                            vm.delete(id)
                                            scope.launch { showUndo("Moved to Recently deleted") { vm.restore(id) } }
                                        },
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                    ),
                                )
                            } else {
                                listOf(
                                    MenuAction(label = "Edit", onClick = { onOpenEditor(id) }, icon = Icons.Outlined.Edit),
                                    MenuAction(
                                        label = if (account.isPinned) "Unpin" else "Pin",
                                        onClick = { vm.setPinned(id, !account.isPinned) },
                                        icon = Icons.Outlined.PushPin,
                                    ),
                                    MenuAction(
                                        label = "Delete",
                                        onClick = {
                                            vm.delete(id)
                                            scope.launch { showUndo("Moved to Recently deleted") { vm.restore(id) } }
                                        },
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                    ),
                                )
                            }
                            CodeCard(
                                account = account,
                                secretState = secretState,
                                nowMillis = nowMillis,
                                hidden = hidden,
                                actions = actions,
                                onClick = {
                                    when {
                                        secretState !is SecretState.Ready -> Unit
                                        hidden -> vm.reveal(id, System.currentTimeMillis())
                                        else -> copyCode(account)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddAccountSheet(
            onDismiss = { showAddSheet = false },
            onManual = {
                showAddSheet = false
                onOpenEditor("new")
            },
            onImported = { count ->
                showAddSheet = false
                announce(if (count == 1) "Added 1 account" else "Added $count accounts")
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
