package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderPaths

/** The message shown under a name that can't be used. */
const val BAD_NAME_MESSAGE = "That name can't be used. Avoid slashes and special characters."

/** The message shown under a name that is already taken. */
const val NAME_TAKEN_MESSAGE = "A file or folder with that name already exists."

/**
 * A dialog with one text field for a name. [validate] returns a message for a name that can't be
 * used, or null when it is fine; the confirm button stays off while the field is blank or invalid.
 * [maxLength] stops typing at that many characters.
 */
@Composable
fun NameEntryDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    validate: (String) -> String? = { null },
) {
    val colors = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    val blank = name.isBlank()
    val error = if (blank) null else validate(name)
    val canConfirm = enabled && !blank && error == null
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = canConfirm) {
                Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
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
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = name,
                onValueChange = { if (it.length <= maxLength) name = it },
                modifier = modifier.focusRequester(focus),
                label = label,
                isError = error != null,
                supportingText = error,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false),
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/** A name check for a new or renamed file or folder inside [parent]; [existing] are the paths already in use. */
fun checkEntryName(name: String, parent: String, existing: Set<String>, ownPath: String? = null): String? {
    if (!CoderPaths.isValidSegment(name)) return BAD_NAME_MESSAGE
    val path = CoderPaths.join(parent, name)
    if (path != ownPath && path in existing) return NAME_TAKEN_MESSAGE
    return null
}

/** Asks before deleting [entry]. Folders warn that everything inside goes too. */
@Composable
fun DeleteEntryDialog(entry: CoderEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = CoderPaths.nameOf(entry.path)
    ConfirmDialog(
        title = if (entry.isDirectory) "Delete folder?" else "Delete file?",
        message = if (entry.isDirectory) {
            "Delete $name and everything inside it? This can't be undone."
        } else {
            "Delete $name? This can't be undone."
        },
        confirmLabel = "Delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

/** A small sheet with the two things you can add: a file or a folder. */
@Composable
fun NewItemSheet(onNewFile: () -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        NeriboButton(text = "New file", onClick = onNewFile, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboButton(
            text = "New folder",
            onClick = onNewFolder,
            modifier = Modifier.fillMaxWidth(),
            style = ButtonStyle.Secondary,
        )
    }
}
