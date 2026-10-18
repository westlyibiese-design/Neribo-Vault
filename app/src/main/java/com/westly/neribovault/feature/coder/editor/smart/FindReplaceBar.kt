package com.westly.neribovault.feature.coder.editor.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton

/**
 * The find and replace bar under the top bar. The first row has the search box, previous, next and close; the
 * second the match count, the two switches (Aa for upper and lower case, ab for whole words) and Replace, which
 * opens a third row with the replace box. It only shows and reports taps; [FindState] lives in the ViewModel.
 * [onFieldFocusChange] tells whether one of the two boxes has the keyboard.
 */
@Composable
fun FindReplaceBar(
    state: FindState,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    onFieldFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var showReplace by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarField(
                    text = state.query,
                    onTextChange = onQueryChange,
                    placeholder = "Find",
                    description = "Find text",
                    imeAction = ImeAction.Search,
                    onImeAction = onNext,
                    onFocusChange = onFieldFocusChange,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
                NeriboIconButton(icon = Icons.Outlined.ArrowUpward, contentDescription = "Previous match", onClick = onPrevious)
                NeriboIconButton(icon = Icons.Outlined.ArrowDownward, contentDescription = "Next match", onClick = onNext)
                NeriboIconButton(icon = Icons.Outlined.Close, contentDescription = "Close find", onClick = onClose)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = countText(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = spacing.sm),
                )
                NeriboChip(
                    label = "Aa",
                    selected = state.caseSensitive,
                    onClick = onToggleCase,
                    modifier = Modifier.semantics { contentDescription = "Match upper and lower case" },
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                NeriboChip(
                    label = "ab",
                    selected = state.wholeWord,
                    onClick = onToggleWholeWord,
                    modifier = Modifier.semantics { contentDescription = "Whole words only" },
                )
                TextButton(
                    onClick = { showReplace = !showReplace },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = if (showReplace) "Hide replace" else "Show replace" },
                ) {
                    Text(text = "Replace", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarField(
                        text = state.replacement,
                        onTextChange = onReplacementChange,
                        placeholder = "Replace with",
                        description = "Replace with",
                        imeAction = ImeAction.Done,
                        onImeAction = { focusManager.clearFocus() },
                        onFocusChange = onFieldFocusChange,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onReplace,
                        enabled = state.current in state.matches.indices,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = "Replace", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onReplaceAll,
                        enabled = state.matches.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = "Replace all", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        NeriboDivider()
    }
}

/** "3 of 12", "No matches", or a hint while nothing is typed. Past the limit it says "5000+" so nobody thinks it is the full count. */
private fun countText(state: FindState): String {
    val count = state.matches.size
    val position = (state.current + 1).coerceAtLeast(1)
    return when {
        state.query.isEmpty() -> "Type what to find"
        count == 0 -> "No matches"
        count >= CoderFind.MAX_MATCHES -> "$position of ${CoderFind.MAX_MATCHES}+"
        else -> "$position of $count"
    }
}

/**
 * A small one-line text box. It keeps its own text and cursor and reports changes, so fast typing is never
 * overwritten by a slightly older value from the ViewModel.
 */
@Composable
private fun BarField(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    description: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var local by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (local.text != text) local = TextFieldValue(text, TextRange(text.length))
    }
    BasicTextField(
        value = local,
        onValueChange = { updated ->
            local = updated
            if (updated.text != text) onTextChange(updated.text)
        },
        modifier = modifier
            .semantics { contentDescription = description }
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onImeAction() },
            onDone = { onImeAction() },
        ),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (local.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}
