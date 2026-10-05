package com.westly.neribovault.feature.screenplays.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboTextField

/**
 * The small form for a screenplay's title and author. With [isNew] it is the "New screenplay"
 * dialog (Create); otherwise it is "Details" (Save). [onConfirm] gets the typed values untrimmed.
 */
@Composable
fun ScreenplayDetailsDialog(
    isNew: Boolean,
    initialTitle: String,
    initialAuthor: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var author by rememberSaveable { mutableStateOf(initialAuthor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(title, author) }) {
                Text(
                    text = if (isNew) "Create" else "Save",
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
        title = {
            Text(
                text = if (isNew) "New screenplay" else "Details",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                NeriboTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = "Title",
                    placeholder = "The Last Danfo",
                )
                Spacer(modifier = Modifier.height(NeriboTheme.spacing.md))
                NeriboTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = "Author",
                    placeholder = "Your name",
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
