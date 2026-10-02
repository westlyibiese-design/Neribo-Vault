package com.westly.neribovault.feature.writers.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.ui.components.NeriboTextField

private const val MAX_CHAPTER_TITLE = 120

/** Asks for a new chapter title. An empty title is allowed and shows as "Untitled chapter". */
@Composable
fun RenameChapterDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim()) }) {
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
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
        title = { Text(text = "Rename chapter", style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = title,
                onValueChange = { new -> title = new.replace("\n", " ").take(MAX_CHAPTER_TITLE) },
                modifier = Modifier.focusRequester(focus),
                label = "Title",
                placeholder = "Chapter title",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
