package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet

/** Bottom sheet to turn a vault lock on or off. Phase 2 makes it real. */
@Composable
fun VaultLockSettingsSheet(vaultId: String, vaultName: String, onDismiss: () -> Unit) {
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Vault lock will be available soon.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = NeriboTheme.spacing.lg),
        )
    }
}

/** Whether the lock is on for [vaultId]. Always false until Phase 2. */
@Composable
fun rememberVaultLockEnabled(vaultId: String): State<Boolean> =
    remember(vaultId) { mutableStateOf(false) }
