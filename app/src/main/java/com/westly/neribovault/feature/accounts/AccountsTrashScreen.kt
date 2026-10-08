package com.westly.neribovault.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.TrashRow
import com.westly.neribovault.core.util.formatRelative
import kotlinx.coroutines.launch

/** Which kind of row the "delete forever" confirmation is for. */
private enum class PendingKind { Account, Item }

/** Recently deleted accounts and items: restore, delete forever, or empty the trash. */
@Composable
fun AccountsTrashScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> AccountsTrashViewModel(c.accountsRepository, c.accountItemsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable { mutableStateOf(PendingKind.Account) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }

    fun announce(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Recently deleted",
                onBack = onBack,
                actions = {
                    if (!state.isEmpty) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Empty trash",
                                    onClick = { confirmEmpty = true },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = "Items are removed permanently after 30 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> EmptyState(
                        icon = Icons.Outlined.Delete,
                        title = "Nothing deleted",
                        message = "Accounts and items you delete wait here for 30 days before they are gone for good.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        if (state.accounts.isNotEmpty()) {
                            item(key = "header-accounts") { SectionHeader(text = "Accounts") }
                            items(state.accounts, key = { "account-" + it.id }) { account ->
                                TrashRow(
                                    title = account.name.ifBlank { UNTITLED_ACCOUNT },
                                    subtitle = PlatformPresets.displayName(account.platform) +
                                        " \u00B7 Deleted " + formatRelative(account.deletedAt ?: account.updatedAt),
                                    onRestore = {
                                        vm.restoreAccount(account.id)
                                        announce("Account restored")
                                    },
                                    onDeleteForever = {
                                        pendingKind = PendingKind.Account
                                        pendingId = account.id
                                    },
                                )
                            }
                        }
                        if (state.items.isNotEmpty()) {
                            item(key = "header-items") { SectionHeader(text = "Items") }
                            items(state.items, key = { "item-" + it.item.id }) { row ->
                                val item = row.item
                                TrashRow(
                                    title = item.name.ifBlank { "Untitled item" },
                                    subtitle = row.accountName + " \u00B7 Deleted " +
                                        formatRelative(item.deletedAt ?: item.updatedAt),
                                    onRestore = {
                                        vm.restoreItem(item.id)
                                        announce("Item restored")
                                    },
                                    onDeleteForever = {
                                        pendingKind = PendingKind.Item
                                        pendingId = item.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingId?.let { id ->
        val isAccount = pendingKind == PendingKind.Account
        ConfirmDialog(
            title = "Delete forever?",
            message = if (isAccount) {
                "This account, its items and its saved passwords will be permanently deleted. This can't be undone."
            } else {
                "This item and its fields will be permanently deleted. This can't be undone."
            },
            confirmLabel = "Delete forever",
            onConfirm = {
                if (isAccount) vm.deleteAccountForever(id) else vm.deleteItemForever(id)
                pendingId = null
            },
            onDismiss = { pendingId = null },
            destructive = true,
        )
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "Everything in Recently deleted will be permanently deleted. This can't be undone.",
            confirmLabel = "Empty trash",
            onConfirm = {
                vm.emptyTrash()
                confirmEmpty = false
            },
            onDismiss = { confirmEmpty = false },
            destructive = true,
        )
    }
}
