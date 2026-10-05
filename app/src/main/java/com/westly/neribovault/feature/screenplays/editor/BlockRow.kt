package com.westly.neribovault.feature.screenplays.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.feature.screenplays.engine.BlockType

/**
 * One editable paragraph of the script, drawn in screenplay format.
 *
 * The text field's value always starts with an invisible sentinel character so that a Backspace
 * at the very start of the block can be told apart from an ordinary edit.
 */
@Composable
fun BlockRow(
    block: EditorBlock,
    previousType: BlockType?,
    contentWidth: Dp,
    isFocused: Boolean,
    focusTarget: FocusTarget?,
    flashToken: Long,
    syncRevision: Long,
    onEdit: (FieldEdit) -> Unit,
    onFocused: () -> Unit,
    onPageBreakTap: () -> Unit,
    onCursor: (Int) -> Unit,
    consumeCursor: (FocusTarget) -> Int,
) {
    val spacing = NeriboTheme.spacing
    val primary = MaterialTheme.colorScheme.primary
    val barOffsetPx = with(LocalDensity.current) { spacing.sm.toPx() }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashToken) {
        if (flashToken != 0L) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(durationMillis = 600))
        }
    }

    val followsCue = (block.type == BlockType.PARENTHETICAL || block.type == BlockType.DIALOGUE) &&
        (
            previousType == BlockType.CHARACTER ||
                previousType == BlockType.PARENTHETICAL ||
                previousType == BlockType.DIALOGUE
            )
    val topSpace = when {
        block.type == BlockType.SCENE_HEADING -> 24.dp
        followsCue -> 0.dp
        else -> 12.dp
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(topSpace))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val level = flash.value
                    if (isFocused || level > 0f) {
                        val width = 2.dp.toPx() * (1f + level)
                        drawRect(
                            color = primary,
                            topLeft = Offset(-barOffsetPx, 0f),
                            size = Size(width, size.height),
                            alpha = if (isFocused) 1f else level,
                        )
                    }
                    if (level > 0f) {
                        drawRect(color = primary, alpha = 0.10f * level)
                    }
                },
        ) {
            if (block.type == BlockType.PAGE_BREAK) {
                PageBreakContent(onTap = onPageBreakTap)
            } else {
                BlockTextField(
                    block = block,
                    contentWidth = contentWidth,
                    focusTarget = focusTarget,
                    syncRevision = syncRevision,
                    onEdit = onEdit,
                    onFocused = onFocused,
                    onCursor = onCursor,
                    consumeCursor = consumeCursor,
                )
            }
        }
    }
}

@Composable
private fun BlockTextField(
    block: EditorBlock,
    contentWidth: Dp,
    focusTarget: FocusTarget?,
    syncRevision: Long,
    onEdit: (FieldEdit) -> Unit,
    onFocused: () -> Unit,
    onCursor: (Int) -> Unit,
    consumeCursor: (FocusTarget) -> Int,
) {
    var field by remember(block.id) {
        mutableStateOf(TextFieldValue(EditorRules.SENTINEL_STRING + block.text))
    }
    val focusRequester = remember { FocusRequester() }

    // The ViewModel changed the text (split, undo, suggestion, ...): show it.
    LaunchedEffect(syncRevision) {
        val current = field.text.removePrefix(EditorRules.SENTINEL_STRING)
        if (current != block.text) {
            val shown = EditorRules.SENTINEL_STRING + block.text
            val position = field.selection.start.coerceIn(1, shown.length)
            field = TextFieldValue(shown, TextRange(position))
        }
    }

    // A focus request for this block: place the cursor and take focus.
    LaunchedEffect(focusTarget?.token) {
        val target = focusTarget
        if (target != null && target.textFocus) {
            val cursor = consumeCursor(target)
            val position = (cursor + 1).coerceIn(1, field.text.length)
            field = TextFieldValue(field.text, TextRange(position))
            withFrameNanos { }
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // The field is not attached yet; the next focus request will succeed.
            }
        }
    }

    val startFraction = when (block.type) {
        BlockType.CHARACTER -> 0.367f
        BlockType.PARENTHETICAL -> 0.267f
        BlockType.DIALOGUE -> 0.167f
        else -> 0f
    }
    val endFraction = when (block.type) {
        BlockType.PARENTHETICAL -> 0.333f
        BlockType.DIALOGUE -> 0.25f
        else -> 0f
    }
    val rightAligned = block.type == BlockType.TRANSITION &&
        !block.text.trim().equals("FADE IN:", ignoreCase = true)
    val textColor = MaterialTheme.colorScheme.onBackground
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = if (block.type == BlockType.SCENE_HEADING) FontWeight.Medium else FontWeight.Normal,
        color = textColor,
        textAlign = if (rightAligned) TextAlign.End else TextAlign.Start,
    )
    val hintStyle = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val capitalization = when (block.type) {
        BlockType.SCENE_HEADING, BlockType.CHARACTER, BlockType.TRANSITION -> KeyboardCapitalization.Characters
        BlockType.ACTION, BlockType.DIALOGUE -> KeyboardCapitalization.Sentences
        BlockType.PARENTHETICAL, BlockType.PAGE_BREAK -> KeyboardCapitalization.None
    }

    BasicTextField(
        value = field,
        onValueChange = { incoming ->
            val edit = EditorRules.classifyEdit(field.text, incoming.text, incoming.selection.start)
            if (edit is FieldEdit.Typed) {
                val shown = EditorRules.SENTINEL_STRING + EditorRules.transformText(block.type, edit.text)
                val shift = if (incoming.text.startsWith(EditorRules.SENTINEL)) 0 else 1
                val start = (incoming.selection.start + shift).coerceIn(1, shown.length)
                val end = (incoming.selection.end + shift).coerceIn(1, shown.length)
                val keepComposition = shift == 0 && shown.length == incoming.text.length
                field = TextFieldValue(
                    text = shown,
                    selection = TextRange(start, end),
                    composition = if (keepComposition) incoming.composition else null,
                )
                onCursor(start - 1)
            }
            onEdit(edit)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = contentWidth * startFraction,
                end = contentWidth * endFraction,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = style,
        keyboardOptions = KeyboardOptions(capitalization = capitalization),
        visualTransformation = if (block.type == BlockType.PARENTHETICAL) {
            ParentheticalTransformation
        } else {
            VisualTransformation.None
        },
        cursorBrush = SolidColor(textColor),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp)) {
                if (field.text.length <= 1) {
                    Text(
                        text = placeholderFor(block.type),
                        style = hintStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            }
        },
    )
}

/** Draws "(" and ")" around parenthetical text without making them editable. */
private object ParentheticalTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= 1) return TransformedText(text, OffsetMapping.Identity)
        val shown = raw.substring(0, 1) + "(" + raw.substring(1) + ")"
        val length = raw.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = if (offset <= 0) 0 else offset + 1

            override fun transformedToOriginal(offset: Int): Int =
                if (offset <= 1) offset else (offset - 1).coerceAtMost(length)
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

private fun placeholderFor(type: BlockType): String = when (type) {
    BlockType.SCENE_HEADING -> "INT. LOCATION - DAY"
    BlockType.ACTION -> "Describe what we see and hear."
    BlockType.CHARACTER -> "CHARACTER"
    BlockType.PARENTHETICAL -> "(beat)"
    BlockType.DIALOGUE -> "What do they say?"
    BlockType.TRANSITION -> "CUT TO:"
    BlockType.PAGE_BREAK -> ""
}

@Composable
private fun PageBreakContent(onTap: () -> Unit) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboDivider(modifier = Modifier.weight(1f))
        Text(
            text = "PAGE BREAK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.sm),
        )
        NeriboDivider(modifier = Modifier.weight(1f))
    }
}
