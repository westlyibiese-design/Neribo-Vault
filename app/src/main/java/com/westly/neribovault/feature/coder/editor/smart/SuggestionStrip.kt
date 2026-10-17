package com.westly.neribovault.feature.coder.editor.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboDivider

/**
 * One calm line above the extra keys. With [suggestions] it shows them as a row of small chips you can scroll
 * sideways; with none and an [issueMessage] it shows that message instead; with neither it takes no space.
 * Tapping a chip calls [onAccept].
 */
@Composable
fun SuggestionStrip(
    suggestions: List<Suggestion>,
    issueMessage: String?,
    onAccept: (Suggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty() && issueMessage == null) return
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        NeriboDivider()
        if (suggestions.isNotEmpty()) {
            val scroll = rememberScrollState()
            LaunchedEffect(suggestions) { scroll.scrollTo(0) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = spacing.xs, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (suggestion in suggestions) {
                    SuggestionChip(suggestion = suggestion, onAccept = onAccept)
                }
            }
        } else if (issueMessage != null) {
            Text(
                text = issueMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
    }
}

@Composable
private fun SuggestionChip(suggestion: Suggestion, onAccept: (Suggestion) -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceVariant)
            .clickable { onAccept(suggestion) }
            .semantics {
                contentDescription = suggestion.label + ": " + suggestion.detail
                role = Role.Button
            }
            .padding(horizontal = spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = suggestion.label,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = colors.onSurface,
            maxLines = 1,
        )
    }
}
