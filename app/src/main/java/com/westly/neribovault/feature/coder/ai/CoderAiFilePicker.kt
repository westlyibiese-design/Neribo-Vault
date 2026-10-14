package com.westly.neribovault.feature.coder.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A sheet for choosing up to 5 extra text files to send with a question. A file that is not plain
 * text or is over 300 KB is shown but cannot be chosen, with a short reason. Nothing is sent from here;
 * [onConfirm] only hands back the chosen paths.
 */
@Composable
fun CoderAiFilePicker(
    projectId: String,
    entries: List<CoderEntry>,
    selected: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as NeriboApp).container.coderFileStore }
    val fileEntries = remember(entries) { entries.filter { !it.isDirectory } }
    var chosen by remember { mutableStateOf(selected) }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Add files",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Choose up to $CODER_AI_MAX_EXTRA_FILES text files to send with your question.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs, bottom = spacing.sm),
        )
        if (fileEntries.isEmpty()) {
            Text(
                text = "This project has no files yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.lg),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(fileEntries, key = { it.path }) { entry ->
                    val isChosen = entry.path in chosen
                    PickerRow(
                        projectId = projectId,
                        entry = entry,
                        store = store,
                        checked = isChosen,
                        limitReached = !isChosen && chosen.size >= CODER_AI_MAX_EXTRA_FILES,
                        onToggle = {
                            chosen = if (isChosen) chosen - entry.path else chosen + entry.path
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            NeriboButton(text = "Cancel", onClick = onDismiss, style = ButtonStyle.Secondary)
            NeriboButton(text = "Done", onClick = { onConfirm(chosen) })
        }
    }
}

@Composable
private fun PickerRow(
    projectId: String,
    entry: CoderEntry,
    store: CoderFileStore,
    checked: Boolean,
    limitReached: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val tooBig = entry.sizeBytes > CoderLimits.MAX_EDITABLE_BYTES
    val readState by produceState<CoderReadResult?>(
        initialValue = null,
        key1 = projectId,
        key2 = entry.path,
        key3 = entry.lastModified,
    ) {
        value = if (tooBig) CoderReadResult.TooLarge else withContext(Dispatchers.IO) { store.read(projectId, entry.path) }
    }
    val problem: String? = when (readState) {
        CoderReadResult.TooLarge -> "Over 300 KB"
        CoderReadResult.Binary -> "Not plain text"
        CoderReadResult.Missing, CoderReadResult.Failed -> "Can't be read"
        is CoderReadResult.Text, null -> null
    }
    val enabled = problem == null && (checked || !limitReached)
    val detail = problem ?: if (limitReached) "Up to $CODER_AI_MAX_EXTRA_FILES files" else formatSize(entry.sizeBytes)
    val textColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** A file size in plain units: bytes below 1 KB, otherwise whole kilobytes. */
private fun formatSize(bytes: Long): String =
    if (bytes < 1024L) "$bytes B" else "${bytes / 1024L} KB"
