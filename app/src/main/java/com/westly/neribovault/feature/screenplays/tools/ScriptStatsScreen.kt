package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDateTime

/** Pages, scenes, words, characters, dialogue share, running time and a few details. */
@Composable
fun ScriptStatsScreen(screenplayId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "script-stats-$screenplayId") { c ->
        ScriptStatsViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val stats = state.stats
    val spacing = NeriboTheme.spacing

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Script stats", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Unit
                stats == null || !stats.hasContent -> EmptyState(
                    icon = Icons.Outlined.Description,
                    title = "Nothing to measure yet",
                    message = "Write a scene or two and the numbers will show up here.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    MetricRow("Pages", stats.pages.toString(), "Scenes", stats.scenes.toString())
                    MetricRow("Words", stats.words.toString(), "Characters", stats.characters.toString())
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                        MetricCard(label = "Speeches", value = stats.speeches.toString(), modifier = Modifier.weight(1f))
                        Box(modifier = Modifier.weight(1f))
                    }
                    WideCard(label = "Estimated running time", value = stats.runningTime)
                    ShareCard(dialoguePercent = stats.dialoguePercent, actionPercent = stats.actionPercent)
                    DetailsCard(stats = stats, updatedAt = state.updatedAt)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(labelA: String, valueA: String, labelB: String, valueB: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.md)) {
        MetricCard(label = labelA, value = valueA, modifier = Modifier.weight(1f))
        MetricCard(label = labelB, value = valueB, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WideCard(label: String, value: String) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShareCard(dialoguePercent: Int, actionPercent: Int) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            SectionHeader("Dialogue and action")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = "$dialoguePercent%",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Dialogue share",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$actionPercent%",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Action share",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceVariant),
            ) {
                if (dialoguePercent > 0) {
                    Box(
                        modifier = Modifier
                            .weight(dialoguePercent.toFloat())
                            .fillMaxSize()
                            .background(colors.primary),
                    )
                }
                if (actionPercent > 0) {
                    Box(modifier = Modifier.weight(actionPercent.toFloat()).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(stats: ScriptStats, updatedAt: Long) {
    val spacing = NeriboTheme.spacing
    val longest = stats.longestScene
    val talkative = stats.topSpeaker
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg)) {
            if (longest != null) {
                DetailRow(
                    label = "Longest scene",
                    value = longest.heading,
                    extra = ScriptAnalysis.lengthLabel(longest.rows),
                )
                NeriboDivider()
            }
            if (talkative != null) {
                DetailRow(
                    label = "Most talkative character",
                    value = talkative.name,
                    extra = speechesLabel(talkative.speeches),
                )
                NeriboDivider()
            }
            DetailRow(label = "Last edited", value = formatDateTime(updatedAt), extra = null)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, extra: String?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (extra != null) {
            Text(text = extra, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** "1 speech" or "12 speeches". */
internal fun speechesLabel(count: Int): String = if (count == 1) "1 speech" else "$count speeches"
