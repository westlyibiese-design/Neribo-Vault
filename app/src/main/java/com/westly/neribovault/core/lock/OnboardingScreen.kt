package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.fragment.app.FragmentActivity
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton

private const val STEP_WELCOME = 0
private const val STEP_PIN = 1
private const val STEP_BIOMETRIC = 2

/** First-run flow: welcome, create a PIN, then optionally turn on biometric unlock. */
@Composable
internal fun OnboardingScreen(manager: AppLockManager) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val biometricAvailable = remember { BiometricAuth.isAvailable(context) }
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var step by rememberSaveable { mutableStateOf(STEP_WELCOME) }
    var biometricNotice by remember { mutableStateOf<String?>(null) }

    LockSurface {
        when (step) {
            STEP_WELCOME -> {
                LockHeader(icon = Icons.Outlined.Lock, title = "Your private space")
                Text(
                    text = "Everything you write here stays on this phone. Nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(spacing.xxl))
                NeriboButton(
                    text = "Get started",
                    onClick = { step = STEP_PIN },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            STEP_PIN -> {
                LockHeader(icon = Icons.Outlined.Lock, title = "Neribo Vault")
                PinSetupFlow(
                    onSave = { pin -> manager.savePin(pin) },
                    onFinished = {
                        if (biometricAvailable && activity != null) {
                            step = STEP_BIOMETRIC
                        } else {
                            manager.completeOnboarding()
                        }
                    },
                    newTitle = "Create your PIN",
                )
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LockHeader(icon = Icons.Outlined.Fingerprint, title = "Unlock faster?")
                    Text(
                        text = "Use your fingerprint or face to open Neribo Vault. Your PIN always works too.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    val notice = biometricNotice
                    if (notice != null) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.xxl))
                    NeriboButton(
                        text = "Enable",
                        onClick = {
                            if (activity != null) {
                                BiometricAuth.authenticate(
                                    activity = activity,
                                    title = "Turn on biometric unlock",
                                    subtitle = null,
                                    onSuccess = {
                                        manager.setBiometricsEnabled(true)
                                        manager.completeOnboarding()
                                    },
                                    onCancel = {
                                        biometricNotice = "Biometric unlock wasn't turned on. You can try again or choose Not now."
                                    },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    NeriboButton(
                        text = "Not now",
                        onClick = { manager.completeOnboarding() },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Text,
                    )
                }
            }
        }
    }
}
