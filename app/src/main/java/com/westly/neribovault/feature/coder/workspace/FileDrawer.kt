package com.westly.neribovault.feature.coder.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderEntry

private const val DRAWER_ANIMATION_MS = 180
private const val DRAWER_SCRIM_ALPHA = 0.4f
private const val DRAWER_WIDTH_FRACTION = 0.85f
private const val DRAWER_MAX_WIDTH_DP = 360f

/** The Files button that opens and closes the side drawer. */
@Composable
fun FilesToggleButton(onClick: () -> Unit) {
    NeriboIconButton(
        icon = Icons.Outlined.ViewList,
        contentDescription = "Files",
        onClick = onClick,
    )
}

/**
 * The project's file tree in a panel that slides in from the left over the editor, behind a dimmed scrim. A tap
 * on the scrim closes it. It uses the same [FileTreePanel] as the Files screen, so rename, delete and the
 * "new file here" actions work the same, and it highlights the file at [activePath]. With at least two [recentFiles]
 * a Recent section sits above the tree, the header has a Search button ([onSearch]) and an Add files button ([onAddFiles]). It is built from plain
 * Compose parts, not a Material drawer, and draws nothing while it is closed.
 */
@Composable
fun FileDrawer(
    open: Boolean,
    projectName: String,
    entries: List<CoderEntry>,
    expandedFolders: List<String>,
    activePath: String?,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRename: (CoderEntry) -> Unit,
    onDelete: (CoderEntry) -> Unit,
    onMove: (CoderEntry) -> Unit,
    onNewFileIn: (String) -> Unit,
    onNewFolderIn: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    recentFiles: List<String> = emptyList(),
    onAddFiles: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = DRAWER_ANIMATION_MS),
        label = "files drawer",
    )
    if (!open && progress == 0f) return
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val panelWidth = minOf(LocalConfiguration.current.screenWidthDp * DRAWER_WIDTH_FRACTION, DRAWER_MAX_WIDTH_DP).dp
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = DRAWER_SCRIM_ALPHA * progress))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close files",
                    onClick = onClose,
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .offset(x = -(panelWidth * (1f - progress)))
                .background(colors.surface)
                // Taps on the panel itself must not fall through to the scrim and close the drawer.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = spacing.screen, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                NeriboIconButton(
                    icon = Icons.Outlined.Search,
                    contentDescription = "Search in files",
                    onClick = onSearch,
                )
                NeriboIconButton(
                    icon = Icons.Outlined.FileCopy,
                    contentDescription = "New file",
                    onClick = { onNewFileIn("") },
                )
                NeriboIconButton(
                    icon = Icons.Outlined.Folder,
                    contentDescription = "New folder",
                    onClick = { onNewFolderIn("") },
                )
                NeriboIconButton(
                    icon = Icons.Outlined.AttachFile,
                    contentDescription = "Add files",
                    onClick = onAddFiles,
                )
            }
            if (recentFiles.size >= MIN_RECENT_TO_SHOW) {
                RecentSection(recentFiles = recentFiles, entries = entries, onOpenFile = onOpenFile)
            }
            FileTreePanel(
                entries = entries,
                expandedFolders = expandedFolders,
                onToggleFolder = onToggleFolder,
                onOpenFile = onOpenFile,
                onRename = onRename,
                onDelete = onDelete,
                onMove = onMove,
                onNewFileIn = onNewFileIn,
                onNewFolderIn = onNewFolderIn,
                modifier = Modifier.weight(1f),
                activePath = activePath,
            )
        }
    }
}

/** The Recent section appears only when there are at least this many files to list. */
private const val MIN_RECENT_TO_SHOW = 2

/** The files opened last, most recent first, above the tree. A tap opens the file. */
@Composable
private fun RecentSection(recentFiles: List<String>, entries: List<CoderEntry>, onOpenFile: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    SectionHeader(text = "Recent", modifier = Modifier.padding(start = spacing.screen, end = spacing.screen, bottom = spacing.xs))
    for (path in recentFiles) {
        val kind = coderFileKindOf(path, entries.firstOrNull { it.path == path }?.sizeBytes ?: 0L)
        val icon = when (kind) {
            CoderFileKind.Code -> Icons.Outlined.Code
            CoderFileKind.Image -> Icons.Outlined.Image
            CoderFileKind.Text -> Icons.Outlined.Description
            CoderFileKind.Other -> Icons.Outlined.AttachFile
        }
        val parent = CoderPaths.parentOf(path)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Open file") { onOpenFile(path) }
                .padding(start = spacing.screen, end = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.width(spacing.md))
            Text(
                text = CoderPaths.nameOf(path),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (parent.isNotEmpty()) {
                Text(
                    text = parent,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = spacing.sm).widthIn(max = 96.dp),
                )
            }
        }
    }
    NeriboDivider(modifier = Modifier.padding(vertical = spacing.xs))
}
