package com.westly.neribovault.feature.church.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.feature.church.churchTypeLabel

/**
 * One church record in a list: title, date and type, then a quiet speaker and church line and
 * the scripture references. Tap opens it; the three dots open [actions].
 */
@Composable
fun ChurchRecordCard(
    record: ChurchRecordEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = record.title.isNotBlank()
    val whoLine = listOf(record.speaker.trim(), record.church.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" \u00B7 ")
    val scripture = record.scriptureRefs.trim()

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) record.title.trim() else "Untitled",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            if (record.isPinned) {
                Spacer(modifier = Modifier.width(spacing.sm))
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
            OverflowMenu(actions = actions)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusBadge(text = churchTypeLabel(record.type))
            Text(
                text = formatDate(record.recordDate),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (whoLine.isNotEmpty()) {
            Text(
                text = whoLine,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        if (scripture.isNotEmpty()) {
            Text(
                text = scripture,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.xs),
            )
        }
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}
