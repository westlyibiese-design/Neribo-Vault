package com.westly.neribovault.feature.lyrics.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.OverflowMenu

/** The hint shown in an empty section. */
private const val SECTION_HINT = "Write a line, then another. Press Enter twice to start a new section."

/**
 * One section of the song: a header row (the type chip showing the printed label, then the
 * three-dot menu) and the lyric lines as a borderless serif field. The focused section shows a
 * 2dp accent bar at its left edge.
 *
 * The text field's value always starts with an invisible sentinel character so that a Backspace
 * at the very start of the section can be told apart from an ordinary edit.
 */
@Composable
fun SectionBlock(
    section: EditorSection,
    label: String,
    isFocused: Boolean,
    focusTarget: FocusTarget?,
    flashToken: Long,
    syncRevision: Long,
    menuActions: List<MenuAction>,
    onTypeClick: () -> Unit,
    onEdit: (FieldEdit) -> Unit,
    onFocused: () -> Unit,
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
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboChip(
                    label = EditorRules.chipText(label),
                    selected = false,
                    onClick = onTypeClick,
                )
                Spacer(modifier = Modifier.weight(1f))
                OverflowMenu(actions = menuActions)
            }
            SectionTextField(
                section = section,
                syncRevision = syncRevision,
                focusTarget = focusTarget,
                onEdit = onEdit,
                onFocused = onFocused,
                onCursor = onCursor,
                consumeCursor = consumeCursor,
            )
        }
    }
}

@Composable
private fun SectionTextField(
    section: EditorSection,
    syncRevision: Long,
    focusTarget: FocusTarget?,
    onEdit: (FieldEdit) -> Unit,
    onFocused: () -> Unit,
    onCursor: (Int) -> Unit,
    consumeCursor: (FocusTarget) -> Int,
) {
    var field by remember(section.id) {
        mutableStateOf(TextFieldValue(EditorRules.SENTINEL_STRING + section.text))
    }
    val focusRequester = remember { FocusRequester() }

    // The ViewModel changed the text (split, undo, new chorus, ...): show it.
    LaunchedEffect(syncRevision) {
        val current = field.text.removePrefix(EditorRules.SENTINEL_STRING)
        if (current != section.text) {
            val shown = EditorRules.SENTINEL_STRING + section.text
            val position = field.selection.start.coerceIn(1, shown.length)
            field = TextFieldValue(shown, TextRange(position))
        }
    }

    // A focus request for this section: place the cursor and take focus.
    LaunchedEffect(focusTarget?.token) {
        val target = focusTarget
        if (target != null) {
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

    val textColor = MaterialTheme.colorScheme.onBackground
    val style = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        color = textColor,
    )
    val hintStyle = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    BasicTextField(
        value = field,
        onValueChange = { incoming ->
            val edit = EditorRules.classifyEdit(field.text, incoming.text)
            if (edit is FieldEdit.Typed) {
                val shown = EditorRules.SENTINEL_STRING + edit.text
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
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = style,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        cursorBrush = SolidColor(textColor),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
                if (field.text.length <= 1) {
                    Text(
                        text = SECTION_HINT,
                        style = hintStyle,
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    )
                }
                inner()
            }
        },
    )
}
