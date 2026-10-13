package com.westly.neribovault.feature.coder.zip

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.westly.neribovault.core.ui.components.ConfirmDialog

/** What an import did: how many files were added, what was left out and whether an outer folder was removed. */
@Composable
fun ImportReportDialog(report: CoderImportReport, onOpen: () -> Unit, onClose: () -> Unit) {
    val lines = ArrayList<String>()
    lines.add(if (report.importedFiles == 1) "1 file added" else "${report.importedFiles} files added")
    lines.addAll(report.skippedReasons)
    val wrapper = report.strippedWrapperFolder
    if (wrapper != null) {
        lines.add("The outer folder '$wrapper' was removed so the project starts at the right place.")
    }
    ConfirmDialog(
        title = "Project imported",
        message = lines.joinToString("\n"),
        confirmLabel = "Open project",
        onConfirm = onOpen,
        onDismiss = onClose,
        dismissLabel = "Close",
    )
}

/** A short message with one OK button, used when an import cannot be done. */
@Composable
fun ZipMessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "OK", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/**
 * A dialog with a moving bar while a ZIP is unpacked or saved. With [onCancel] it shows a Cancel
 * button and Back cancels too; without it the dialog cannot be dismissed.
 */
@Composable
fun ZipProgressDialog(title: String, onCancel: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { onCancel?.invoke() },
        confirmButton = {},
        dismissButton = if (onCancel == null) {
            null
        } else {
            {
                TextButton(onClick = onCancel) {
                    Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
        },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
