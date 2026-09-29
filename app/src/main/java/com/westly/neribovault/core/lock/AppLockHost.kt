package com.westly.neribovault.core.lock

import androidx.compose.runtime.Composable

/**
 * Wraps the whole app. Phase 2 will show onboarding or the lock screen here when needed.
 * For now it simply renders [content].
 */
@Composable
fun AppLockHost(content: @Composable () -> Unit) {
    content()
}
