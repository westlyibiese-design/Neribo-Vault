package com.westly.neribovault.feature.coder.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton

private val CONSOLE_FONT_SIZE = 12.sp
private val CONSOLE_LINE_HEIGHT = 17.sp

/**
 * The preview console as a bottom sheet. It only lists what the page wrote; nothing typed here is
 * ever run. Each line says its level in words as well as in color, and an error may have a plain-English
 * explanation under it.
 */
@Composable
fun CoderConsolePanel(
    messages: List<CoderConsoleMessage>,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    NeriboBottomSheet(onDismiss = onClose) {
        Text(
            text = "Console",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        if (messages.isEmpty()) {
            Text(
                text = "Nothing logged yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.lg),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(messages, key = { it.id }) { message -> ConsoleLine(message) }
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            NeriboButton(
                text = "Clear",
                onClick = onClear,
                style = ButtonStyle.Secondary,
                enabled = messages.isNotEmpty(),
            )
            NeriboButton(text = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun ConsoleLine(message: CoderConsoleMessage) {
    val colors = MaterialTheme.colorScheme
    val color: Color = when (message.level) {
        ConsoleLevel.Error -> colors.error
        ConsoleLevel.Warn -> NeriboTheme.extraColors.warning
        ConsoleLevel.Log -> colors.onSurface
        ConsoleLevel.Info, ConsoleLevel.Debug -> colors.onSurfaceVariant
    }
    val label = when (message.level) {
        ConsoleLevel.Error -> "Error"
        ConsoleLevel.Warn -> "Warning"
        ConsoleLevel.Log -> "Log"
        ConsoleLevel.Info -> "Info"
        ConsoleLevel.Debug -> "Debug"
    }
    val where = when {
        message.sourceFile != null && message.line != null -> " \u00B7 ${message.sourceFile}:${message.line}"
        message.sourceFile != null -> " \u00B7 ${message.sourceFile}"
        else -> ""
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label + where,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Text(
            text = message.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = CONSOLE_FONT_SIZE,
            lineHeight = CONSOLE_LINE_HEIGHT,
        )
        val friendly = message.friendly
        if (friendly != null) {
            Text(
                text = friendly,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = NeriboTheme.spacing.xs),
            )
        }
    }
}
