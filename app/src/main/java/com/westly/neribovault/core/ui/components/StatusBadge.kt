package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme

/** Small pill for statuses such as "Expires soon" or "Draft". */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Neutral,
) {
    val colors = MaterialTheme.colorScheme
    val extra = NeriboTheme.extraColors
    val background = when (tone) {
        BadgeTone.Neutral -> colors.surfaceVariant
        BadgeTone.Accent -> colors.primaryContainer
        BadgeTone.Warning -> extra.warning.copy(alpha = 0.16f)
        BadgeTone.Danger -> colors.errorContainer
    }
    val content = when (tone) {
        BadgeTone.Neutral -> colors.onSurfaceVariant
        BadgeTone.Accent -> colors.onPrimaryContainer
        BadgeTone.Warning -> extra.warning
        BadgeTone.Danger -> colors.onErrorContainer
    }
    val shape = MaterialTheme.shapes.extraSmall
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
