package com.westly.neribovault.feature.accounts.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.accounts.security.AccountsSecureWindowEffect
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsSetup
import com.westly.neribovault.feature.accounts.security.AccountsSetupSheet
import com.westly.neribovault.feature.accounts.security.AccountsUnlockSheet
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.delay

/** What a masked secret looks like. */
const val SECRET_MASK = "••••••••"

/** The snackbar text after copying a password or secret field. */
const val COPIED_SECRET_MESSAGE = "Copied. Clipboard clears in 30 seconds"

/** How long a revealed value stays on screen before it hides itself. */
const val REVEAL_SECONDS = 15

private const val SECURE_STORAGE_MESSAGE = "Secure storage is not available on this phone"

/**
 * Runs actions that need the Accounts session. It works like `runWithSession` in the Developer
 * secrets tab: a real session runs everything; the second-PIN session runs read-only actions
 * and refuses writes with the neutral message; a locked vault asks for the PIN (or sets it up)
 * and then runs the action. The screen that owns it must also call [SecretAccessSheets].
 */
@Stable
class SecretAccess internal constructor(
    private val message: State<(String) -> Unit>,
) {
    /** True while the PIN sheet is open. */
    var showUnlock by mutableStateOf(false)
        internal set

    /** True while the first-time setup sheet is open. */
    var showSetup by mutableStateOf(false)
        internal set

    internal var pending: (() -> Unit)? = null

    /** Shows [text] with the screen's snackbar. */
    fun say(text: String) {
        message.value.invoke(text)
    }

    /**
     * Runs [action] when the session allows it. [requireReal] marks a write action, which the
     * second-PIN session refuses.
     */
    fun run(requireReal: Boolean, action: () -> Unit) {
        when (AccountsVault.mode.value) {
            AccountsSessionMode.Real -> {
                AccountsVault.touch()
                action()
            }
            AccountsSessionMode.Decoy -> {
                if (requireReal) {
                    say(AccountsVault.BLOCKED_MESSAGE)
                } else {
                    AccountsVault.touch()
                    action()
                }
            }
            AccountsSessionMode.Locked -> when (AccountsVault.setup.value) {
                AccountsSetup.Unavailable -> say(SECURE_STORAGE_MESSAGE)
                AccountsSetup.NotSetUp -> {
                    pending = { run(requireReal, action) }
                    showSetup = true
                }
                AccountsSetup.Loading, AccountsSetup.Ready -> {
                    pending = { run(requireReal, action) }
                    showUnlock = true
                }
            }
        }
    }

    internal fun finishPending() {
        val next = pending
        pending = null
        next?.invoke()
    }

    internal fun cancelPending() {
        pending = null
    }
}

/** Creates the [SecretAccess] for a screen. [onMessage] shows a snackbar. */
@Composable
fun rememberSecretAccess(onMessage: (String) -> Unit): SecretAccess {
    val context = LocalContext.current
    remember(context) {
        AccountsVault.attach(context)
        true
    }
    val latest = rememberUpdatedState(onMessage)
    return remember { SecretAccess(latest) }
}

/** The PIN and setup sheets that [SecretAccess] opens. Call once per screen. */
@Composable
fun SecretAccessSheets(access: SecretAccess) {
    if (access.showUnlock) {
        AccountsUnlockSheet(
            onDismiss = {
                access.showUnlock = false
                access.cancelPending()
            },
            onUnlocked = {
                access.showUnlock = false
                access.finishPending()
            },
        )
    }
    if (access.showSetup) {
        AccountsSetupSheet(
            onDismiss = {
                access.showSetup = false
                access.cancelPending()
            },
            onDone = {
                access.showSetup = false
                access.finishPending()
            },
        )
    }
}

/**
 * The decrypted values currently on screen, by row key. They live only in composition: they are
 * cleared when the session ends, when the screen stops and when the screen is left.
 */
@Stable
class RevealState {
    private val shown = mutableStateMapOf<String, String>()

    /** True while any value is on screen. */
    val anyRevealed: Boolean get() = shown.isNotEmpty()

    fun value(key: String): String? = shown[key]

    fun show(key: String, value: String) {
        shown[key] = value
    }

    fun hide(key: String) {
        shown.remove(key)
    }

    fun clear() {
        shown.clear()
    }
}

/** Creates a [RevealState] that clears itself and blocks screenshots while a value is shown. */
@Composable
fun rememberRevealState(): RevealState {
    val state = remember { RevealState() }
    val mode by AccountsVault.mode.collectAsStateWithLifecycle()
    LaunchedEffect(mode) {
        if (mode == AccountsSessionMode.Locked) state.clear()
    }
    LifecycleSaveEffect(onSave = { state.clear() })
    AccountsSecureWindowEffect(active = state.anyRevealed)
    return state
}

/**
 * One secret value with Reveal / Hide and Copy. [revealedValue] is the text to show (null keeps
 * it masked); it hides itself after [REVEAL_SECONDS]. When nothing is stored the row shows
 * [emptyText] and, when [onAdd] is given, an Add button.
 */
@Composable
fun SecretRow(
    label: String,
    isStored: Boolean,
    revealedValue: String?,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "No password stored",
    onAdd: (() -> Unit)? = null,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val isRevealed = revealedValue != null
    val latestHide by rememberUpdatedState(onHide)
    var secondsLeft by remember { mutableIntStateOf(REVEAL_SECONDS) }

    LaunchedEffect(isRevealed) {
        if (isRevealed) {
            secondsLeft = REVEAL_SECONDS
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
            latestHide()
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        if (!isStored) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onAdd != null) {
                    NeriboButton(text = "Add", onClick = onAdd, style = ButtonStyle.Text)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = revealedValue ?: SECRET_MASK,
                    style = if (isRevealed) {
                        MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                NeriboButton(
                    text = if (isRevealed) "Hide" else "Reveal",
                    onClick = if (isRevealed) onHide else onReveal,
                    style = ButtonStyle.Text,
                )
                NeriboIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy $label",
                    onClick = onCopy,
                )
            }
            if (isRevealed) {
                Text(
                    text = "Hides in ${secondsLeft}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
