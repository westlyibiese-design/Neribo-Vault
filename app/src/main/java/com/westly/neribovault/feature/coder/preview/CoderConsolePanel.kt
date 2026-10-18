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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.feature.coder.guidance.WhatIsThis

private val CONSOLE_FONT_SIZE = 12.sp
private val CONSOLE_LINE_HEIGHT = 17.sp

/** Which messages the console list shows. Exactly one is chosen at a time. */
enum class ConsoleFilter { All, Errors, Warnings }

/** The messages that [filter] lets through, in the same order. [ConsoleFilter.All] keeps every message. */
fun filterConsoleMessages(messages: List<CoderConsoleMessage>, filter: ConsoleFilter): List<CoderConsoleMessage> =
    when (filter) {
        ConsoleFilter.All -> messages
        ConsoleFilter.Errors -> messages.filter { it.level == ConsoleLevel.Error }
        ConsoleFilter.Warnings -> messages.filter { it.level == ConsoleLevel.Warn }
    }

/**
 * The messages as plain text for pasting somewhere: one line each, `LEVEL file:line message`. The file and line
 * are left out when the page did not say where the message came from, and line breaks inside a message become spaces.
 */
fun consoleCopyText(messages: List<CoderConsoleMessage>): String =
    messages.joinToString("\n") { message ->
        val where = when {
            message.sourceFile != null && message.line != null -> " ${message.sourceFile}:${message.line}"
            message.sourceFile != null -> " ${message.sourceFile}"
            else -> ""
        }
        val text = message.text.replace("\r", " ").replace("\n", " ")
        message.level.name.uppercase() + where + " " + text
    }

/**
 * The preview console as a bottom sheet. It only lists what the page wrote; nothing typed here is
 * ever run. Each line says its level in words as well as in color, and an error may have a plain-English
 * explanation under it. Three chips (All, Errors, Warnings, with counts) choose what is listed, and Copy puts the
 * listed messages on the clipboard as plain text and then calls [onCopied]. When [onAskAi] is set, every error
 * line has a Fix with AI button.
 */
@Composable
fun CoderConsolePanel(
    messages: List<CoderConsoleMessage>,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onAskAi: ((CoderConsoleMessage) -> Unit)? = null,
    onCopied: (() -> Unit)? = null,
) {
    val spacing = NeriboTheme.spacing
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var filter by rememberSaveable { mutableStateOf(ConsoleFilter.All) }
    val errorCount = messages.count { it.level == ConsoleLevel.Error }
    val warningCount = messages.count { it.level == ConsoleLevel.Warn }
    val visible = filterConsoleMessages(messages, filter)
    LaunchedEffect(visible.size, filter) {
        if (visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }
    NeriboBottomSheet(onDismiss = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Console",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            WhatIsThis(termId = "console")
        }
        Spacer(modifier = Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            NeriboChip(
                label = "All ${messages.size}",
                selected = filter == ConsoleFilter.All,
                onClick = { filter = ConsoleFilter.All },
            )
            NeriboChip(
                label = "Errors $errorCount",
                selected = filter == ConsoleFilter.Errors,
                onClick = { filter = ConsoleFilter.Errors },
            )
            NeriboChip(
                label = "Warnings $warningCount",
                selected = filter == ConsoleFilter.Warnings,
                onClick = { filter = ConsoleFilter.Warnings },
            )
        }
        if (visible.isEmpty()) {
            Text(
                text = when {
                    messages.isEmpty() -> "Nothing logged yet."
                    filter == ConsoleFilter.Errors -> "No errors."
                    else -> "No warnings."
                },
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
                items(visible, key = { it.id }) { message -> ConsoleLine(message, onAskAi) }
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            NeriboButton(
                text = "Clear",
                onClick = onClear,
                style = ButtonStyle.Secondary,
                enabled = messages.isNotEmpty(),
            )
            NeriboButton(
                text = "Copy",
                onClick = {
                    clipboard.setText(AnnotatedString(consoleCopyText(visible)))
                    onCopied?.invoke()
                },
                style = ButtonStyle.Text,
                enabled = visible.isNotEmpty(),
            )
            NeriboButton(text = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun ConsoleLine(message: CoderConsoleMessage, onAskAi: ((CoderConsoleMessage) -> Unit)?) {
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
        if (onAskAi != null && message.level == ConsoleLevel.Error) {
            NeriboButton(
                text = "Fix with AI",
                onClick = { onAskAi(message) },
                style = ButtonStyle.Text,
            )
        }
    }
}
