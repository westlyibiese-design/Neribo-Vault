package com.westly.neribovault.feature.authenticator.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboTextField

/**
 * Asks for a pasted link or setup key. The typed text lives only in this dialog and disappears
 * when it closes. [needsNames] turns on the Service and Account fields for a bare setup key.
 */
@Composable
fun PasteLinkDialog(
    needsNames: Boolean,
    error: String?,
    busy: Boolean,
    onTextEdited: () -> Unit,
    onContinue: (text: String) -> Unit,
    onAddKey: (text: String, service: String, account: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var service by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }

    val canContinue = if (needsNames) {
        !busy && (service.isNotBlank() || account.isNotBlank())
    } else {
        !busy && text.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = "Paste a link or key",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                NeriboTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onTextEdited()
                    },
                    label = "Link or setup key",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    isError = error != null,
                    supportingText = error,
                )
                NeriboButton(
                    text = "Paste from clipboard",
                    onClick = {
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrEmpty()) {
                            text = pasted
                            onTextEdited()
                        }
                    },
                    style = ButtonStyle.Secondary,
                    leadingIcon = Icons.Outlined.ContentPaste,
                )
                if (needsNames) {
                    Text(
                        text = "That looks like a setup key. Tell us which account it belongs to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    NeriboTextField(
                        value = service,
                        onValueChange = { service = it },
                        label = "Service",
                        placeholder = "For example GitHub",
                    )
                    NeriboTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = "Account",
                        placeholder = "For example adaeze@example.com",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canContinue,
                onClick = {
                    if (needsNames) onAddKey(text, service, account) else onContinue(text)
                },
            ) {
                Text(
                    text = if (needsNames) "Add account" else "Continue",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canContinue) colors.primary else colors.onSurfaceVariant,
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
