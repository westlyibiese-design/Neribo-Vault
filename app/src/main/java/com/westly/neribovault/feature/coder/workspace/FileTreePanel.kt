package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.feature.backup.formatBytes
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths

private val INDENT_PER_LEVEL = 16.dp

private val NON_TEXT_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "gz", "tar", "7z", "rar", "mp3",
    "wav", "ogg", "mp4", "mov", "webm", "ttf", "otf", "woff", "woff2", "apk", "jar", "class", "so",
)

/**
 * The project's files as a tree. Folders open and close when tapped; [expandedFolders] is kept by
 * the caller so it survives rotation. Tapping a file calls [onOpenFile]. The file at [activePath], when given,
 * is highlighted (the side drawer passes the active tab; the Files screen passes nothing).
 */
@Composable
fun FileTreePanel(
    entries: List<CoderEntry>,
    expandedFolders: List<String>,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRename: (CoderEntry) -> Unit,
    onDelete: (CoderEntry) -> Unit,
    onNewFileIn: (String) -> Unit,
    onNewFolderIn: (String) -> Unit,
    modifier: Modifier = Modifier,
    activePath: String? = null,
) {
    if (entries.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Description,
            title = "No files yet",
            message = "Add a file to start writing.",
            modifier = modifier.fillMaxSize(),
            actionLabel = "New file",
            onAction = { onNewFileIn("") },
        )
        return
    }
    val visible = remember(entries, expandedFolders) {
        val open = expandedFolders.toSet()
        entries.filter { entry -> ancestorsOpen(entry.path, open) }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(visible, key = { it.path }) { entry ->
            TreeRow(
                entry = entry,
                expanded = entry.path in expandedFolders,
                isActive = !entry.isDirectory && entry.path == activePath,
                onClick = { if (entry.isDirectory) onToggleFolder(entry.path) else onOpenFile(entry.path) },
                onRename = { onRename(entry) },
                onDelete = { onDelete(entry) },
                onNewFileIn = { onNewFileIn(entry.path) },
                onNewFolderIn = { onNewFolderIn(entry.path) },
            )
        }
    }
}

private fun ancestorsOpen(path: String, open: Set<String>): Boolean {
    var parent = CoderPaths.parentOf(path)
    while (parent.isNotEmpty()) {
        if (parent !in open) return false
        parent = CoderPaths.parentOf(parent)
    }
    return true
}

private fun looksNonEditable(entry: CoderEntry): Boolean {
    if (entry.sizeBytes > CoderLimits.MAX_EDITABLE_BYTES) return true
    val name = CoderPaths.nameOf(entry.path)
    val dot = name.lastIndexOf('.')
    return dot >= 0 && name.substring(dot + 1).lowercase() in NON_TEXT_EXTENSIONS
}

@Composable
private fun TreeRow(
    entry: CoderEntry,
    expanded: Boolean,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewFileIn: () -> Unit,
    onNewFolderIn: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val depth = entry.path.count { it == '/' }
    val name = CoderPaths.nameOf(entry.path)
    val icon = when {
        entry.isDirectory && expanded -> Icons.Outlined.FolderOpen
        entry.isDirectory -> Icons.Outlined.Folder
        looksNonEditable(entry) -> Icons.Outlined.AttachFile
        else -> Icons.Outlined.Description
    }
    val actions = buildList {
        add(MenuAction(label = "Rename", onClick = onRename, icon = Icons.Outlined.Edit))
        if (entry.isDirectory) {
            add(MenuAction(label = "New file here", onClick = onNewFileIn, icon = Icons.Outlined.FileCopy))
            add(MenuAction(label = "New folder here", onClick = onNewFolderIn, icon = Icons.Outlined.Folder))
        }
        add(MenuAction(label = "Delete", onClick = onDelete, icon = Icons.Outlined.Delete, destructive = true))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (isActive) colors.secondaryContainer else Color.Transparent)
            .semantics { if (isActive) stateDescription = "Current file" }
            .clickable(onClick = onClick)
            .padding(start = spacing.screen + INDENT_PER_LEVEL * depth, end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (entry.isDirectory) "Folder" else "File",
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            Text(
                text = formatBytes(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        Box(modifier = Modifier.semantics { contentDescription = "Options for $name" }) {
            OverflowMenu(actions = actions)
        }
    }
}
