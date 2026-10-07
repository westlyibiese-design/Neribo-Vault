package com.westly.neribovault.feature.lyrics.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.util.formatDateTime

/** Counts as big numbers, then the longest line, the most repeated line and the last edit. */
@Composable
internal fun OverviewTab(state: SongToolsUiState, modifier: Modifier = Modifier) {
    val stats = state.stats ?: return
    val spacing = NeriboTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen)
            .padding(top = spacing.lg, bottom = spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            MetricCard("Sections", stats.sections.toString(), Modifier.weight(1f))
            MetricCard("Lines", stats.lines.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
            MetricCard("Words", stats.words.toString(), Modifier.weight(1f))
            MetricCard("Syllables (estimate)", stats.syllables.toString(), Modifier.weight(1f))
        }
        NeriboCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = spacing.lg)) {
                DetailRow("Longest line", longestLineText(stats.longestLine))
                NeriboDivider()
                DetailRow("Most repeated line", repeatedText(state.mostRepeated))
                NeriboDivider()
                DetailRow("Last edited", formatDateTime(state.updatedAt))
            }
        }
        Text(
            text = "Syllables are counted by a simple rule of thumb, so treat them as a guide.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
    }
}

private fun longestLineText(length: Int): String =
    if (length == 1) "1 character" else "$length characters"

private fun repeatedText(repeated: Pair<String, Int>?): String =
    if (repeated == null) "No repeated lines" else "\"${repeated.first}\" · ${repeated.second} times"

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
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
private fun DetailRow(label: String, value: String) {
    val spacing = NeriboTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
