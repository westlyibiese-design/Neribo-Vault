package com.westly.neribovault.feature.documents.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.feature.documents.documentMetaLine
import com.westly.neribovault.feature.documents.expiryLabel
import com.westly.neribovault.feature.documents.expiryState

/**
 * One document in the list: title, a quiet category-and-issuer line, the expiry label, a status
 * badge and a small paperclip when a file is attached. Tap opens it; long-press or the three
 * dots open Edit and Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentCard(
    document: PersonalDocumentEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val state = expiryState(document.expiryDate, document.remindDaysBefore)
    val meta = documentMetaLine(document.category, document.issuer)

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.xs, top = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = document.title.trim().ifEmpty { "Untitled document" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = spacing.xs),
                )
                Box {
                    NeriboIconButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "More options for ${document.title.trim().ifEmpty { "document" }}",
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
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = spacing.lg),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = expiryLabel(document.expiryDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = expiryTextColor(state),
                    modifier = Modifier.weight(1f),
                )
                if (document.fileUri != null) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = "Has an attached file",
                        modifier = Modifier.size(16.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                }
                StatusBadge(text = expiryBadgeText(state), tone = expiryBadgeTone(state))
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
