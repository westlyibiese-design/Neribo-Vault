package com.westly.neribovault.feature.lyrics.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton

/** What the Structure tab can ask for. */
internal class StructureActions(
    val onJump: (Int) -> Unit,
    val onMoveUp: (Int) -> Unit,
    val onMoveDown: (Int) -> Unit,
    val onDuplicate: (Int) -> Unit,
    val onDelete: (Int) -> Unit,
)

/** One row per section. Tapping a row opens the editor at that section. */
@Composable
internal fun StructureTab(
    rows: List<StructureRow>,
    actions: StructureActions,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.lg,
            bottom = spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(items = rows, key = { it.index }) { row ->
            StructureRowCard(row = row, actions = actions)
        }
    }
}

@Composable
private fun StructureRowCard(row: StructureRow, actions: StructureActions) {
    val spacing = NeriboTheme.spacing
    NeriboCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { actions.onJump(row.index) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberCircle(number = row.index + 1)
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = lineCountText(row.lineCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (row.preview.isNotEmpty()) {
                    Text(
                        text = row.preview,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SectionMenu(row = row, actions = actions)
        }
    }
}

private fun lineCountText(count: Int): String = when (count) {
    0 -> "No lines"
    1 -> "1 line"
    else -> "$count lines"
}

@Composable
private fun NumberCircle(number: Int) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A three-dot menu whose Move items are really disabled at the ends of the song. */
@Composable
private fun SectionMenu(row: StructureRow, actions: StructureActions) {
    var expanded by remember { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    Box {
        NeriboIconButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "More options for ${row.label}",
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Move up", style = MaterialTheme.typography.bodyLarge) },
                enabled = row.canMoveUp,
                onClick = {
                    expanded = false
                    actions.onMoveUp(row.index)
                },
                leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Move down", style = MaterialTheme.typography.bodyLarge) },
                enabled = row.canMoveDown,
                onClick = {
                    expanded = false
                    actions.onMoveDown(row.index)
                },
                leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Duplicate section", style = MaterialTheme.typography.bodyLarge) },
                onClick = {
                    expanded = false
                    actions.onDuplicate(row.index)
                },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Delete section",
                        style = MaterialTheme.typography.bodyLarge,
                        color = error,
                    )
                },
                onClick = {
                    expanded = false
                    actions.onDelete(row.index)
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = error)
                },
            )
        }
    }
}
