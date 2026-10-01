package com.westly.neribovault.feature.developer.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.ui.components.BadgeTone

/** The badge tone for a project status: the accent for active projects, quiet for the rest. */
fun projectStatusTone(status: String): BadgeTone =
    if (status == "active") BadgeTone.Accent else BadgeTone.Neutral

/** A small, quiet, non-interactive pill for one technology (or "+3"). */
@Composable
fun TechChip(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
