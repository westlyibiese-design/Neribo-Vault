package com.westly.neribovault.feature.accounts.security

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Accounts PIN screen: shows whether Accounts is not set up, locked or unlocked (with a live
 * idle countdown), and lets the owner set up, unlock, lock now and change PIN settings. The
 * status text is the same for both kinds of session.
 */
@Composable
fun AccountsSecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        AccountsVault.attach(context)
        true
    }
    val setup by AccountsVault.setup.collectAsStateWithLifecycle()
    val mode by AccountsVault.mode.collectAsStateWithLifecycle()
    val lockoutUntil by AccountsVault.lockoutUntil.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSetup by rememberSaveable { mutableStateOf(false) }
    var showUnlock by rememberSaveable { mutableStateOf(false) }
    var showPinSettings by rememberSaveable { mutableStateOf(false) }
    var idleSeconds by remember { mutableIntStateOf(AccountsVault.idleSecondsRemaining()) }
    var lockoutSeconds by remember { mutableIntStateOf(AccountsVault.lockoutSecondsRemaining()) }

    // Ticks the idle countdown and the lockout line. Stops once there is nothing left to count.
    LaunchedEffect(mode, lockoutUntil) {
        while (true) {
            idleSeconds = AccountsVault.idleSecondsRemaining()
            lockoutSeconds = AccountsVault.lockoutSecondsRemaining()
            if (mode == AccountsSessionMode.Locked && lockoutSeconds <= 0) break
            delay(500)
        }
    }

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Accounts PIN", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when (setup) {
            AccountsSetup.Loading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            AccountsSetup.Unavailable -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = "Accounts PIN isn't available",
                message = "This phone's secure storage couldn't be opened. Close the app and try again.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            AccountsSetup.NotSetUp, AccountsSetup.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                val ready = setup == AccountsSetup.Ready
                val unlocked = mode != AccountsSessionMode.Locked
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(spacing.lg)) {
                        Text(
                            text = when {
                                !ready -> "Not set up"
                                unlocked -> "Unlocked \u00B7 " + formatClock(idleSeconds) + " left"
                                else -> "Locked"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (lockoutSeconds > 0) {
                            Spacer(modifier = Modifier.height(spacing.xs))
                            Text(
                                text = "Try again in " + secondsLabel(lockoutSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(spacing.lg))
                when {
                    !ready -> NeriboButton(
                        text = "Set up Accounts PIN",
                        onClick = { showSetup = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    !unlocked -> NeriboButton(
                        text = "Unlock",
                        onClick = { showUnlock = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {
                        NeriboButton(
                            text = "Lock now",
                            onClick = { AccountsVault.endSession() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                        NeriboButton(
                            text = "Change PIN settings",
                            onClick = {
                                if (AccountsVault.mode.value == AccountsSessionMode.Real) {
                                    AccountsVault.touch()
                                    showPinSettings = true
                                } else {
                                    showMessage(AccountsVault.BLOCKED_MESSAGE)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = ButtonStyle.Secondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.xl))
                Text(
                    text = "This PIN is separate from your app PIN and your Developer secrets PINs. " +
                        "It protects the passwords and secret fields in Accounts. " +
                        "If you forget it, those passwords cannot be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showSetup) {
        AccountsSetupSheet(
            onDismiss = { showSetup = false },
            onDone = { showSetup = false },
        )
    }
    if (showUnlock) {
        AccountsUnlockSheet(
            onDismiss = { showUnlock = false },
            onUnlocked = { showUnlock = false },
        )
    }
    if (showPinSettings) {
        AccountsPinSettingsSheet(
            onDismiss = { showPinSettings = false },
            onMessage = { showMessage(it) },
        )
    }
}

/** 108 seconds as "1:48". */
private fun formatClock(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
}

/** "1 second" or "24 seconds". */
private fun secondsLabel(count: Int): String = if (count == 1) "1 second" else "$count seconds"
