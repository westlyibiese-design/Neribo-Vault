package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Flat card: tonal fill plus a 1dp hairline border, never a shadow. */
@Composable
fun NeriboCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val base = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface, shape)
        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
    Column(
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
        content = content,
    )
}
