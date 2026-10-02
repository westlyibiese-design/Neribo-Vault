package com.westly.neribovault

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.lock.AppLockHost
import com.westly.neribovault.core.navigation.NeriboNavHost
import com.westly.neribovault.core.ui.logo.NeriboLogoReveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Single activity. Extends FragmentActivity because biometrics (Phase 2) need it. */
class MainActivity : FragmentActivity() {
    /** True only when the app is opened from a closed state (no saved instance state). */
    private var isFreshStart: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isFreshStart = savedInstanceState == null
        if (isFreshStart) {
            applyLogoBars()
        } else {
            enableEdgeToEdge()
        }
        setContent {
            val freshStart = remember { isFreshStart }
            var animationDone by remember { mutableStateOf(false) }

            // While the logo plays: create the app container off the main thread and read the
            // stored theme so the first normal frame already uses the right theme.
            val preloadedTheme by produceState<String?>(initialValue = null) {
                if (freshStart) {
                    val app = application as NeriboApp
                    withContext(Dispatchers.Default) { app.container }
                    value = app.container.settingsStore.themeMode
                        .catch { emit("system") }
                        .first()
                }
            }

            val showLogo = freshStart && !(animationDone && preloadedTheme != null)
            if (showLogo) {
                Box(Modifier.fillMaxSize().background(Color(0xFF141414))) {
                    NeriboLogoReveal(onFinished = { animationDone = true })
                }
            } else {
                AppContent(initialThemeMode = preloadedTheme ?: "system")
            }
        }
    }

    /** Dark, edge-to-edge bars with light icons for the logo screen. */
    private fun applyLogoBars() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
    }

    @Composable
    private fun AppContent(initialThemeMode: String) {
        val container = (application as NeriboApp).container
        // "dark" and "light" are the owner's choice; "system" follows the phone. The theme
        // wraps the app lock too, so the lock screen matches.
        val themeMode by container.settingsStore.themeMode
            .collectAsStateWithLifecycle(initialValue = initialThemeMode)
        val systemDark = isSystemInDarkTheme()
        val darkTheme = when (themeMode) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
        // Keep the status and navigation bar icons readable when the chosen theme differs
        // from the phone's own light or dark setting.
        DisposableEffect(darkTheme) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(NAV_LIGHT_SCRIM, NAV_DARK_SCRIM) { darkTheme },
            )
            onDispose {}
        }
        NeriboTheme(darkTheme = darkTheme) {
            AppLockHost {
                NeriboNavHost()
            }
        }
    }

    private companion object {
        /** The same scrims `enableEdgeToEdge()` uses by default for the navigation bar. */
        val NAV_LIGHT_SCRIM: Int = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val NAV_DARK_SCRIM: Int = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
