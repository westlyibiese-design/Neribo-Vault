package com.westly.neribovault.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.feature.backup.formatBytes

/** How much space Neribo Vault uses, with clean-up and "Empty all trash". */
@Composable
fun StorageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vm = neriboViewModel { c -> StorageViewModel(c, context.applicationContext as Application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Storage",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.height(spacing.md))
        UsageRow(label = "Database", value = formatBytes(state.databaseBytes))
        NeriboDivider()
        UsageRow(label = "Memory photos", value = formatBytes(state.photoBytes))
        NeriboDivider()
        UsageRow(label = "Document files", value = formatBytes(state.documentBytes))
        NeriboDivider()
        UsageRow(label = "Total", value = formatBytes(state.totalBytes), bold = true)

        val message = state.message
        if (message != null) {
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(spacing.lg))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            NeriboButton(
                text = "Clean up unused files",
                onClick = vm::startCleanup,
                enabled = !state.isBusy,
                style = ButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            NeriboButton(
                text = "Empty all trash",
                onClick = vm::askEmptyTrash,
                enabled = !state.isBusy,
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.Delete,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val cleanup = state.confirmCleanup
    if (cleanup != null) {
        val count = cleanup.files.size
        ConfirmDialog(
            title = "Clean up unused files?",
            message = "$count unused ${if (count == 1) "file" else "files"} will be deleted, " +
                "freeing ${formatBytes(cleanup.bytes)}. Files that your memories and documents " +
                "use are never touched.",
            confirmLabel = "Delete files",
            destructive = true,
            onConfirm = vm::confirmCleanup,
            onDismiss = vm::cancelCleanup,
        )
    }
    if (state.confirmEmptyTrash) {
        ConfirmDialog(
            title = "Empty all trash?",
            message = "Everything in Recently deleted, in every vault, will be removed permanently, " +
                "including the photos and files of deleted memories and documents. This cannot be undone.",
            confirmLabel = "Empty all trash",
            destructive = true,
            onConfirm = vm::confirmEmptyTrash,
            onDismiss = vm::cancelEmptyTrash,
        )
    }
}

@Composable
private fun UsageRow(label: String, value: String, bold: Boolean = false) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (bold) colors.onSurface else colors.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
    }
}
