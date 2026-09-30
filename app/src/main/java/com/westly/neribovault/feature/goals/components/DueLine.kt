package com.westly.neribovault.feature.goals.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.feature.goals.DueInfo

/** "Due 14 Dec 2026 · 76 days left", with "Overdue" in the warning color. */
@Composable
fun DueLine(info: DueInfo, modifier: Modifier = Modifier) {
    val warning = NeriboTheme.extraColors.warning
    val text = buildAnnotatedString {
        append(info.prefix)
        if (info.suffix.isNotEmpty()) {
            append(" \u00B7 ")
            if (info.isOverdue) {
                withStyle(SpanStyle(color = warning)) { append(info.suffix) }
            } else {
                append(info.suffix)
            }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
