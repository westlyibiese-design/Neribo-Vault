package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileCopy
import com.westly.neribovault.feature.coder.files.CoderPaths

private val MOVE_INDENT_PER_LEVEL = 16.dp
private val MOVE_LIST_MAX_HEIGHT = 320.dp
private const val MOVE_DISABLED_ALPHA = 0.38f

/**
 * Asks where [entry] should go. The list shows the top of the project first and then every folder of [folders],
 * indented by depth, without [entry] itself and the folders inside it. The folder it is already in cannot be chosen.
 * Choosing a folder calls [onMove] with its path ("" is the top of the project); nothing is overwritten.
 */
@Composable
fun MoveDialog(entry: CoderEntry, folders: List<String>, onMove: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val targets = CoderFileCopy.moveTargets(folders, entry.path)
    val current = CoderPaths.parentOf(entry.path)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        },
        title = { Text(text = "Move to\u2026", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    text = "Choose a folder for ${CoderPaths.nameOf(entry.path)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = spacing.sm),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = MOVE_LIST_MAX_HEIGHT)) {
                    items(targets, key = { it }) { folder ->
                        FolderRow(folder = folder, isCurrent = folder == current, onClick = { onMove(folder) })
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

@Composable
private fun FolderRow(folder: String, isCurrent: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val depth = if (folder.isEmpty()) 0 else folder.count { it == '/' } + 1
    val tint = if (isCurrent) colors.onSurface.copy(alpha = MOVE_DISABLED_ALPHA) else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = !isCurrent, onClickLabel = "Move here", onClick = onClick)
            .padding(start = MOVE_INDENT_PER_LEVEL * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = if (folder.isEmpty()) "Top of the project" else CoderPaths.nameOf(folder),
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Text(
                text = "Current folder",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
    }
}
