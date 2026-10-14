package com.westly.neribovault.feature.coder.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.developer.projectStatusLabel

/**
 * A sheet for choosing the Developer project a Coder project belongs to. The current choice is checked.
 * Tapping a row links the project and closes the sheet. Nothing is copied; only the choice is remembered.
 */
@Composable
fun CoderLinkSheet(viewModel: CoderLinkViewModel, onDismiss: () -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val linkedId by viewModel.linkedId.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val list = projects

    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Link to a Developer project",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Text(
            text = "Nothing is copied. Coder just remembers which project this belongs to.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
        )
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                text = "No Developer projects yet. Create one in the Developer vault first.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.lg),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(list, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        current = project.id == linkedId,
                        onClick = {
                            viewModel.link(project.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            NeriboButton(text = "Close", onClick = onDismiss, style = ButtonStyle.Secondary)
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectEntity, current: Boolean, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = current, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName(project),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = projectStatusLabel(project.status),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            Spacer(modifier = Modifier.width(spacing.sm))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Linked",
                modifier = Modifier.size(24.dp),
                tint = colors.primary,
            )
        }
    }
}
