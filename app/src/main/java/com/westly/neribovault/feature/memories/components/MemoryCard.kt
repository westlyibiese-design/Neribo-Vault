package com.westly.neribovault.feature.memories.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.feature.memories.memoryMetaLine
import com.westly.neribovault.feature.memories.photoCountLabel

/**
 * One memory in the list: the first photo (or a calm tonal block), the serif title, a quiet
 * date-and-place line and the people's names. Tap opens it; long-press or the three dots open
 * Edit and Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryCard(
    memory: MemoryEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val hasTitle = memory.title.isNotBlank()
    val cover = memory.photoUris.firstOrNull()
    val people = memory.people.filter { it.isNotBlank() }

    NeriboCard(modifier = modifier.fillMaxWidth()) {
        // The click handling lives inside the card so the ripple follows its rounded corners.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClickLabel = "More options",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        ) {
            if (cover != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.sm)
                        .aspectRatio(16f / 10f)
                        .clip(MaterialTheme.shapes.small),
                ) {
                    MemoryPhoto(
                        path = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (memory.photoUris.size > 1) {
                        val pill = MaterialTheme.shapes.extraSmall
                        Text(
                            text = photoCountLabel(memory.photoUris.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(spacing.sm)
                                .clip(pill)
                                .background(colors.background.copy(alpha = 0.85f), pill)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.sm)
                        .height(88.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasTitle) memory.title.trim() else "Untitled memory",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                    color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = spacing.xs),
                )
                Box {
                    NeriboIconButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "More options",
                        onClick = { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CardMenuItem(
                            label = "Edit",
                            icon = Icons.Outlined.Edit,
                            destructive = false,
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        CardMenuItem(
                            label = "Delete",
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            Text(
                text = memoryMetaLine(memory.memoryDate, memory.location),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg),
            )
            if (people.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(
                        text = people.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
        }
    }
}

@Composable
private fun CardMenuItem(
    label: String,
    icon: ImageVector,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (destructive) colors.error else colors.onSurface
    DropdownMenuItem(
        text = { Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) colors.error else colors.onSurfaceVariant,
            )
        },
    )
}
