package com.westly.neribovault.feature.accounts.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_CLOSED
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_INACTIVE
import com.westly.neribovault.feature.accounts.AccountListItem
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.UNTITLED_ACCOUNT
import com.westly.neribovault.feature.accounts.accountStatusLabel
import com.westly.neribovault.feature.accounts.signInMethodLabel

/**
 * One account in the list: letter avatar, serif name, a quiet "platform . sign-in method" line,
 * a status badge, the item count and a pin mark. Tap opens it; long-press or the three dots
 * open [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountCard(
    item: AccountListItem,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val account = item.account
    var menuOpen by remember { mutableStateOf(false) }
    val line = PlatformPresets.displayName(account.platform) + " \u00B7 " + signInMethodLabel(account.signInMethod, account.signInOtherName)

    NeriboCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "More options",
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.md, end = spacing.xs, bottom = spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            PlatformAvatar(
                platform = account.platform,
                size = 40.dp,
                modifier = Modifier.padding(top = spacing.xs),
                customLogoPath = account.customLogoPath,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md, top = spacing.xs),
            ) {
                Text(
                    text = account.name.trim().ifEmpty { UNTITLED_ACCOUNT },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = accountStatusLabel(account.status),
                        tone = statusTone(account.status),
                    )
                    if (item.itemCount > 0) {
                        Text(
                            text = if (item.itemCount == 1) "1 item" else "${item.itemCount} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                    if (account.isPinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier
                                .padding(start = spacing.sm)
                                .size(16.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                NeriboIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                AccountActionsMenu(
                    expanded = menuOpen,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

private fun statusTone(status: String): BadgeTone = when (status) {
    ACCOUNT_STATUS_INACTIVE -> BadgeTone.Warning
    ACCOUNT_STATUS_CLOSED -> BadgeTone.Danger
    else -> BadgeTone.Neutral
}

@Composable
private fun AccountActionsMenu(
    expanded: Boolean,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            val tint = if (action.destructive) colors.error else colors.onSurface
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tint,
                    )
                },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                leadingIcon = action.icon?.let { vector ->
                    {
                        Icon(
                            imageVector = vector,
                            contentDescription = null,
                            tint = if (action.destructive) colors.error else colors.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}
