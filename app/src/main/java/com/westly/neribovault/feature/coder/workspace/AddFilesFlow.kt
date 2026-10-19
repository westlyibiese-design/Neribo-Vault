package com.westly.neribovault.feature.coder.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.feature.coder.files.CoderConflictChoice
import com.westly.neribovault.feature.coder.files.CoderFileCopy
import com.westly.neribovault.feature.coder.files.CoderPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** One file chosen in the picker: its name on the phone, its size when the phone says, and a way to open it (null when it cannot be opened). */
class CoderPickedFile(val displayName: String, val sizeBytes: Long?, val open: () -> InputStream?)

/** How far adding files has come: [done] of [total] are finished and [name] is being copied into [folder] ("" is the top of the project). */
data class CoderImportProgress(val done: Int, val total: Int, val name: String, val folder: String)

/**
 * What adding files did. [notes] say why each file that was left out was left out; [firstImagePath] is the path of
 * the first picture that was added, or null when no picture was.
 */
data class CoderAddReport(
    val added: Int,
    val skipped: Int,
    val folder: String,
    val notes: List<String>,
    val firstImagePath: String?,
)

/** A tap on "Add files". The screen calls [ask]; [AddFilesFlow] opens the picker once and goes back to waiting for the next tap. */
class AddFilesRequest {
    private var waiting by mutableStateOf(false)

    /** True from [ask] until the picker has been opened. */
    internal val isWaiting: Boolean get() = waiting

    /** Asks for the picker to open. */
    fun ask() {
        waiting = true
    }

    internal fun take() {
        waiting = false
    }
}

/** The files [uris] with their names and sizes, as the phone tells them. Does quick queries, so call it off the main thread. */
internal fun readPickedFiles(resolver: ContentResolver, uris: List<Uri>): List<CoderPickedFile> =
    uris.map { uri ->
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        } catch (e: Exception) {
            // Some providers give no name or size; the file is still added under a fallback name.
        }
        val opener: () -> InputStream? = {
            try {
                resolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
        }
        CoderPickedFile(name ?: uri.lastPathSegment ?: CoderFileCopy.FALLBACK_NAME, size, opener)
    }

/** The picked files wait here while the person chooses what to do with the names that are already used. */
private class PendingAdd(val picked: List<CoderPickedFile>, val folder: String, val conflicts: List<String>)

/**
 * Everything that happens after "Add files": the picker, the one question about names that are already used
 * (Replace, Keep both or Skip, for all of them), the progress with Cancel and the report. [targetFolder] is the folder
 * the files go to ("" is the top of the project) and [existingPaths] are the paths the project has now.
 */
@Composable
fun AddFilesFlow(
    viewModel: CoderWorkspaceViewModel,
    request: AddFilesRequest,
    targetFolder: String,
    existingPaths: Set<String>,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val progress by viewModel.importProgress.collectAsStateWithLifecycle()
    val report by viewModel.importReport.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingAdd?>(null) }
    val folderNow by rememberUpdatedState(targetFolder)
    val pathsNow by rememberUpdatedState(existingPaths)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) { readPickedFiles(context.applicationContext.contentResolver, uris) }
                val folder = folderNow
                val onDisk = pathsNow.filter { CoderPaths.parentOf(it) == folder }.map { CoderPaths.nameOf(it) }.toSet()
                val conflicts = CoderFileCopy.conflictingNames(onDisk, picked.map { CoderFileCopy.sanitizeName(it.displayName) })
                if (conflicts.isEmpty()) {
                    viewModel.importFiles(picked, folder, CoderConflictChoice.KeepBoth)
                } else {
                    pending = PendingAdd(picked, folder, conflicts)
                }
            }
        }
    }
    LaunchedEffect(request.isWaiting) {
        if (request.isWaiting) {
            request.take()
            launcher.launch(arrayOf("*/*"))
        }
    }

    pending?.let { waiting ->
        ConflictDialog(
            folder = waiting.folder,
            conflicts = waiting.conflicts,
            onChoose = { choice ->
                pending = null
                viewModel.importFiles(waiting.picked, waiting.folder, choice)
            },
            onCancel = { pending = null },
        )
    }
    progress?.let { running ->
        ProgressDialog(progress = running, onCancel = { viewModel.cancelImport() })
    }
    report?.let { done ->
        ReportDialog(
            report = done,
            onCopy = { path ->
                clipboard.setText(AnnotatedString(path))
                onMessage("Path copied")
            },
            onClose = { viewModel.dismissImportReport() },
        )
    }
}

/** How a folder is named in the dialogs: its path, or "the top of the project" for the root. */
private fun folderLabel(folder: String): String = if (folder.isEmpty()) "the top of the project" else folder

@Composable
private fun ConflictDialog(folder: String, conflicts: List<String>, onChoose: (CoderConflictChoice) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shown = conflicts.take(3).joinToString(", ")
    val more = conflicts.size - 3
    val names = if (more > 0) "$shown and $more more" else shown
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        },
        title = { Text(text = "Some names are already used", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(text = "Add to: ${folderLabel(folder)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(spacing.sm))
                Text(
                    text = "Already there: $names. What should happen to these files? Your choice applies to all of them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboButton(
                    text = "Keep both",
                    onClick = { onChoose(CoderConflictChoice.KeepBoth) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                NeriboButton(
                    text = "Replace",
                    onClick = { onChoose(CoderConflictChoice.Replace) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                NeriboButton(
                    text = "Skip",
                    onClick = { onChoose(CoderConflictChoice.Skip) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Secondary,
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

@Composable
private fun ProgressDialog(progress: CoderImportProgress, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val position = (progress.done + 1).coerceAtMost(progress.total.coerceAtLeast(1))
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        },
        title = { Text(text = "Adding files\u2026", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(text = "Add to: ${folderLabel(progress.folder)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "File $position of ${progress.total}: ${progress.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = spacing.xs, bottom = spacing.md),
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primary,
                    trackColor = colors.surfaceVariant,
                )
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}

@Composable
private fun ReportDialog(report: CoderAddReport, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val imagePath = report.firstImagePath
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(text = "Close", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        },
        dismissButton = if (imagePath == null) {
            null
        } else {
            {
                TextButton(onClick = { onCopy(imagePath) }) {
                    Text(text = "Copy", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
        },
        title = { Text(text = CoderFileCopy.reportTitle(report.added, report.skipped), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(text = "Added to: ${folderLabel(report.folder)}", style = MaterialTheme.typography.bodyMedium)
                for (note in report.notes.take(MAX_NOTES)) {
                    Text(text = note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.xs))
                }
                val extra = report.notes.size - MAX_NOTES
                if (extra > 0) {
                    Text(text = "and $extra more", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = spacing.xs))
                }
                if (imagePath != null) {
                    Text(
                        text = "Use them in HTML like this: $imagePath",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

private const val MAX_NOTES = 5
