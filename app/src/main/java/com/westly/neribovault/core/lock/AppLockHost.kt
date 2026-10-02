package com.westly.neribovault.core.lock

import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.ui.components.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Constant key under which the app content's saved state is kept while the app is locked. */
private const val CONTENT_STATE_KEY = "neribo_app_content"

/**
 * Counts how many times the app has become locked (0 until the first lock). Screens that must
 * not survive a lock, such as a vault's unlocked state, read it and reset when it changes.
 */
val LocalAppLockEpoch: ProvidableCompositionLocal<Int> = staticCompositionLocalOf { 0 }

/**
 * Wraps the whole app. Shows onboarding on first run, the lock screen when locked, and
 * [content] only while unlocked. Locking removes [content] from the composition entirely, but
 * its saved state (navigation back stack, scroll positions, editor state) is kept and handed
 * back after unlocking, so the person continues where they left off.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppLockHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val manager = rememberLockManager()
    val state by manager.state.collectAsStateWithLifecycle()
    val blockScreenshots by manager.blockScreenshots.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showForgotDialog by remember { mutableStateOf(false) }
    var showResetFailed by remember { mutableStateOf(false) }

    // These stay composed the whole time, so they outlive the content being removed on lock.
    val stateHolder = rememberSaveableStateHolder()
    val lockEpoch = rememberLockEpoch(state)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Do not leave a focused field or the keyboard on top of the lock screen.
    LaunchedEffect(state) {
        if (state == LockState.Locked) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // After an erase-everything reset, a fresh start must begin at Home, not resume old screens.
    LaunchedEffect(state) {
        if (state == LockState.NotSetUp) stateHolder.removeState(CONTENT_STATE_KEY)
    }

    // The lock and setup screens are always hidden from the recents preview.
    ScreenshotGuard(secure = blockScreenshots || state != LockState.Unlocked)

    Crossfade(targetState = state, animationSpec = tween(220), label = "appLock") { current ->
        when (current) {
            LockState.NotSetUp -> OnboardingScreen(manager = manager)
            LockState.Locked -> LockScreen(manager = manager, onForgotPin = { showForgotDialog = true })
            LockState.Unlocked -> CompositionLocalProvider(LocalAppLockEpoch provides lockEpoch) {
                stateHolder.SaveableStateProvider(CONTENT_STATE_KEY) { content() }
            }
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

/**
 * Returns a number that starts at 0 and goes up by one each time [state] changes from
 * Unlocked to Locked. It never goes down and does not change on recomposition.
 */
@Composable
private fun rememberLockEpoch(state: LockState): Int {
    var epoch by rememberSaveable { mutableStateOf(0) }
    var previous by remember { mutableStateOf(state) }
    if (previous != state) {
        if (previous == LockState.Unlocked && state == LockState.Locked) epoch += 1
        previous = state
    }
    return epoch
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
