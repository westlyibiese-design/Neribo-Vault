package com.westly.neribovault.feature.developer.projects

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.developer.projectStatusLabel

/** The Overview tab: status, description, tech stack and the repo and live links. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectOverviewTab(
    project: ProjectEntity,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen, vertical = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            StatusBadge(
                text = projectStatusLabel(project.status),
                tone = projectStatusTone(project.status),
            )
            Text(
                text = "Updated ${formatRelative(project.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = project.description.trim().ifEmpty { "No description yet." },
            style = MaterialTheme.typography.bodyLarge,
            color = if (project.description.isBlank()) colors.onSurfaceVariant else colors.onSurface,
        )
        if (project.techStack.isNotEmpty()) {
            Column {
                SectionHeader("TECH STACK")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    project.techStack.forEach { tech -> TechChip(text = tech) }
                }
            }
        }
        Column {
            SectionHeader("LINKS")
            Spacer(modifier = Modifier.height(spacing.xs))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                LinkRow(label = "Repository", url = project.repoUrl, onMessage = onMessage)
                NeriboDivider()
                LinkRow(label = "Live site", url = project.liveUrl, onMessage = onMessage)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val clean = url.trim()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = spacing.sm)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = clean.ifEmpty { "Not added" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (clean.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (clean.isNotEmpty()) {
            NeriboButton(
                text = "Open",
                onClick = {
                    if (!openLink(context, clean)) onMessage("No app can open that link")
                },
                style = ButtonStyle.Text,
            )
            NeriboIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label link",
                onClick = {
                    context.copyToClipboard(label, clean)
                    onMessage("Link copied")
                },
            )
        }
    }
}

/** Opens [url] in whatever app handles it. Returns false if nothing can. */
private fun openLink(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
