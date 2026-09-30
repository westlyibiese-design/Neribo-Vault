package com.westly.neribovault.feature.goals.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.feature.goals.GoalListItem
import com.westly.neribovault.feature.goals.goalCategoryLabel
import com.westly.neribovault.feature.goals.goalDueInfo

/**
 * One goal in the list: title, quiet category label, slim progress bar with "3 of 5 steps",
 * and the due line. Tap opens it; long-press or the three dots open [actions].
 *
 * [dayTick] changes at midnight so "days left" and "Overdue" never go stale.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GoalCard(
    item: GoalListItem,
    dayTick: Int,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shape = MaterialTheme.shapes.medium
    val goal = item.goal
    var menuOpen by remember { mutableStateOf(false) }
    val due = remember(goal.targetDate, goal.status, dayTick) {
        goal.targetDate?.let { goalDueInfo(it, isCompleted = goal.status == "completed") }
    }

    NeriboCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "More options",
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = goal.title.trim().ifEmpty { "Untitled goal" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            if (goal.isPinned) {
                Spacer(modifier = Modifier.width(spacing.sm))
                Icon(
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
            Box {
                NeriboIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                GoalActionsMenu(
                    expanded = menuOpen,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
        ) {
            Text(
                text = goalCategoryLabel(goal.category),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            GoalProgressBar(fraction = item.progress.fraction)
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = item.progress.label,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            if (due != null) {
                Spacer(modifier = Modifier.height(spacing.xxs))
                DueLine(info = due)
            }
        }
    }
}

@Composable
private fun GoalActionsMenu(
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
