package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboTopBar
import kotlinx.coroutines.delay

/**
 * Optional extra PIN for one vault (for example the Diary). If the vault has no lock, or it is
 * already unlocked this session, [content] is shown. Otherwise a calm unlock screen appears.
 * A vault re-locks whenever the app locks or goes to the background.
 */
@Composable
fun VaultLockGate(
    vaultId: String,
    vaultName: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val manager = rememberLockManager()
    val enabledIds by manager.vaultEnabledIds.collectAsStateWithLifecycle()
    val unlockedIds by manager.unlockedVaults.collectAsStateWithLifecycle()
    val lockEpoch = LocalAppLockEpoch.current
    if (vaultId !in enabledIds || vaultId in unlockedIds) {
        content()
    } else {
        // A new epoch gives the unlock screen fresh saved state, so nothing about a previous
        // unlock (such as "already asked for biometrics") is restored after the app locks.
        key(lockEpoch) {
            VaultUnlockScreen(
                manager = manager,
                vaultId = vaultId,
                vaultName = vaultName,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun VaultUnlockScreen(
    manager: AppLockManager,
    vaultId: String,
    vaultName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricsOn by manager.biometricsEnabled.collectAsStateWithLifecycle()
    val input = rememberPinInput()
    var shake by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    val canUseBiometrics = biometricsOn && activity != null && biometricAvailable

    val launchBiometric: () -> Unit = {
        if (activity != null) {
            BiometricAuth.authenticate(
                activity = activity,
                title = "Unlock $vaultName",
                subtitle = null,
                onSuccess = { manager.unlockVault(vaultId) },
                onCancel = {},
            )
        }
    }

    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canUseBiometrics) {
        if (canUseBiometrics && !autoPrompted) {
            autoPrompted = true
            delay(300)
            launchBiometric()
        }
    }

    LaunchedEffect(input.value) {
        if (input.value.isNotEmpty()) errorText = null
        if (input.isComplete) {
            val result = manager.verifyVaultPin(vaultId, input.value)
            if (result is PinResult.Success) {
                manager.unlockVault(vaultId)
            } else {
                errorText = pinErrorText(result)
                shake += 1
                input.clear()
            }
        }
    }

    LockSurface(
        topBar = { NeriboTopBar(title = vaultName, onBack = onBack) },
    ) {
        LockHeader(icon = Icons.Outlined.Lock, title = "$vaultName is locked")
        PinEntryPanel(
            input = input,
            message = errorText ?: "Enter the PIN for this vault",
            isError = errorText != null,
            shakeTrigger = shake,
            showBiometric = canUseBiometrics,
            onBiometric = launchBiometric,
        )
        Spacer(modifier = Modifier.height(NeriboTheme.spacing.lg))
    }
}
