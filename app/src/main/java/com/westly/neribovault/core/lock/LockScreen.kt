package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import kotlinx.coroutines.delay

/** The screen shown whenever the app is locked. */
@Composable
internal fun LockScreen(manager: AppLockManager, onForgotPin: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricsOn by manager.biometricsEnabled.collectAsStateWithLifecycle()
    val lockoutUntil by manager.lockoutUntil.collectAsStateWithLifecycle()
    val input = rememberPinInput()
    var shake by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    val canUseBiometrics = biometricsOn && activity != null && biometricAvailable

    // Countdown while the lockout is active.
    LaunchedEffect(lockoutUntil) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= lockoutUntil) break
            delay(250)
        }
    }
    val secondsLeft = if (lockoutUntil > nowMs) ((lockoutUntil - nowMs + 999) / 1000).toInt() else 0
    val lockedOut = secondsLeft > 0

    val launchBiometric: () -> Unit = {
        if (activity != null) {
            BiometricAuth.authenticate(
                activity = activity,
                title = "Unlock Neribo Vault",
                subtitle = null,
                onSuccess = { manager.markUnlocked() },
                onCancel = {},
            )
        }
    }

    // Show the system prompt once when the lock screen opens (not again after a rotation).
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
            val result = manager.checkPin(input.value)
            if (result is PinResult.Success) {
                manager.markUnlocked()
            } else {
                errorText = if (result is PinResult.LockedOut) null else pinErrorText(result)
                shake += 1
                input.clear()
            }
        }
    }

    val message = when {
        lockedOut -> "Try again in $secondsLeft ${if (secondsLeft == 1) "second" else "seconds"}"
        errorText != null -> errorText
        else -> "Enter your PIN"
    }

    LockSurface {
        LockHeader(icon = Icons.Outlined.Lock, title = "Neribo Vault")
        PinEntryPanel(
            input = input,
            message = message,
            isError = lockedOut || errorText != null,
            shakeTrigger = shake,
            enabled = !lockedOut,
            showBiometric = canUseBiometrics,
            onBiometric = launchBiometric,
        )
        Spacer(modifier = Modifier.height(NeriboTheme.spacing.lg))
        NeriboButton(text = "Forgot PIN?", onClick = onForgotPin, style = ButtonStyle.Text)
    }
}
