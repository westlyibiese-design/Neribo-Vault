package com.westly.neribovault.feature.accounts.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.westly.neribovault.core.lock.AppLockManager
import com.westly.neribovault.core.lock.LockState

/**
 * Blocks screenshots and the recents preview while [active] is true (a password or secret field is on screen).
 * When it ends, the window goes back to whatever the app lock wants: still secure if the person
 * turned on "block screenshots" or the app is locked, otherwise normal.
 */
@Composable
fun AccountsSecureWindowEffect(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(context, active) {
        val window = if (active) context.findHostActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val appWantsSecure = AppLockManager.blockScreenshots.value ||
                AppLockManager.state.value != LockState.Unlocked
            if (window != null && !appWantsSecure) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
