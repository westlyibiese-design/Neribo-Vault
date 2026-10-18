package com.westly.neribovault.feature.coder.editor

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.feature.coder.editor.smart.CoderIssue
import com.westly.neribovault.feature.coder.editor.smart.CoderLinter
import com.westly.neribovault.feature.coder.editor.smart.IssueSeverity
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
private const val ISSUE_TINT_ALPHA = 0.2f
private const val MATCH_TINT_ALPHA = 0.22f
private const val CURRENT_MATCH_TINT_ALPHA = 0.5f

/**
 * The code editor: line numbers, colors, no wrapping (long lines scroll sideways) and a monospace
 * font. Colors are added by a transformation that never changes the text, so offsets stay the same.
 * [issues] are underlined as mistakes and [matches] (inclusive ranges, [currentMatch] the one in use) are tinted
 * as find results; both only change how the text looks. Each time [revealTick] goes up the editor scrolls to the
 * current match, even when the text box does not have the keyboard. The panel starts scrolled to
 * ([initialScrollX], [initialScrollY]) and reports every scroll through [onScrollChanged]; the caller keys the
 * panel by the open file so scroll and focus never leak from one file to another.
 */
@Composable
fun CoderEditorPanel(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    language: CoderLanguage,
    modifier: Modifier = Modifier,
    matches: List<IntRange> = emptyList(),
    currentMatch: Int = -1,
    issues: List<CoderIssue> = emptyList(),
    revealTick: Int = 0,
    initialScrollX: Int = 0,
    initialScrollY: Int = 0,
    onScrollChanged: (x: Int, y: Int) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme
    val syntax = rememberCoderSyntaxColors()
    val density = LocalDensity.current
    val vScroll = rememberScrollState(initialScrollY)
    val hScroll = rememberScrollState(initialScrollX)
    val errorTint = colors.error.copy(alpha = ISSUE_TINT_ALPHA)
    val warningTint = NeriboTheme.extraColors.warning.copy(alpha = ISSUE_TINT_ALPHA)
    val matchTint = colors.primary.copy(alpha = MATCH_TINT_ALPHA)
    val currentTint = colors.primary.copy(alpha = CURRENT_MATCH_TINT_ALPHA)

    val highlighted = remember(value.text, language, syntax) {
        CoderHighlighter.highlight(value.text, language, syntax)
    }
    val styled = remember(highlighted, matches, currentMatch, issues, errorTint, warningTint, matchTint, currentTint) {
        withEditorMarks(highlighted, matches, currentMatch, issues, errorTint, warningTint, matchTint, currentTint)
    }
    val transformation = remember(styled) {
        object : VisualTransformation {
            override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
                val shown = if (text.text == styled.text) styled else text
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

    // Tells the caller where the text is scrolled to, so a tab can come back to the same place.
    val latestOnScroll by rememberUpdatedState(onScrollChanged)
    LaunchedEffect(vScroll, hScroll) {
        snapshotFlow { Pair(hScroll.value, vScroll.value) }.collectLatest { (x, y) -> latestOnScroll(x, y) }
    }

    // Scrolls to the find match the person just stepped to. The text box may not have the keyboard then
    // (the find box has it), so the effect above would not move; this one does, and only when asked.
    val latestMatches by rememberUpdatedState(matches)
    val latestCurrent by rememberUpdatedState(currentMatch)
    LaunchedEffect(revealTick) {
        if (revealTick == 0) return@LaunchedEffect
        val range = latestMatches.getOrNull(latestCurrent) ?: return@LaunchedEffect
        withFrameNanos { }
        val layout = layoutResult ?: return@LaunchedEffect
        if (viewportHeight <= 0 || textViewportWidth <= 0) return@LaunchedEffect
        scrollToOffset(
            layout = layout,
            offset = range.first,
            viewportHeight = viewportHeight,
            viewportWidth = textViewportWidth,
            vScroll = vScroll,
            hScroll = hScroll,
            topPaddingPx = topPaddingPx,
            startPaddingPx = startPaddingPx,
            marginPx = marginPx,
        )
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

/** Brings [offset] into view with the same margins the cursor-follow effect uses. Does nothing when it is already visible. */
private suspend fun scrollToOffset(
    layout: TextLayoutResult,
    offset: Int,
    viewportHeight: Int,
    viewportWidth: Int,
    vScroll: ScrollState,
    hScroll: ScrollState,
    topPaddingPx: Float,
    startPaddingPx: Float,
    marginPx: Float,
) {
    val length = layout.layoutInput.text.length
    val rect = runCatching { layout.getCursorRect(offset.coerceIn(0, length)) }.getOrNull() ?: return
    val top = topPaddingPx + rect.top
    val bottom = topPaddingPx + rect.bottom
    val viewTop = vScroll.value.toFloat()
    val verticalTarget = when {
        bottom + marginPx > viewTop + viewportHeight -> bottom + marginPx - viewportHeight
        top - marginPx < viewTop -> top - marginPx
        else -> null
    }
    if (verticalTarget != null) {
        vScroll.animateScrollTo(verticalTarget.toInt().coerceAtLeast(0), tween(durationMillis = 120))
    }
    val left = startPaddingPx + rect.left
    val viewLeft = hScroll.value.toFloat()
    val horizontalTarget = when {
        left + marginPx > viewLeft + viewportWidth -> left + marginPx - viewportWidth
        left - marginPx < viewLeft -> left - marginPx
        else -> null
    }
    if (horizontalTarget != null) {
        hScroll.animateScrollTo(horizontalTarget.toInt().coerceAtLeast(0), tween(durationMillis = 120))
    }
}

/**
 * [base] with the mistake underlines and find tints added as styling only. The characters and their offsets are
 * untouched. Ranges that do not fit the text (they can be one edit old) are skipped, and long files get no marks.
 */
private fun withEditorMarks(
    base: AnnotatedString,
    matches: List<IntRange>,
    currentMatch: Int,
    issues: List<CoderIssue>,
    errorTint: Color,
    warningTint: Color,
    matchTint: Color,
    currentTint: Color,
): AnnotatedString {
    if ((matches.isEmpty() && issues.isEmpty()) || base.length > CoderLinter.MAX_CHARS) return base
    val length = base.length
    val builder = AnnotatedString.Builder(base)
    for (issue in issues) {
        if (issue.start < 0 || issue.start >= issue.end || issue.end > length) continue
        val tint = if (issue.severity == IssueSeverity.Error) errorTint else warningTint
        builder.addStyle(SpanStyle(background = tint, textDecoration = TextDecoration.Underline), issue.start, issue.end)
    }
    for (index in matches.indices) {
        val range = matches[index]
        val end = range.last + 1
        if (range.first < 0 || range.first >= end || end > length) continue
        builder.addStyle(SpanStyle(background = if (index == currentMatch) currentTint else matchTint), range.first, end)
    }
    return builder.toAnnotatedString()
}

private fun countLines(text: String): Int {
    var lines = 1
    for (ch in text) if (ch == '\n') lines++
    return lines
}
