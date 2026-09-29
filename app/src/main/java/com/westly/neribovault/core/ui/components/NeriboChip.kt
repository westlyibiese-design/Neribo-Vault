package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Filter chip: tonal when idle, accent container when selected. 48dp touch target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeriboChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val background = if (selected) colors.primaryContainer else colors.surfaceVariant
    val content = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    val borderColor = if (selected) colors.primary.copy(alpha = 0.35f) else Color.Transparent
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(background, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
