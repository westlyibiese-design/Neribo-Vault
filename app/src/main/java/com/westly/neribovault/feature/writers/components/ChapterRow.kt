package com.westly.neribovault.feature.writers.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.feature.writers.wordCountLabel

/**
 * One chapter in the Chapters tab: its number, title (or "Untitled chapter") and word count,
 * with an overflow menu for Rename, Move up, Move down and Delete.
 */
@Composable
fun ChapterRow(
    number: Int,
    chapter: StoryChapterEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = chapter.title.isNotBlank()
    val actions = listOfNotNull(
        MenuAction(label = "Rename", onClick = onRename, icon = Icons.Outlined.Edit),
        if (canMoveUp) {
            MenuAction(label = "Move up", onClick = onMoveUp, icon = Icons.Outlined.ArrowUpward)
        } else {
            null
        },
        if (canMoveDown) {
            MenuAction(label = "Move down", onClick = onMoveDown, icon = Icons.Outlined.ArrowDownward)
        } else {
            null
        },
        MenuAction(
            label = "Delete",
            onClick = onDelete,
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )
    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(vertical = spacing.sm)) {
                Text(
                    text = if (hasTitle) chapter.title.trim() else "Untitled chapter",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                    color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = wordCountLabel(chapter.wordCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            OverflowMenu(actions = actions)
        }
    }
}
