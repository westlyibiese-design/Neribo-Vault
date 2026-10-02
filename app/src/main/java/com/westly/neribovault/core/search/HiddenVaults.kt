package com.westly.neribovault.core.search

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.lock.rememberVaultLockEnabled
import com.westly.neribovault.core.vault.VaultCatalog

/**
 * The ids of every vault that has its own lock turned on. Search and Recent leave these vaults
 * out completely, so a locked vault never leaks its contents. Updates live when a lock is turned
 * on or off.
 */
@Composable
fun rememberHiddenVaults(): Set<String> {
    val hidden = LinkedHashSet<String>()
    for (vault in VaultCatalog.all) {
        val locked = rememberVaultLockEnabled(vault.id).value
        if (locked) hidden.add(vault.id)
    }
    return hidden
}
