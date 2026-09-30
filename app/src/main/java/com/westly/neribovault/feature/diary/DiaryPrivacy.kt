package com.westly.neribovault.feature.diary

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** How many diary screens are showing right now, and whether the window was already secure. */
private object SecureWindowCount {
    var holders = 0
    var wasSecureBefore = false
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * While a diary screen is showing, the window is marked secure so diary text never appears in
 * the recents screen or in screenshots. If the app already had the flag on, it is left on.
 * Only ever touched from the main thread, so a plain counter is enough.
 */
@Composable
internal fun DiaryPrivacyGuard() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findHostActivity()?.window
        if (window != null) {
            if (SecureWindowCount.holders == 0) {
                SecureWindowCount.wasSecureBefore =
                    (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            }
            SecureWindowCount.holders++
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null) {
                SecureWindowCount.holders--
                if (SecureWindowCount.holders <= 0) {
                    SecureWindowCount.holders = 0
                    if (!SecureWindowCount.wasSecureBefore) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }
}
