package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboIconButton

private val TAB_LABEL_MAX_WIDTH = 160.dp
private val DOT_SIZE = 8.dp

/**
 * The row of open files under the top bar, with the Files button at its start. Tap a tab to switch to it, tap
 * its X to close it, long-press it for Close, Close others and Close all. A dot on a tab means unsaved text.
 * The row scrolls sideways and brings the active tab into view. Closing is only asked for here; the caller decides
 * what to confirm.
 */
@Composable
fun TabStrip(
    tabs: List<TabInfo>,
    activePath: String?,
    onActivate: (String) -> Unit,
    onClose: (String) -> Unit,
    onCloseOthers: (String) -> Unit,
    onCloseAll: () -> Unit,
    onToggleFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val listState = rememberLazyListState()
    LaunchedEffect(activePath, tabs.size) {
        val index = tabs.indexOfFirst { it.path == activePath }
        if (index < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(index)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilesToggleButton(onClick = onToggleFiles)
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.path }) { tab ->
                TabItem(
                    tab = tab,
                    isActive = tab.path == activePath,
                    tabCount = tabs.size,
                    onActivate = { onActivate(tab.path) },
                    onClose = { onClose(tab.path) },
                    onCloseOthers = { onCloseOthers(tab.path) },
                    onCloseAll = onCloseAll,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    tab: TabInfo,
    isActive: Boolean,
    tabCount: Int,
    onActivate: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }
    val container = if (isActive) colors.secondaryContainer else Color.Transparent
    val content = if (isActive) colors.onSecondaryContainer else colors.onSurfaceVariant
    val dotColor = if (tab.saveFailed) colors.error else NeriboTheme.extraColors.warning
    val state = when {
        tab.saveFailed -> "Couldn't save"
        tab.dirty -> "Unsaved changes"
        else -> null
    }
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .background(container),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        selected = isActive
                        if (state != null) stateDescription = state
                    }
                    .combinedClickable(
                        onClick = onActivate,
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = "Close options",
                        role = Role.Tab,
                    )
                    .padding(start = spacing.md, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = TAB_LABEL_MAX_WIDTH),
                )
                if (tab.dirty || tab.saveFailed) {
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Box(modifier = Modifier.size(DOT_SIZE).clip(CircleShape).background(dotColor))
                }
            }
            NeriboIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Close ${tab.label}",
                onClick = onClose,
                tint = content,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TabMenuItem(text = "Close", enabled = true) {
                menuOpen = false
                onClose()
            }
            TabMenuItem(text = "Close others", enabled = tabCount > 1) {
                menuOpen = false
                onCloseOthers()
            }
            TabMenuItem(text = "Close all", enabled = true) {
                menuOpen = false
                onCloseAll()
            }
        }
    }
}

@Composable
private fun TabMenuItem(text: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
        onClick = onClick,
        enabled = enabled,
    )
}
