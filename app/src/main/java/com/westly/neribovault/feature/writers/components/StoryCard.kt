package com.westly.neribovault.feature.writers.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.repository.StoryStats
import com.westly.neribovault.feature.writers.StoryOptions
import com.westly.neribovault.feature.writers.storyStatsLine

/**
 * One story in the list: serif title, genre and status, a two-line synopsis, a quiet chapter
 * and word line, and a slim progress bar when the story has a target word count.
 */
@Composable
fun StoryCard(
    story: StoryEntity,
    stats: StoryStats?,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = story.title.isNotBlank()
    val words = stats?.wordCount ?: 0
    val chapters = stats?.chapterCount ?: 0

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) story.title.trim() else "Untitled story",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = story.genre,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            StatusBadge(
                text = StoryOptions.statusLabel(story.status),
                tone = StoryOptions.statusTone(story.status),
            )
        }
        val synopsis = story.synopsis.trim()
        if (synopsis.isNotEmpty()) {
            Text(
                text = synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        Text(
            text = storyStatsLine(chapters, words),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.md),
        )
        StoryProgress(
            words = words,
            target = story.targetWordCount,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
        )
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}
