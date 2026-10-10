package com.westly.neribovault.feature.authenticator.add

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.NeriboBottomSheet

/**
 * The "Add an account" sheet: scan a QR code, paste a link or key, or type a key by hand. It owns
 * the whole flow; after a successful save it calls [onImported] with the number saved and closes.
 */
@Composable
fun AddAccountSheet(
    onDismiss: () -> Unit,
    onManual: () -> Unit,
    onImported: (count: Int) -> Unit,
) {
    val context = LocalContext.current
    val vm = neriboViewModel(key = "authenticator-import") { c -> ImportViewModel(c.totpAccountsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showPaste by remember { mutableStateOf(false) }

    // Start clean, and drop every secret still in memory when the sheet goes away.
    DisposableEffect(vm) {
        vm.reset()
        onDispose { vm.reset() }
    }

    val finished = state.finished
    LaunchedEffect(finished) {
        if (finished != null) {
            if (finished.failed > 0) {
                val text = if (finished.failed == 1) {
                    "1 account could not be saved"
                } else {
                    "${finished.failed} accounts could not be saved"
                }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
            onImported(finished.saved)
            onDismiss()
        }
    }

    val preview = state.preview
    if (preview != null) {
        ImportPreviewSheet(
            preview = preview,
            checked = state.checked,
            isBusy = state.isBusy,
            message = state.message,
            onToggle = vm::toggle,
            onToggleAll = vm::toggleAll,
            onCancel = onDismiss,
            onAdd = vm::saveChecked,
        )
        return
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        val colors = MaterialTheme.colorScheme
        val spacing = NeriboTheme.spacing
        Text(
            text = "Add an account",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.md),
        )
        AddOptionRow(
            icon = Icons.Outlined.QrCodeScanner,
            title = "Scan a QR code",
            line = "Point the camera at the code the service shows you",
            onClick = {
                vm.clearMessage()
                startQrScan(
                    context = context,
                    onScanned = { raw -> vm.importScanned(raw) },
                    onCancelled = { },
                    onFailed = { error -> vm.showMessage(scanFailureMessage(error)) },
                )
            },
        )
        AddOptionRow(
            icon = Icons.Outlined.Link,
            title = "Paste a link or key",
            line = "A link that starts with otpauth, or the setup key",
            onClick = { showPaste = true },
        )
        AddOptionRow(
            icon = Icons.Outlined.Keyboard,
            title = "Enter a key manually",
            line = "Type the setup key yourself",
            onClick = {
                onDismiss()
                onManual()
            },
        )
        val message = state.message
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.sm)
                    .background(colors.errorContainer, MaterialTheme.shapes.medium)
                    .padding(spacing.md),
            )
        }
        Text(
            text = "Your secret keys are encrypted and never shown.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.md),
        )
    }

    if (showPaste) {
        PasteLinkDialog(
            needsNames = state.needsNames,
            error = state.pasteError,
            busy = state.isBusy,
            onTextEdited = vm::pasteEdited,
            onContinue = vm::submitPasted,
            onAddKey = vm::addBareKey,
            onDismiss = {
                showPaste = false
                vm.pasteEdited()
            },
        )
    }
}

@Composable
private fun AddOptionRow(
    icon: ImageVector,
    title: String,
    line: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
        )
        Column(modifier = Modifier.padding(start = spacing.lg)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
