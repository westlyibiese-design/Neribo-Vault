package com.westly.neribovault.feature.developer.bugs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.BugEntity

/** The route argument value that means "report a new bug". */
internal const val BUG_NEW_ID = "new"

internal const val SEVERITY_LOW = "low"
internal const val SEVERITY_MEDIUM = "medium"
internal const val SEVERITY_HIGH = "high"
internal const val SEVERITY_CRITICAL = "critical"

internal const val BUG_OPEN = "open"
internal const val BUG_IN_PROGRESS = "in_progress"
internal const val BUG_RESOLVED = "resolved"
internal const val BUG_CLOSED = "closed"

internal val BUG_SEVERITIES: List<String> =
    listOf(SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH, SEVERITY_CRITICAL)

internal val BUG_STATUSES: List<String> =
    listOf(BUG_OPEN, BUG_IN_PROGRESS, BUG_RESOLVED, BUG_CLOSED)

/** Display name of a severity value. Unknown values read as Medium. */
internal fun severityLabel(severity: String): String = when (severity) {
    SEVERITY_LOW -> "Low"
    SEVERITY_HIGH -> "High"
    SEVERITY_CRITICAL -> "Critical"
    else -> "Medium"
}

/** Display name of a status value. Unknown values read as Open. */
internal fun bugStatusLabel(status: String): String = when (status) {
    BUG_IN_PROGRESS -> "In progress"
    BUG_RESOLVED -> "Resolved"
    BUG_CLOSED -> "Closed"
    else -> "Open"
}

/** Sort rank of a severity: critical is 0 so it comes first. */
internal fun severityRank(severity: String): Int = when (severity) {
    SEVERITY_CRITICAL -> 0
    SEVERITY_HIGH -> 1
    SEVERITY_LOW -> 3
    else -> 2
}

/** Critical is Danger, High is Warning, everything else is Neutral. */
internal fun severityTone(severity: String): BadgeTone = when (severity) {
    SEVERITY_CRITICAL -> BadgeTone.Danger
    SEVERITY_HIGH -> BadgeTone.Warning
    else -> BadgeTone.Neutral
}

/** True for Resolved and Closed. */
internal fun isFinishedStatus(status: String): Boolean =
    status == BUG_RESOLVED || status == BUG_CLOSED

/** True for Open and In progress. */
internal fun isActiveStatus(status: String): Boolean = !isFinishedStatus(status)

/**
 * Moves a bug to [newStatus]. Resolved and Closed stamp `resolvedAt` if it is empty; Open and
 * In progress clear it.
 */
internal fun BugEntity.withStatus(newStatus: String, now: Long): BugEntity =
    if (isFinishedStatus(newStatus)) {
        copy(status = newStatus, resolvedAt = resolvedAt ?: now)
    } else {
        copy(status = newStatus, resolvedAt = null)
    }

/** A clean plain-text report: title, severity, status, description, steps and resolution. */
internal fun bugPlainText(
    title: String,
    severity: String,
    status: String,
    description: String,
    steps: String,
    resolution: String,
): String {
    val parts = mutableListOf<String>()
    parts += title.trim().ifEmpty { "Untitled bug" }
    parts += "Severity: ${severityLabel(severity)}\nStatus: ${bugStatusLabel(status)}"
    if (description.isNotBlank()) parts += "Description\n${description.trim()}"
    if (steps.isNotBlank()) parts += "Steps to reproduce\n${steps.trim()}"
    if (resolution.isNotBlank() && isFinishedStatus(status)) {
        parts += "Resolution\n${resolution.trim()}"
    }
    return parts.joinToString("\n\n")
}

/** [bugPlainText] for a saved bug. */
internal fun BugEntity.toPlainText(): String = bugPlainText(
    title = title,
    severity = severity,
    status = status,
    description = description,
    steps = stepsToReproduce,
    resolution = resolution,
)

/**
 * One bug in a list: title, severity badge, status, a two-line description preview and when it
 * last changed. Tap opens it; the three dots open [actions].
 */
@Composable
internal fun BugCard(
    bug: BugEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = bug.title.isNotBlank()
    val preview = snippet(bug.description, 160)

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) bug.title.trim() else "Untitled bug",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusBadge(text = severityLabel(bug.severity), tone = severityTone(bug.severity))
            Text(
                text = bugStatusLabel(bug.status),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = "\u00B7",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = formatRelative(bug.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}

/** A bottom sheet listing the four statuses, with a check on the current one. */
@Composable
internal fun BugStatusSheet(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Change status",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        BUG_STATUSES.forEach { status ->
            val selected = status == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { onSelect(status) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = bugStatusLabel(status),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) colors.primary else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Current status",
                        tint = colors.primary,
                    )
                }
            }
        }
    }
}

/** A text field with no border or fill, sitting straight on the paper background. */
@Composable
internal fun BugBorderlessField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = maxLines,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = colors.onSurfaceVariant),
                    )
                }
                inner()
            }
        },
    )
}
