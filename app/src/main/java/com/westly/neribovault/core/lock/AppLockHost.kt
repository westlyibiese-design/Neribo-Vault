package com.westly.neribovault.core.lock

import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.ui.components.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps the whole app. Shows onboarding on first run, the lock screen when locked, and
 * [content] only while unlocked. Locking removes [content] from the screen entirely.
 */
@Composable
fun AppLockHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val manager = rememberLockManager()
    val state by manager.state.collectAsStateWithLifecycle()
    val blockScreenshots by manager.blockScreenshots.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showForgotDialog by remember { mutableStateOf(false) }
    var showResetFailed by remember { mutableStateOf(false) }

    // The lock and setup screens are always hidden from the recents preview.
    ScreenshotGuard(secure = blockScreenshots || state != LockState.Unlocked)

    Crossfade(targetState = state, animationSpec = tween(220), label = "appLock") { current ->
        when (current) {
            LockState.NotSetUp -> OnboardingScreen(manager = manager)
            LockState.Locked -> LockScreen(manager = manager, onForgotPin = { showForgotDialog = true })
            LockState.Unlocked -> content()
        }
    }

    if (showForgotDialog) {
        ConfirmDialog(
            title = "Erase everything?",
            message = "Everything in Neribo Vault is stored only on this phone, so a forgotten PIN " +
                "can't be recovered. The only way to reset it is to erase all Neribo Vault data. " +
                "This cannot be undone.",
            confirmLabel = "Erase everything",
            destructive = true,
            onConfirm = {
                showForgotDialog = false
                scope.launch {
                    try {
                        val container = (context.applicationContext as NeriboApp).container
                        withContext(Dispatchers.IO) { container.database.clearAllTables() }
                        manager.resetAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Only reset the PIN if the data really was erased.
                        showResetFailed = true
                    }
                }
            },
            onDismiss = { showForgotDialog = false },
        )
    }

    if (showResetFailed) {
        ConfirmDialog(
            title = "Couldn't erase your data",
            message = "Nothing was changed. Please try again.",
            confirmLabel = "OK",
            dismissLabel = "Close",
            onConfirm = { showResetFailed = false },
            onDismiss = { showResetFailed = false },
        )
    }
}

/** Applies or clears FLAG_SECURE on the window (blocks screenshots and the recents preview). */
@Composable
private fun ScreenshotGuard(secure: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, secure) {
        val window = context.findActivity()?.window
        if (window != null) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose { }
    }
}
