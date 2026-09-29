package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.westly.neribovault.core.design.NeriboTheme

/** One item on a Recently deleted screen, with Restore and Delete forever. */
@Composable
fun TrashRow(
    title: String,
    subtitle: String,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.lg),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.xxs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRestore) {
                Text(
                    text = "Restore",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
            TextButton(onClick = onDeleteForever) {
                Text(
                    text = "Delete forever",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.error,
                )
            }
        }
    }
}
