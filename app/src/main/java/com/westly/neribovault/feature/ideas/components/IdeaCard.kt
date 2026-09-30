package com.westly.neribovault.feature.ideas.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.feature.ideas.ideaCategoryLabel
import com.westly.neribovault.feature.ideas.ideaStatusLabel
import com.westly.neribovault.feature.ideas.ideaStatusTone

/** Only this much of the description is looked at when building the two-line preview. */
private const val PREVIEW_SOURCE_CHARS = 400

/**
 * One idea in the list: title, two-line preview, a quiet category label and a status badge.
 * Tap opens it; long-press or the three dots open [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdeaCard(
    idea: IdeaEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shape = MaterialTheme.shapes.medium
    var menuOpen by remember { mutableStateOf(false) }
    val preview = remember(idea.description) { snippet(idea.description.take(PREVIEW_SOURCE_CHARS)) }
    val hasTitle = idea.title.isNotBlank()

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
                text = if (hasTitle) idea.title.trim() else "Untitled idea",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            if (idea.isPinned) {
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
                IdeaActionsMenu(
                    expanded = menuOpen,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    end = spacing.lg,
                    top = spacing.md,
                    bottom = spacing.lg,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ideaCategoryLabel(idea.category),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            StatusBadge(
                text = ideaStatusLabel(idea.status),
                tone = ideaStatusTone(idea.status),
            )
        }
    }
}

@Composable
private fun IdeaActionsMenu(
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
