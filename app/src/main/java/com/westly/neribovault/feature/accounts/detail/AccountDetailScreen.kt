package com.westly.neribovault.feature.accounts.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_CLOSED
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_INACTIVE
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.SIGN_IN_OTHER
import com.westly.neribovault.feature.accounts.UNTITLED_ACCOUNT
import com.westly.neribovault.feature.accounts.accountStatusLabel
import com.westly.neribovault.feature.accounts.components.PlatformAvatar
import com.westly.neribovault.feature.accounts.editor.PLAIN_TEXT_REMINDER
import com.westly.neribovault.feature.accounts.editor.loginLabel
import com.westly.neribovault.feature.accounts.security.AccountsClipboard
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsVault
import com.westly.neribovault.feature.accounts.signInMethodLabel
import com.westly.neribovault.feature.accounts.signInMethodUsesPassword
import com.westly.neribovault.feature.accounts.twoFactorLabel
import kotlinx.coroutines.launch

private const val PASSWORD_KEY = "password"

/**
 * The account viewer: header, login details, custom fields, items and notes. A password or
 * secret field is masked until the owner reveals or copies it, which needs the Accounts PIN.
 * [onEdit] opens the editor, [onOpenItem] an item, [onAddItem] a new item. Deleting moves the
 * account to the trash and goes back through [onBack].
 */
@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onAddItem: () -> Unit,
) {
    val vm = neriboViewModel(key = "accounts-detail-$accountId") { c ->
        AccountDetailViewModel(accountId, c.accountsRepository, c.accountItemsRepository, c.accountFieldsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val account = state.account

    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }
    val access = rememberSecretAccess(onMessage = showMessage)
    val reveal = rememberRevealState()

    // The second-PIN session can't change anything, so writes are refused with a neutral message.
    val guardWrite: (() -> Unit) -> Unit = { action ->
        if (AccountsVault.mode.value == AccountsSessionMode.Decoy) {
            showMessage(AccountsVault.BLOCKED_MESSAGE)
        } else {
            action()
        }
    }
    val openLink: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())))
        } catch (e: ActivityNotFoundException) {
            showMessage("No app can open this link")
        }
    }
    val copyPlain: (String, String) -> Unit = { label, value ->
        context.copyToClipboard(label, value)
        showMessage("Copied")
    }

    val revealPassword: (AccountEntity) -> Unit = { acc ->
        access.run(false) {
            val value = AccountsVault.displayValue(
                acc.passwordCipher, acc.passwordIv, acc.passwordDecoy, "password", acc.id,
            )
            if (value == null) {
                showMessage("Couldn't read that password")
            } else {
                reveal.show(PASSWORD_KEY, value)
                vm.log("password_revealed", acc.id)
            }
        }
    }
    val copyPassword: (AccountEntity) -> Unit = { acc ->
        access.run(false) {
            val value = AccountsVault.displayValue(
                acc.passwordCipher, acc.passwordIv, acc.passwordDecoy, "password", acc.id,
            )
            if (value == null) {
                showMessage("Couldn't read that password")
            } else {
                AccountsClipboard.copy(context, value)
                showMessage(COPIED_SECRET_MESSAGE)
                vm.log("password_copied", acc.id)
            }
        }
    }
    val revealField: (AccountFieldEntity) -> Unit = { field ->
        access.run(false) {
            val value = AccountsVault.displayValue(
                field.valueCipher, field.valueIv, field.valueDecoy, fieldDisplayCategory(field.label), field.id,
            )
            if (value == null) {
                showMessage("Couldn't read that value")
            } else {
                reveal.show(field.id, value)
                vm.log("field_revealed", field.id)
            }
        }
    }
    val copyField: (AccountFieldEntity) -> Unit = { field ->
        access.run(false) {
            val value = AccountsVault.displayValue(
                field.valueCipher, field.valueIv, field.valueDecoy, fieldDisplayCategory(field.label), field.id,
            )
            if (value == null) {
                showMessage("Couldn't read that value")
            } else {
                AccountsClipboard.copy(context, value)
                showMessage(COPIED_SECRET_MESSAGE)
                vm.log("field_copied", field.id)
            }
        }
    }
    val deleteItem: (AccountItemEntity) -> Unit = { item ->
        guardWrite {
            state.itemFields[item.id]?.forEach { reveal.hide(it.id) }
            vm.deleteItem(item.id)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = "Moved to Recently deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.restoreItem(item.id)
            }
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = account?.name?.trim()?.ifEmpty { UNTITLED_ACCOUNT } ?: "Account",
                onBack = onBack,
                actions = {
                    if (account != null) {
                        val actions = buildList<MenuAction> {
                            add(MenuAction(label = "Edit", onClick = onEdit, icon = Icons.Outlined.Edit))
                            if (account.url.isNotBlank()) {
                                add(
                                    MenuAction(
                                        label = "Open link",
                                        onClick = { openLink(account.url) },
                                        icon = Icons.Outlined.Link,
                                    ),
                                )
                            }
                            if (account.loginId.isNotBlank()) {
                                add(
                                    MenuAction(
                                        label = "Copy login",
                                        onClick = { copyPlain(loginLabel(account.signInMethod, account.signInOtherName), account.loginId) },
                                        icon = Icons.Outlined.ContentCopy,
                                    ),
                                )
                            }
                            add(
                                MenuAction(
                                    label = if (account.isPinned) "Unpin" else "Pin",
                                    onClick = { guardWrite { vm.setPinned(!account.isPinned) } },
                                    icon = Icons.Outlined.PushPin,
                                ),
                            )
                            add(
                                MenuAction(
                                    label = "Delete",
                                    onClick = { guardWrite { vm.delete(onDone = onBack) } },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            )
                        }
                        OverflowMenu(actions = actions)
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
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
            else -> {
                val hasPassword = account.passwordCipher != null && account.passwordIv != null
                // An older "Other" account that already has a stored password keeps its password row,
                // so the password stays reachable.
                val usesPassword = signInMethodUsesPassword(account.signInMethod) ||
                    (account.signInMethod == SIGN_IN_OTHER && hasPassword)
                val isEmptyDetails = account.loginId.isBlank() && !hasPassword &&
                    state.fields.isEmpty() && state.items.isEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen, vertical = spacing.sm),
                ) {
                    Header(account)
                    if (isEmptyDetails) {
                        Spacer(modifier = Modifier.height(spacing.lg))
                        NeriboCard {
                            Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
                                Text(
                                    text = "Add the login details",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Keep the login, password, link and recovery notes for this account together.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.md),
                                )
                                NeriboButton(text = "Add details", onClick = onEdit)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.lg))
                    NeriboDivider()
                    SectionHeader(text = "Login", modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs))
                    if (account.loginId.isNotBlank()) {
                        val label = loginLabel(account.signInMethod, account.signInOtherName)
                        ValueRow(
                            label = label,
                            value = account.loginId,
                            onCopy = { copyPlain(label, account.loginId) },
                        )
                    }
                    if (usesPassword) {
                        SecretRow(
                            label = "Password",
                            isStored = hasPassword,
                            revealedValue = reveal.value(PASSWORD_KEY),
                            onReveal = { revealPassword(account) },
                            onHide = { reveal.hide(PASSWORD_KEY) },
                            onCopy = { copyPassword(account) },
                            onAdd = onEdit,
                        )
                    }
                    if (account.url.isNotBlank()) {
                        ValueRow(
                            label = "Link",
                            value = account.url,
                            onCopy = { copyPlain("Link", account.url) },
                            actionLabel = "Open",
                            onAction = { openLink(account.url) },
                        )
                    }
                    ValueRow(label = "Two-factor", value = twoFactorLabel(account.twoFactor))
                    if (account.recovery.isNotBlank()) {
                        ValueRow(label = "Recovery", value = account.recovery)
                    }
                    if (account.tags.isNotEmpty()) {
                        TagsRow(account.tags)
                    }

                    if (state.fields.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(spacing.lg))
                        NeriboDivider()
                        SectionHeader(
                            text = "Custom fields",
                            modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs),
                        )
                        FieldRows(
                            fields = state.fields,
                            reveal = reveal,
                            onRevealField = revealField,
                            onCopyField = copyField,
                            onCopyPlain = copyPlain,
                        )
                    }

                    Spacer(modifier = Modifier.height(spacing.lg))
                    NeriboDivider()
                    val itemWord = PlatformPresets.find(account.platform)?.itemWord ?: "Items"
                    SectionHeader(
                        text = "$itemWord · ${state.items.size}",
                        modifier = Modifier.padding(top = spacing.lg, bottom = spacing.xs),
                    )
                    if (state.items.isEmpty()) {
                        Text(
                            text = "Nothing added yet. Add the projects, pages or domains that live in this account.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = spacing.sm),
                        )
                    }
                    for (item in state.items) {
                        ItemRow(
                            item = item,
                            onOpen = { onOpenItem(item.id) },
                            onOpenLink = { openLink(item.url) },
                            onCopyLink = { copyPlain("Link", item.url) },
                            onDelete = { deleteItem(item) },
                        )
                        val itemFieldList = state.itemFields[item.id].orEmpty()
                        if (itemFieldList.isNotEmpty()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(start = spacing.md, bottom = spacing.sm)) {
                                FieldRows(
                                    fields = itemFieldList,
                                    reveal = reveal,
                                    onRevealField = revealField,
                                    onCopyField = copyField,
                                    onCopyPlain = copyPlain,
                                )
                            }
                        }
                    }
                    NeriboButton(
                        text = "Add item",
                        onClick = onAddItem,
                        modifier = Modifier.padding(top = spacing.sm),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Add,
                    )

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
                    Text(
                        text = PLAIN_TEXT_REMINDER,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.lg),
                    )
                    Spacer(modifier = Modifier.height(spacing.xl))
                }
            }
        }
    }

    SecretAccessSheets(access = access)
}

@Composable
private fun Header(account: AccountEntity) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformAvatar(platform = account.platform, size = 56.dp, customLogoPath = account.customLogoPath)
        Column(modifier = Modifier.weight(1f).padding(start = spacing.lg)) {
            Text(
                text = account.name.trim().ifEmpty { UNTITLED_ACCOUNT },
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                color = colors.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PlatformPresets.displayName(account.platform) + " · " +
                    signInMethodLabel(account.signInMethod, account.signInOtherName),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsRow(tags: List<String>) {
    val spacing = NeriboTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            for (tag in tags) StatusBadge(text = tag)
        }
    }
}
