package com.westly.neribovault.feature.writers.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.writers.formatStoryCount

/**
 * A slim progress bar with a label such as "62% of 50,000 words". Draws nothing when the story
 * has no target word count.
 */
@Composable
fun StoryProgress(words: Int, target: Int?, modifier: Modifier = Modifier) {
    if (target == null || target <= 0) return
    val fraction = (words.toFloat() / target.toFloat()).coerceIn(0f, 1f)
    val percent = ((words.toLong() * 100L) / target.toLong()).toInt().coerceIn(0, 100)
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.outlineVariant)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(colors.primary),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$percent% of ${formatStoryCount(target)} words",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}
