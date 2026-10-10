package com.westly.neribovault.feature.authenticator.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.lock.AppLockManager
import com.westly.neribovault.core.lock.LockState

/**
 * Blocks screenshots and the recents preview while [active] is true. When it ends, the window goes
 * back to whatever the app lock wants: still secure if the person turned on "block screenshots"
 * or the app is locked, otherwise normal.
 *
 * The app lock host clears the flag whenever the lock state changes back to Unlocked (for example
 * after the app goes to the background for the QR scanner and returns), so while [active] the flag
 * is added again on every resume and whenever the lock state or the "block screenshots" setting
 * changes.
 */
@Composable
fun AuthenticatorSecureWindowEffect(active: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lockState by AppLockManager.state.collectAsStateWithLifecycle()
    val blockScreenshots by AppLockManager.blockScreenshots.collectAsStateWithLifecycle()

    DisposableEffect(context, lifecycleOwner, active) {
        val window = if (active) context.findHostActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (window != null) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val appWantsSecure = AppLockManager.blockScreenshots.value ||
                AppLockManager.state.value != LockState.Unlocked
            if (window != null && !appWantsSecure) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // Runs after the app lock host has applied its own change, so this one wins while active.
    LaunchedEffect(context, active, lockState, blockScreenshots) {
        if (active) {
            context.findHostActivity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
