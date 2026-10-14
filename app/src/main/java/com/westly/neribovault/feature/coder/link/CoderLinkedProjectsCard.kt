package com.westly.neribovault.feature.coder.link

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.data.local.entity.CoderProjectEntity

/**
 * The "Coder projects" card at the bottom of a Developer project's Overview tab. Each linked Coder project
 * is a row that opens it in Coder through [onOpenProject]. With none linked it shows one quiet line.
 */
@Composable
fun CoderLinkedProjectsCard(developerProjectId: String, onOpenProject: (String) -> Unit) {
    val vm = neriboViewModel(key = "coder-linked-$developerProjectId") { c ->
        CoderLinkedProjectsViewModel(developerProjectId, c.coderProjectsRepository)
    }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val list = projects ?: return
    val spacing = NeriboTheme.spacing
    Column {
        SectionHeader("Coder projects")
        Spacer(modifier = Modifier.height(spacing.xs))
        NeriboCard(modifier = Modifier.fillMaxWidth()) {
            if (list.isEmpty()) {
                Text(
                    text = "No Coder projects linked yet. Link one from inside Coder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                )
            } else {
                list.forEachIndexed { index, project ->
                    if (index > 0) NeriboDivider()
                    CoderProjectRow(project = project, onClick = { onOpenProject(project.id) })
                }
            }
        }
    }
}

@Composable
private fun CoderProjectRow(project: CoderProjectEntity, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
            Text(
                text = project.name.trim().ifEmpty { "Untitled project" },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Updated ${formatRelative(project.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
    }
}
