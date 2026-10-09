package com.westly.neribovault.feature.accounts.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.feature.accounts.itemStatusLabel
import com.westly.neribovault.feature.accounts.itemTypeLabel

/** One item under an account: name, type, status and link, with a small menu. */
@Composable
fun ItemRow(
    item: AccountItemEntity,
    onOpen: () -> Unit,
    onOpenLink: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val hasLink = item.url.isNotBlank()
    val actions = buildList<MenuAction> {
        if (hasLink) {
            add(MenuAction(label = "Open link", onClick = onOpenLink, icon = Icons.Outlined.Link))
            add(MenuAction(label = "Copy link", onClick = onCopyLink, icon = Icons.Outlined.ContentCopy))
        }
        add(
            MenuAction(
                label = "Delete",
                onClick = onDelete,
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onOpen)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name.ifBlank { "Untitled item" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.padding(top = spacing.xxs),
            ) {
                Text(
                    text = itemTypeLabel(item.itemType),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                StatusBadge(
                    text = itemStatusLabel(item.status),
                    tone = if (item.status == "active") BadgeTone.Neutral else BadgeTone.Warning,
                )
            }
            if (hasLink) {
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OverflowMenu(actions = actions)
    }
}
