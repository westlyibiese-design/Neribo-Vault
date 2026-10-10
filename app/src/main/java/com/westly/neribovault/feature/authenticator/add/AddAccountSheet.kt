package com.westly.neribovault.feature.authenticator.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet

/**
 * The "Add an account" sheet. In this part it offers only the manual option; a later part replaces
 * this file with scanning and pasting too, keeping this exact signature.
 */
@Composable
fun AddAccountSheet(
    onDismiss: () -> Unit,
    onManual: () -> Unit,
    onImported: (count: Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Add an account",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.md),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                    onDismiss()
                    onManual()
                }
                .padding(vertical = spacing.md),
        ) {
            Text(
                text = "Enter a key manually",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Text(
                text = "Type the setup key the service shows you",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            text = "Scanning and pasting links arrive in the next update.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}
