package com.westly.neribovault.feature.developer.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.developer.projectStatusLabel
import com.westly.neribovault.feature.developer.projects.TechChip
import com.westly.neribovault.feature.developer.projects.projectStatusTone

private const val MAX_CARD_TECH = 4

/**
 * One project in the list: serif name and status, a one-line description, up to four tech
 * chips (then "+n") and when it was last changed. Tap opens it; the three dots open [actions].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectCard(
    project: ProjectEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasName = project.name.isNotBlank()

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasName) project.name.trim() else "Untitled project",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = if (hasName) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = spacing.sm),
            )
            StatusBadge(
                text = projectStatusLabel(project.status),
                tone = projectStatusTone(project.status),
            )
            OverflowMenu(actions = actions)
        }
        if (project.description.isNotBlank()) {
            Text(
                text = project.description.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg),
            )
        }
        if (project.techStack.isNotEmpty()) {
            val shown = project.techStack.take(MAX_CARD_TECH)
            val extra = project.techStack.size - shown.size
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                shown.forEach { tech -> TechChip(text = tech) }
                if (extra > 0) TechChip(text = "+$extra")
            }
        }
        Text(
            text = formatRelative(project.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.sm,
                bottom = spacing.md,
            ),
        )
    }
}
