package com.westly.neribovault.feature.developer.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.lock.PinEntryPanel
import com.westly.neribovault.core.lock.rememberPinInput
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WRONG_PIN_TEXT = "That PIN isn't right."
private const val MISMATCH_TEXT = "Those didn't match. Let's start again."

/** Asks for six digits and calls [onComplete] once with them. Give it a fresh `key` per round. */
@Composable
private fun PinCapture(
    title: String,
    message: String?,
    isError: Boolean,
    onComplete: (String) -> Unit,
) {
    val input = rememberPinInput()
    val latestComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(input.value) {
        if (input.isComplete) latestComplete(input.value)
    }
    PinEntryPanel(input = input, title = title, message = message, isError = isError)
}

/** A sheet title, centered text and a spacer, used by the short explanation steps. */
@Composable
private fun StepText(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(NeriboTheme.spacing.sm))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Opens the secrets: asks for a PIN and starts the matching session. */
@Composable
fun SecretsUnlockSheet(
    onDismiss: () -> Unit,
    onUnlocked: (SessionMode) -> Unit,
) {
    val input = rememberPinInput()
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(SecretsVault.lockoutSecondsRemaining()) }
    val lockoutUntil by SecretsVault.lockoutUntil.collectAsStateWithLifecycle()
    val latestUnlocked by rememberUpdatedState(onUnlocked)

    LaunchedEffect(lockoutUntil) {
        while (true) {
            val left = SecretsVault.lockoutSecondsRemaining()
            secondsLeft = left
            if (left <= 0) break
            delay(1000)
        }
    }

    LaunchedEffect(input.value) {
        if (input.value.isNotEmpty() && isError) {
            isError = false
            message = null
        }
        if (input.isComplete) {
            busy = true
            val result = SecretsVault.unlock(input.value)
            busy = false
            when (result) {
                is UnlockResult.Success -> latestUnlocked(result.mode)
                is UnlockResult.Wrong -> {
                    message = WRONG_PIN_TEXT
                    isError = true
                    shake += 1
                    input.clear()
                }
                is UnlockResult.LockedOut -> {
                    secondsLeft = result.secondsLeft
                    message = null
                    isError = false
                    shake += 1
                    input.clear()
                }
                is UnlockResult.Unavailable -> {
                    message = "Secrets aren't available right now."
                    isError = true
                    input.clear()
                }
            }
        }
    }

    val lockedOut = secondsLeft > 0
    val shownMessage = when {
        lockedOut -> "Too many tries. Try again in $secondsLeft seconds."
        else -> message ?: "Enter your secrets PIN"
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PinEntryPanel(
                input = input,
                title = "Secrets PIN",
                message = shownMessage,
                isError = lockedOut || isError,
                shakeTrigger = shake,
                enabled = !busy && !lockedOut,
            )
            Spacer(modifier = Modifier.height(NeriboTheme.spacing.md))
            NeriboButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Text,
            )
        }
    }
}

private enum class SetupStep { Explain, EnterA, ConfirmA, OfferDecoy, EnterB, ConfirmB, Saving }

/**
 * First use of the secrets: explains that a forgotten PIN can't be recovered, asks for the main
 * PIN twice and then offers an optional second PIN. Calls [onDone] with a real session open.
 */
@Composable
fun SecretsSetupSheet(
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val spacing = NeriboTheme.spacing
    var step by remember { mutableStateOf(SetupStep.Explain) }
    var round by remember { mutableIntStateOf(0) }
    var acknowledged by remember { mutableStateOf(false) }
    var pinA by remember { mutableStateOf("") }
    var pinB by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestDone by rememberUpdatedState(onDone)

    val finish: (String?) -> Unit = { decoy ->
        step = SetupStep.Saving
        scope.launch {
            val ok = SecretsVault.setUp(pinA, decoy)
            pinA = ""
            pinB = ""
            if (ok) {
                latestDone()
            } else {
                notice = "Couldn't set up your secrets. Please try again."
                acknowledged = false
                step = SetupStep.Explain
            }
        }
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                SetupStep.Explain -> {
                    StepText(
                        title = "Protect your secrets",
                        body = "Your secrets are encrypted on this phone with a six-digit PIN of " +
                            "their own. If you forget this PIN, your secrets cannot be recovered.",
                    )
                    val message = notice
                    if (message != null) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.lg))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = acknowledged,
                                role = Role.Checkbox,
                                onValueChange = { acknowledged = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text(
                            text = "I understand there is no way to recover a forgotten PIN.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.lg))
                    NeriboButton(
                        text = "Continue",
                        onClick = {
                            notice = null
                            step = SetupStep.EnterA
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = acknowledged,
                    )
                }
                SetupStep.EnterA -> key(round) {
                    PinCapture(
                        title = "Choose your secrets PIN",
                        message = notice ?: "Six digits you'll remember.",
                        isError = notice != null,
                    ) { pin ->
                        pinA = pin
                        notice = null
                        step = SetupStep.ConfirmA
                    }
                }
                SetupStep.ConfirmA -> key(round) {
                    PinCapture(
                        title = "Confirm your PIN",
                        message = "Enter the same six digits again.",
                        isError = false,
                    ) { pin ->
                        if (pin == pinA) {
                            step = SetupStep.OfferDecoy
                        } else {
                            pinA = ""
                            notice = MISMATCH_TEXT
                            round += 1
                            step = SetupStep.EnterA
                        }
                    }
                }
                SetupStep.OfferDecoy -> {
                    StepText(
                        title = "Add a decoy PIN?",
                        body = "Optional. A second PIN that opens your secrets with believable " +
                            "fake values and can't change anything. It is for a moment when you " +
                            "are made to unlock them.",
                    )
                    Spacer(modifier = Modifier.height(spacing.xl))
                    NeriboButton(
                        text = "Add a decoy PIN",
                        onClick = {
                            notice = null
                            round += 1
                            step = SetupStep.EnterB
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    NeriboButton(
                        text = "Skip",
                        onClick = { finish(null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SetupStep.EnterB -> key(round) {
                    PinCapture(
                        title = "Choose a decoy PIN",
                        message = notice ?: "It must be different from your main PIN.",
                        isError = notice != null,
                    ) { pin ->
                        if (pin == pinA) {
                            notice = "Choose a PIN that's different from your main PIN."
                            round += 1
                        } else {
                            pinB = pin
                            notice = null
                            step = SetupStep.ConfirmB
                        }
                    }
                }
                SetupStep.ConfirmB -> key(round) {
                    PinCapture(
                        title = "Confirm the decoy PIN",
                        message = "Enter the same six digits again.",
                        isError = false,
                    ) { pin ->
                        if (pin == pinB) {
                            finish(pin)
                        } else {
                            pinB = ""
                            notice = MISMATCH_TEXT
                            round += 1
                            step = SetupStep.EnterB
                        }
                    }
                }
                SetupStep.Saving -> {
                    StepText(
                        title = "Setting up",
                        body = "Locking your secrets with your PIN. This takes a moment.",
                    )
                }
            }
            if (step != SetupStep.Saving) {
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Text,
                )
            }
        }
    }
}

private enum class PinSettingsMode { Home, ChangeMain, SetDecoy, RemoveDecoy }

/**
 * PIN settings for the secrets: change the main PIN (which re-encrypts every secret) and add,
 * change or remove the optional second PIN. Only opened from a real session.
 */
@Composable
fun SecretsPinSettingsSheet(
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val hasDecoy by SecretsVault.hasDecoyPin.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(PinSettingsMode.Home) }
    val finished: (String) -> Unit = { text ->
        onMessage(text)
        onDismiss()
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (mode) {
                PinSettingsMode.Home -> {
                    StepText(
                        title = "Secrets PIN",
                        body = "Changing your PIN re-encrypts every secret with the new one.",
                    )
                    Spacer(modifier = Modifier.height(spacing.xl))
                    NeriboButton(
                        text = "Change secrets PIN",
                        onClick = { mode = PinSettingsMode.ChangeMain },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    NeriboButton(
                        text = if (hasDecoy) "Change decoy PIN" else "Add a decoy PIN",
                        onClick = { mode = PinSettingsMode.SetDecoy },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                    )
                    if (hasDecoy) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        NeriboButton(
                            text = "Remove decoy PIN",
                            onClick = { mode = PinSettingsMode.RemoveDecoy },
                            modifier = Modifier.fillMaxWidth(),
                            style = ButtonStyle.Secondary,
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
                PinSettingsMode.ChangeMain -> {
                    ChangeMainPinFlow(onFinished = finished)
                    BackToHome(onClick = { mode = PinSettingsMode.Home })
                }
                PinSettingsMode.SetDecoy -> {
                    DecoyPinFlow(onFinished = finished)
                    BackToHome(onClick = { mode = PinSettingsMode.Home })
                }
                PinSettingsMode.RemoveDecoy -> {
                    RemoveDecoyStep(onFinished = finished)
                    BackToHome(onClick = { mode = PinSettingsMode.Home })
                }
            }
        }
    }
}

@Composable
private fun BackToHome(onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(NeriboTheme.spacing.md))
    NeriboButton(
        text = "Back",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        style = ButtonStyle.Text,
    )
}

private enum class ChangeStep { Current, New, Confirm, Working }

@Composable
private fun ChangeMainPinFlow(onFinished: (String) -> Unit) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as NeriboApp).container }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(ChangeStep.Current) }
    var round by remember { mutableIntStateOf(0) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestFinished by rememberUpdatedState(onFinished)

    when (step) {
        ChangeStep.Current -> key(round) {
            PinCapture(
                title = "Enter your current PIN",
                message = notice ?: "Your secrets PIN.",
                isError = notice != null,
            ) { pin ->
                currentPin = pin
                notice = null
                step = ChangeStep.New
            }
        }
        ChangeStep.New -> key(round) {
            PinCapture(
                title = "Choose a new PIN",
                message = notice ?: "Six digits you'll remember.",
                isError = notice != null,
            ) { pin ->
                if (pin == currentPin) {
                    notice = "Choose a different PIN."
                    round += 1
                } else {
                    newPin = pin
                    notice = null
                    step = ChangeStep.Confirm
                }
            }
        }
        ChangeStep.Confirm -> key(round) {
            PinCapture(
                title = "Confirm your new PIN",
                message = "Enter the same six digits again.",
                isError = false,
            ) { pin ->
                if (pin == newPin) {
                    step = ChangeStep.Working
                    scope.launch {
                        val result = SecretsVault.changePinA(
                            currentPin = currentPin,
                            newPin = newPin,
                            database = container.database,
                            secrets = container.secretsRepository,
                        )
                        when (result) {
                            is PinChangeResult.Success -> {
                                currentPin = ""
                                newPin = ""
                                latestFinished("Secrets PIN changed")
                            }
                            is PinChangeResult.WrongPin -> {
                                notice = WRONG_PIN_TEXT
                                round += 1
                                step = ChangeStep.Current
                            }
                            is PinChangeResult.LockedOut -> {
                                notice = "Too many tries. Try again in ${result.secondsLeft} seconds."
                                round += 1
                                step = ChangeStep.Current
                            }
                            is PinChangeResult.NotAllowed -> {
                                notice = "Choose a different PIN."
                                round += 1
                                step = ChangeStep.New
                            }
                            is PinChangeResult.Failed -> {
                                notice = "Couldn't change the PIN. Nothing was changed."
                                round += 1
                                step = ChangeStep.Current
                            }
                        }
                    }
                } else {
                    newPin = ""
                    notice = MISMATCH_TEXT
                    round += 1
                    step = ChangeStep.New
                }
            }
        }
        ChangeStep.Working -> StepText(
            title = "Re-encrypting",
            body = "Locking every secret with your new PIN. Please keep the app open.",
        )
    }
}

private enum class DecoyStep { New, Confirm, Working }

@Composable
private fun DecoyPinFlow(onFinished: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(DecoyStep.New) }
    var round by remember { mutableIntStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestFinished by rememberUpdatedState(onFinished)

    when (step) {
        DecoyStep.New -> key(round) {
            PinCapture(
                title = "Choose a decoy PIN",
                message = notice ?: "It must be different from your main PIN.",
                isError = notice != null,
            ) { pin ->
                firstPin = pin
                notice = null
                step = DecoyStep.Confirm
            }
        }
        DecoyStep.Confirm -> key(round) {
            PinCapture(
                title = "Confirm the decoy PIN",
                message = "Enter the same six digits again.",
                isError = false,
            ) { pin ->
                if (pin == firstPin) {
                    step = DecoyStep.Working
                    scope.launch {
                        val result = SecretsVault.setDecoyPin(pin)
                        firstPin = ""
                        if (result is PinChangeResult.Success) {
                            latestFinished("Decoy PIN saved")
                        } else {
                            notice = if (result is PinChangeResult.NotAllowed) {
                                "Choose a PIN that's different from your main PIN."
                            } else {
                                "Couldn't save that PIN."
                            }
                            round += 1
                            step = DecoyStep.New
                        }
                    }
                } else {
                    firstPin = ""
                    notice = MISMATCH_TEXT
                    round += 1
                    step = DecoyStep.New
                }
            }
        }
        DecoyStep.Working -> StepText(title = "Saving", body = "One moment.")
    }
}

@Composable
private fun RemoveDecoyStep(onFinished: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val latestFinished by rememberUpdatedState(onFinished)
    StepText(
        title = "Remove the decoy PIN?",
        body = if (failed) "Couldn't remove it. Please try again." else "Your main PIN and your secrets stay exactly as they are.",
    )
    Spacer(modifier = Modifier.height(NeriboTheme.spacing.xl))
    NeriboButton(
        text = "Remove decoy PIN",
        onClick = {
            working = true
            scope.launch {
                val result = SecretsVault.setDecoyPin(null)
                working = false
                if (result is PinChangeResult.Success) {
                    latestFinished("Decoy PIN removed")
                } else {
                    failed = true
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !working,
        style = ButtonStyle.Destructive,
    )
}
