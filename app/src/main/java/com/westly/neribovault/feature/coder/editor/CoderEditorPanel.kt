package com.westly.neribovault.feature.coder.editor

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.feature.coder.ui.rememberCoderSyntaxColors
import kotlinx.coroutines.flow.collectLatest

private val EDITOR_FONT_SIZE = 14.sp
private val EDITOR_LINE_HEIGHT = 20.sp
private val GUTTER_PADDING = 8.dp
private val TEXT_START_PADDING = 8.dp
private val TEXT_TOP_PADDING = 8.dp
private val TEXT_END_PADDING = 48.dp
private val TEXT_BOTTOM_PADDING = 96.dp
private val FOLLOW_MARGIN = 28.dp

/**
 * The code editor: line numbers, colors, no wrapping (long lines scroll sideways) and a monospace
 * font. Colors are added by a transformation that never changes the text, so offsets stay the same.
 */
@Composable
fun CoderEditorPanel(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: CoderLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val syntax = rememberCoderSyntaxColors()
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val highlighted = remember(value.text, language, syntax) {
        CoderHighlighter.highlight(value.text, language, syntax)
    }
    val transformation = remember(highlighted) {
        object : VisualTransformation {
            override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
                val shown = if (text.text == highlighted.text) highlighted else text
                return TransformedText(shown, OffsetMapping.Identity)
            }
        }
    }
    val codeStyle = remember(syntax) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = EDITOR_FONT_SIZE,
            lineHeight = EDITOR_LINE_HEIGHT,
            color = syntax.plain,
        )
    }
    val gutterStyle = remember(colors.onSurfaceVariant) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = EDITOR_FONT_SIZE,
            lineHeight = EDITOR_LINE_HEIGHT,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
    val lineCount = remember(value.text) { countLines(value.text) }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }

    var viewportHeight by remember { mutableIntStateOf(0) }
    var textViewportWidth by remember { mutableIntStateOf(0) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)

    val topPaddingPx = with(density) { TEXT_TOP_PADDING.toPx() }
    val startPaddingPx = with(density) { TEXT_START_PADDING.toPx() }
    val marginPx = with(density) { FOLLOW_MARGIN.toPx() }

    // Keep the cursor on screen while typing. Does nothing while the cursor is already visible,
    // and nothing when the field is not focused, so browsing the file is never interrupted.
    LaunchedEffect(vScroll, hScroll) {
        snapshotFlow {
            Triple(layoutResult, latestValue.selection.end, Triple(viewportHeight, textViewportWidth, hasFocus))
        }.collectLatest { (layout, cursor, sizes) ->
            val (height, width, focused) = sizes
            if (!focused || layout == null || height <= 0 || width <= 0) return@collectLatest
            withFrameNanos { }
            val length = layout.layoutInput.text.length
            val rect = runCatching { layout.getCursorRect(cursor.coerceIn(0, length)) }.getOrNull()
                ?: return@collectLatest
            val top = topPaddingPx + rect.top
            val bottom = topPaddingPx + rect.bottom
            val viewTop = vScroll.value.toFloat()
            val verticalTarget = when {
                bottom + marginPx > viewTop + height -> bottom + marginPx - height
                top - marginPx < viewTop -> top - marginPx
                else -> null
            }
            if (verticalTarget != null) {
                vScroll.animateScrollTo(verticalTarget.toInt().coerceAtLeast(0), tween(durationMillis = 120))
            }
            val left = startPaddingPx + rect.left
            val viewLeft = hScroll.value.toFloat()
            val horizontalTarget = when {
                left + marginPx > viewLeft + width -> left + marginPx - width
                left - marginPx < viewLeft -> left - marginPx
                else -> null
            }
            if (horizontalTarget != null) {
                hScroll.animateScrollTo(horizontalTarget.toInt().coerceAtLeast(0), tween(durationMillis = 120))
            }
        }
    }

    val viewportHeightDp = with(density) { viewportHeight.toDp() }
    val textViewportWidthDp = with(density) { textViewportWidth.toDp() }

    Box(modifier = modifier.onSizeChanged { viewportHeight = it.height }) {
        Row(modifier = Modifier.verticalScroll(vScroll).heightIn(min = viewportHeightDp)) {
            Text(
                text = lineNumbers,
                style = gutterStyle,
                softWrap = false,
                modifier = Modifier
                    .padding(start = GUTTER_PADDING, end = GUTTER_PADDING, top = TEXT_TOP_PADDING)
                    .clearAndSetSemantics { },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { textViewportWidth = it.width }
                    .horizontalScroll(hScroll),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .padding(
                            start = TEXT_START_PADDING,
                            end = TEXT_END_PADDING,
                            top = TEXT_TOP_PADDING,
                            bottom = TEXT_BOTTOM_PADDING,
                        )
                        .widthIn(min = textViewportWidthDp)
                        .heightIn(min = viewportHeightDp)
                        .onFocusChanged { hasFocus = it.isFocused },
                    textStyle = codeStyle,
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Text,
                    ),
                    visualTransformation = transformation,
                    onTextLayout = { layoutResult = it },
                )
            }
        }
    }
}

private fun countLines(text: String): Int {
    var lines = 1
    for (ch in text) if (ch == '\n') lines++
    return lines
}
