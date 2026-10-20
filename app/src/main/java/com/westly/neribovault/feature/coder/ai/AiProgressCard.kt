package com.westly.neribovault.feature.coder.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard

/**
 * The progress of one AI answer. While the answer runs it is a small card that lists only the real steps reached
 * (see [CoderAiStages.steps]); when the answer ended it shrinks to one muted line such as "Wrote 2 files in 14 s".
 * [writingTarget] names what is being written (a path, "code", or null for text); [filesWritten] and [otherBlocks]
 * count the finished code blocks with and without a path. The step text is a polite live region, so a screen reader
 * announces each new step and the ending; the seconds counter is outside it so it is not read every second.
 */
@Composable
fun AiProgressCard(
    progress: CoderAiProgress,
    writingTarget: String?,
    filesWritten: Int,
    otherBlocks: Int,
    elapsedMillis: Long,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    if (CoderAiStages.isTerminal(progress.stage)) {
        Text(
            text = CoderAiStages.summary(progress, filesWritten, otherBlocks, elapsedMillis),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    val steps = CoderAiStages.steps(progress, writingTarget, elapsedMillis)
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            for ((index, step) in steps.withIndex()) {
                val isLast = index == steps.lastIndex
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step.done) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Done",
                            tint = colors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.done) colors.onSurfaceVariant else colors.onSurface,
                        modifier = if (isLast) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    )
                    val detail = step.detail
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
