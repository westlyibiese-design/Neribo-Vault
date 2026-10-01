package com.westly.neribovault.feature.memories.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboTextField

/**
 * A small wrapping group of removable chips with an "add" chip, used for both people and tags in
 * the memory editor. Tap a chip to remove it; the add chip opens a short dialog and disappears
 * once [maxItems] is reached.
 *
 * [normalize] turns typed text into the stored value (an empty result means "nothing usable").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryChipEditor(
    items: List<String>,
    addLabel: String,
    dialogTitle: String,
    placeholder: String,
    maxItems: Int,
    maxLength: Int,
    normalize: (String) -> String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.xs),
    ) {
        items.forEach { item ->
            RemovableChip(text = item, onRemove = { onRemove(item) })
        }
        if (items.size < maxItems) {
            NeriboChip(label = addLabel, selected = false, onClick = { dialogOpen = true })
        }
    }
    if (dialogOpen) {
        AddItemDialog(
            title = dialogTitle,
            placeholder = placeholder,
            maxLength = maxLength,
            existing = items,
            normalize = normalize,
            capitalization = capitalization,
            onAdd = { value ->
                onAdd(value)
                dialogOpen = false
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .clickable(onClickLabel = "Remove $text", role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddItemDialog(
    title: String,
    placeholder: String,
    maxLength: Int,
    existing: List<String>,
    normalize: (String) -> String,
    capitalization: KeyboardCapitalization,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val normalized = normalize(input)
    val isDuplicate = normalized.isNotEmpty() && existing.any { it.equals(normalized, ignoreCase = true) }
    val canAdd = normalized.isNotEmpty() && !isDuplicate

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = input,
                onValueChange = { input = it.take(maxLength) },
                modifier = Modifier.focusRequester(focusRequester),
                placeholder = placeholder,
                isError = isDuplicate,
                supportingText = if (isDuplicate) {
                    "Already added"
                } else {
                    "Up to $maxLength characters"
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = capitalization,
                    imeAction = ImeAction.Done,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (canAdd) onAdd(normalized) }, enabled = canAdd) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canAdd) colors.primary else colors.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
