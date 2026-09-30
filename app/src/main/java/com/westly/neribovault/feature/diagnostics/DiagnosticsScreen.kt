package com.westly.neribovault.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge

/** Debug screen that proves the local database works on the device. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val viewModel = neriboViewModel { container -> DiagnosticsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Diagnostics", onBack = onBack) },
    ) { padding: PaddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                NeriboButton(
                    text = if (state.isRunning) "Running…" else "Run database self-test",
                    onClick = viewModel::runSelfTest,
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.results.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader(text = "Self-test results")
                }
                items(state.results, key = { "result:" + it.name }) { result ->
                    ResultRow(result)
                }
            }
            item {
                Spacer(modifier = Modifier.height(spacing.md))
                SectionHeader(text = "Row counts")
            }
            items(state.counts, key = { "count:" + it.label }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = row.count.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SelfTestResult) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp),
                )
                StatusBadge(
                    text = if (result.passed) "Pass" else "Fail",
                    tone = if (result.passed) BadgeTone.Accent else BadgeTone.Danger,
                )
            }
            if (!result.passed && result.message != null) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}
