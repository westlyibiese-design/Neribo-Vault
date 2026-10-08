package com.westly.neribovault.feature.accounts.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_CLOSED
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_INACTIVE
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.UNTITLED_ACCOUNT
import com.westly.neribovault.feature.accounts.accountStatusLabel
import com.westly.neribovault.feature.accounts.components.PlatformAvatar
import com.westly.neribovault.feature.accounts.itemStatusLabel
import com.westly.neribovault.feature.accounts.itemTypeLabel
import com.westly.neribovault.feature.accounts.signInMethodLabel
import com.westly.neribovault.feature.accounts.twoFactorLabel

private const val MASK = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

/**
 * The read-only account viewer: header, login details, custom fields, items and notes. Passwords
 * and secret fields are never decrypted here; they show as a mask. Empty values are hidden.
 * [onEdit] opens the editor, [onOpenItem] an item, [onAddItem] a new item. Deleting moves the
 * account to the trash and goes back through [onBack].
 */
@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenItem: (String) -> Unit,
    onAddItem: () -> Unit,
) {
    val vm = neriboViewModel(key = "accounts-detail-$accountId") { c ->
        AccountDetailViewModel(accountId, c.accountsRepository, c.accountItemsRepository, c.accountFieldsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val account = state.account

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = account?.name?.trim()?.ifEmpty { UNTITLED_ACCOUNT } ?: "Account",
                onBack = onBack,
                actions = {
                    if (account != null) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(label = "Edit", onClick = onEdit, icon = Icons.Outlined.Edit),
                                MenuAction(
                                    label = "Delete",
                                    onClick = { confirmDelete = true },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            account == null -> EmptyState(
                icon = Icons.Outlined.AccountCircle,
                title = "Account not found",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Back",
                onAction = onBack,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                Header(account)
                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboDivider()
                LoginSection(account)
                if (state.fields.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(spacing.lg))
                    NeriboDivider()
                    FieldsSection(state.fields)
                }
                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboDivider()
                ItemsSection(state.items, onOpenItem = onOpenItem, onAddItem = onAddItem)
                if (account.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(spacing.lg))
                    NeriboDivider()
                    SectionHeader(text = "Notes", modifier = Modifier.padding(top = spacing.lg, bottom = spacing.sm))
                    Text(
                        text = account.notes,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xl))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this account?",
            message = "It moves to Recently deleted and is removed for good after 30 days.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                vm.delete(onDone = onBack)
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun Header(account: AccountEntity) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformAvatar(platform = account.platform, size = 56.dp)
        Column(modifier = Modifier.weight(1f).padding(start = spacing.lg)) {
            Text(
                text = account.name.trim().ifEmpty { UNTITLED_ACCOUNT },
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                color = colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PlatformPresets.displayName(account.platform),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            StatusBadge(
                text = accountStatusLabel(account.status),
                tone = when (account.status) {
                    ACCOUNT_STATUS_INACTIVE -> BadgeTone.Warning
                    ACCOUNT_STATUS_CLOSED -> BadgeTone.Danger
                    else -> BadgeTone.Neutral
                },
            )
        }
    }
}

@Composable
private fun LoginSection(account: AccountEntity) {
    val spacing = NeriboTheme.spacing
    SectionHeader(text = "Login", modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs))
    LabeledValue("Sign-in method", signInMethodLabel(account.signInMethod))
    if (account.loginId.isNotBlank()) LabeledValue("Login", account.loginId)
    if (account.url.isNotBlank()) LabeledValue("Link", account.url)
    LabeledValue("Two-factor", twoFactorLabel(account.twoFactor))
    if (account.recovery.isNotBlank()) LabeledValue("Recovery", account.recovery)
    if (account.tags.isNotEmpty()) LabeledValue("Tags", account.tags.joinToString(", "))
    LabeledValue(
        "Password",
        if (account.passwordCipher != null && account.passwordIv != null) {
            "$MASK  Stored, encrypted"
        } else {
            "No password stored"
        },
    )
    Text(
        text = "Names, logins, links and notes are stored as plain text on this phone. " +
            "Passwords and secret fields are encrypted.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.sm),
    )
}

@Composable
private fun FieldsSection(fields: List<AccountFieldEntity>) {
    val spacing = NeriboTheme.spacing
    SectionHeader(text = "Custom fields", modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs))
    for (field in fields) {
        val shown = if (field.isSecret) MASK else field.valuePlain.orEmpty()
        LabeledValue(field.label.ifBlank { "Field" }, shown.ifEmpty { "\u2014" })
    }
}

@Composable
private fun ItemsSection(
    items: List<AccountItemEntity>,
    onOpenItem: (String) -> Unit,
    onAddItem: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    SectionHeader(text = "Items", modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs))
    if (items.isEmpty()) {
        Text(
            text = "Nothing under this account yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
    }
    for (item in items) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { onOpenItem(item.id) }
                .padding(vertical = spacing.sm),
        ) {
            Text(
                text = item.name.ifBlank { "Untitled item" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = itemTypeLabel(item.itemType) + " \u00B7 " + itemStatusLabel(item.status),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
    NeriboButton(
        text = "Add item",
        onClick = onAddItem,
        style = ButtonStyle.Text,
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    val spacing = NeriboTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
