package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton

private enum class VaultLockMode { Home, Setup, Change, TurnOff }

/** Bottom sheet to turn a vault lock on, change its PIN, or turn it off. */
@Composable
fun VaultLockSettingsSheet(vaultId: String, vaultName: String, onDismiss: () -> Unit) {
    val manager = rememberLockManager()
    val enabledIds by manager.vaultEnabledIds.collectAsStateWithLifecycle()
    val enabled = vaultId in enabledIds
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(VaultLockMode.Home) }
    var notice by remember { mutableStateOf<String?>(null) }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            when (mode) {
                VaultLockMode.Home -> {
                    Text(
                        text = "$vaultName lock",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = if (enabled) {
                            "$vaultName has its own PIN, on top of your app PIN."
                        } else {
                            "Add a separate PIN that is asked for every time you open $vaultName."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    val message = notice
                    if (message != null) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primary,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.xl))
                    if (enabled) {
                        NeriboButton(
                            text = "Change PIN",
                            onClick = { notice = null; mode = VaultLockMode.Change },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                        NeriboButton(
                            text = "Turn off lock",
                            onClick = { notice = null; mode = VaultLockMode.TurnOff },
                            modifier = Modifier.fillMaxWidth(),
                            style = ButtonStyle.Secondary,
                        )
                    } else {
                        NeriboButton(
                            text = "Set a PIN for this vault",
                            onClick = { notice = null; mode = VaultLockMode.Setup },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.sm))
                    NeriboButton(
                        text = "Done",
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Text,
                    )
                }
                VaultLockMode.Setup -> {
                    PinSetupFlow(
                        onSave = { pin -> manager.setVaultPin(vaultId, pin) },
                        onFinished = {
                            notice = "$vaultName lock is on."
                            mode = VaultLockMode.Home
                        },
                        newTitle = "Choose a PIN for $vaultName",
                    )
                    BackToSettings(onClick = { mode = VaultLockMode.Home })
                }
                VaultLockMode.Change -> {
                    PinSetupFlow(
                        onSave = { pin -> manager.setVaultPin(vaultId, pin) },
                        onFinished = {
                            notice = "PIN changed."
                            mode = VaultLockMode.Home
                        },
                        requireCurrent = true,
                        verifyCurrent = { pin -> manager.verifyVaultPin(vaultId, pin) },
                        newTitle = "Choose a new PIN",
                    )
                    BackToSettings(onClick = { mode = VaultLockMode.Home })
                }
                VaultLockMode.TurnOff -> {
                    PinVerifyStep(
                        title = "Enter the PIN to turn off the lock",
                        verify = { pin -> manager.verifyVaultPin(vaultId, pin) },
                        onVerified = {
                            manager.disableVaultLock(vaultId)
                            notice = "$vaultName lock is off."
                            mode = VaultLockMode.Home
                        },
                    )
                    BackToSettings(onClick = { mode = VaultLockMode.Home })
                }
            }
        }
    }
}

@Composable
private fun BackToSettings(onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(NeriboTheme.spacing.md))
    NeriboButton(
        text = "Cancel",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        style = ButtonStyle.Text,
    )
}

/** Whether the lock is on for [vaultId]. Updates live. */
@Composable
fun rememberVaultLockEnabled(vaultId: String): State<Boolean> {
    val manager = rememberLockManager()
    val enabledIds = manager.vaultEnabledIds.collectAsStateWithLifecycle()
    return remember(vaultId) { derivedStateOf { vaultId in enabledIds.value } }
}
