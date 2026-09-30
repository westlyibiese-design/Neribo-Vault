package com.westly.neribovault.feature.diary.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.feature.diary.DiaryMood
import com.westly.neribovault.feature.diary.headline
import com.westly.neribovault.feature.diary.previewSource
import com.westly.neribovault.feature.diary.toLocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val MAX_VISIBLE_TAGS = 3

/** Only this much of the body is looked at when building the two-line preview. */
private const val PREVIEW_SOURCE_CHARS = 400

/**
 * One diary entry in a list: the day as a small block, the title (or first line), a two-line
 * preview, a quiet mood label and tags. Tap opens it; long-press or the three dots open [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryEntryCard(
    entry: DiaryEntryEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shape = MaterialTheme.shapes.medium
    var menuOpen by remember { mutableStateOf(false) }
    val date = remember(entry.entryDate) { entry.entryDate.toLocalDate() }
    val weekday = remember(date) { date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    val headline = remember(entry.title, entry.body) { entry.headline() }
    val preview = remember(entry.title, entry.body) {
        snippet(entry.previewSource().take(PREVIEW_SOURCE_CHARS))
    }
    val moodLabel = DiaryMood.labelFor(entry.mood)
    val showFooter = moodLabel != null || entry.tags.isNotEmpty()

    NeriboCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "More options",
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.md, bottom = spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            DayBlock(day = date.dayOfMonth.toString(), weekday = weekday)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md, top = spacing.xs),
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.title.isBlank() && entry.body.isBlank()) {
                        colors.onSurfaceVariant
                    } else {
                        colors.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showFooter) {
                    Row(
                        modifier = Modifier.padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (moodLabel != null) {
                            Text(
                                text = moodLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                maxLines = 1,
                            )
                        }
                        val shown = entry.tags.take(MAX_VISIBLE_TAGS)
                        shown.forEach { tag -> QuietLabel(text = tag, modifier = Modifier.weight(1f, fill = false)) }
                        val extra = entry.tags.size - shown.size
                        if (extra > 0) QuietLabel(text = "+$extra")
                    }
                }
            }
            Box {
                NeriboIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                DiaryActionsMenu(
                    expanded = menuOpen,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun DayBlock(day: String, weekday: String) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceVariant)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = weekday,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun DiaryActionsMenu(
    expanded: Boolean,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            val tint = if (action.destructive) colors.error else colors.onSurface
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tint,
                    )
                },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                leadingIcon = action.icon?.let { vector ->
                    {
                        Icon(
                            imageVector = vector,
                            contentDescription = null,
                            tint = if (action.destructive) colors.error else colors.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun QuietLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
