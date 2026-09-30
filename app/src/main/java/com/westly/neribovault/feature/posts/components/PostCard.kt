package com.westly.neribovault.feature.posts.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.feature.posts.STATUS_POSTED
import com.westly.neribovault.feature.posts.formatPostSchedule
import com.westly.neribovault.feature.posts.formatPostedOn
import com.westly.neribovault.feature.posts.platformInfo
import com.westly.neribovault.feature.posts.postStatusLabel
import com.westly.neribovault.feature.posts.postStatusTone

/** Only this much of the caption is looked at when building the two-line preview. */
private const val PREVIEW_SOURCE_CHARS = 400

/**
 * One post in a list: private title, platform and time, a two-line caption preview and a status
 * badge. Tap opens it; the three dots open [actions]. Pass [onMarkPosted] to show a quick
 * "Mark as posted" button (used on the Upcoming tab), and [isOverdue] to show the warning tone.
 */
@Composable
fun PostCard(
    post: SocialPostEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOverdue: Boolean = false,
    onMarkPosted: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val warning = NeriboTheme.extraColors.warning
    val preview = remember(post.caption) { snippet(post.caption.take(PREVIEW_SOURCE_CHARS)) }
    val hasTitle = post.title.isNotBlank()
    val timeText = timeLineFor(post)

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) post.title.trim() else "Untitled post",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = spacing.lg),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    end = spacing.lg,
                    top = spacing.md,
                    bottom = if (onMarkPosted != null) spacing.xs else spacing.lg,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = platformInfo(post.platform).displayName,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
            )
            if (timeText != null) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isOverdue) warning else colors.onSurfaceVariant,
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOverdue) warning else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.width(spacing.xs))
            StatusBadge(
                text = if (isOverdue) "Overdue" else postStatusLabel(post.status),
                tone = if (isOverdue) BadgeTone.Warning else postStatusTone(post.status),
            )
        }
        if (onMarkPosted != null) {
            NeriboButton(
                text = "Mark as posted",
                onClick = onMarkPosted,
                modifier = Modifier.padding(start = spacing.sm, bottom = spacing.xs),
                style = ButtonStyle.Text,
                leadingIcon = Icons.Outlined.Check,
            )
        }
    }
}

/** "Posted 29 Sep" for posted posts, "Sat 3 Oct, 6:30 PM" for planned ones, otherwise null. */
private fun timeLineFor(post: SocialPostEntity): String? {
    val postedAt = post.postedAt
    val scheduledAt = post.scheduledAt
    return when {
        post.status == STATUS_POSTED && postedAt != null -> formatPostedOn(postedAt)
        post.status != STATUS_POSTED && scheduledAt != null -> formatPostSchedule(scheduledAt)
        else -> null
    }
}
