package com.westly.neribovault.core.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

private const val STEP_CURRENT = 0
private const val STEP_NEW = 1
private const val STEP_CONFIRM = 2

/** Friendly text for a failed [PinResult], or null when there is nothing to say. */
internal fun pinErrorText(result: PinResult): String? = when (result) {
    is PinResult.Success -> null
    is PinResult.Wrong -> {
        val left = result.attemptsLeft
        if (left != null && left in 1..3) {
            "That PIN isn't right. $left ${if (left == 1) "try" else "tries"} left."
        } else {
            "That PIN isn't right."
        }
    }
    is PinResult.LockedOut -> "Too many tries. Try again in ${result.secondsLeft} seconds."
}

/** Asks for a PIN and checks it with [verify]. Calls [onVerified] when it is correct. */
@Composable
fun PinVerifyStep(
    title: String,
    verify: suspend (String) -> PinResult,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val input = rememberPinInput()
    val latestVerify by rememberUpdatedState(verify)
    val latestVerified by rememberUpdatedState(onVerified)
    var error by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableIntStateOf(0) }
    LaunchedEffect(input.value) {
        if (input.value.isNotEmpty()) error = null
        if (input.isComplete) {
            val result = latestVerify(input.value)
            if (result is PinResult.Success) {
                latestVerified()
            } else {
                error = pinErrorText(result)
                shake += 1
                input.clear()
            }
        }
    }
    PinEntryPanel(
        input = input,
        modifier = modifier,
        title = title,
        message = error ?: message,
        isError = error != null,
        shakeTrigger = shake,
    )
}

@Composable
private fun PinCaptureStep(
    title: String,
    message: String?,
    isError: Boolean,
    onComplete: suspend (String) -> Unit,
) {
    val input = rememberPinInput()
    val latestComplete by rememberUpdatedState(onComplete)
    LaunchedEffect(input.value) {
        if (input.isComplete) latestComplete(input.value)
    }
    PinEntryPanel(input = input, title = title, message = message, isError = isError)
}

/**
 * Create or change a PIN: optionally checks the current PIN first ([requireCurrent]), then asks
 * for the new PIN twice. Calls [onSave] with the confirmed PIN, then [onFinished].
 */
@Composable
fun PinSetupFlow(
    onSave: suspend (String) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    requireCurrent: Boolean = false,
    verifyCurrent: suspend (String) -> PinResult = { PinResult.Success },
    newTitle: String = "Choose a new PIN",
) {
    var step by remember { mutableIntStateOf(if (requireCurrent) STEP_CURRENT else STEP_NEW) }
    var firstPin by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestSave by rememberUpdatedState(onSave)
    val latestFinished by rememberUpdatedState(onFinished)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            STEP_CURRENT -> PinVerifyStep(
                title = "Enter your current PIN",
                verify = verifyCurrent,
                onVerified = { step = STEP_NEW },
            )
            STEP_CONFIRM -> PinCaptureStep(
                title = "Confirm your PIN",
                message = "Enter the same six digits again.",
                isError = false,
                onComplete = { pin ->
                    if (pin == firstPin) {
                        firstPin = ""
                        latestSave(pin)
                        latestFinished()
                    } else {
                        firstPin = ""
                        notice = "Those didn't match. Let's start again."
                        step = STEP_NEW
                    }
                },
            )
            else -> PinCaptureStep(
                title = newTitle,
                message = notice ?: "Choose six digits you'll remember.",
                isError = notice != null,
                onComplete = { pin ->
                    firstPin = pin
                    notice = null
                    step = STEP_CONFIRM
                },
            )
        }
    }
}
