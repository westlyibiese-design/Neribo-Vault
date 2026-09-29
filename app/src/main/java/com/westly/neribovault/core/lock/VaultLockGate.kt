package com.westly.neribovault.core.lock

import androidx.compose.runtime.Composable

/**
 * Optional extra PIN for one vault. Phase 2 will show an unlock screen when that vault is locked.
 * For now it simply renders [content].
 */
@Composable
fun VaultLockGate(
    vaultId: String,
    vaultName: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}
