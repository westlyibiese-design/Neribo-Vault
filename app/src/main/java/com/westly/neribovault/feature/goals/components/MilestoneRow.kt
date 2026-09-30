package com.westly.neribovault.feature.goals.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import com.westly.neribovault.feature.goals.stepDueInfo

/**
 * One step of a goal: a checkbox-style toggle with the step's title, an optional due line and
 * a three-dot menu (Edit, Move up, Move down, Due date, Delete).
 *
 * [dayTick] changes at midnight so a step's "Overdue" never goes stale.
 */
@Composable
fun MilestoneRow(
    milestone: GoalMilestoneEntity,
    dayTick: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDueDate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val due = remember(milestone.dueDate, milestone.isDone, dayTick) {
        milestone.dueDate?.let { stepDueInfo(it, isDone = milestone.isDone) }
    }
    val actions = listOfNotNull(
        MenuAction(label = "Edit", onClick = onEdit, icon = Icons.Outlined.Edit),
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
        MenuAction(label = "Due date", onClick = onDueDate, icon = Icons.Outlined.Event),
        MenuAction(
            label = "Delete",
            onClick = onDelete,
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(
                    value = milestone.isDone,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .padding(vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (milestone.isDone) {
                    Icons.Outlined.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (milestone.isDone) colors.primary else colors.outline,
            )
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = milestone.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (milestone.isDone) colors.onSurfaceVariant else colors.onSurface,
                    textDecoration = if (milestone.isDone) TextDecoration.LineThrough else null,
                )
                if (due != null) {
                    DueLine(info = due)
                }
            }
        }
        OverflowMenu(actions = actions)
    }
}
