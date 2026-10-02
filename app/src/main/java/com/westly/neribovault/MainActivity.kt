package com.westly.neribovault

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.lock.AppLockHost
import com.westly.neribovault.core.navigation.NeriboNavHost

/** Single activity. Extends FragmentActivity because biometrics (Phase 2) need it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NeriboApp).container
        setContent {
            // "dark" and "light" are the owner's choice; "system" follows the phone. The theme
            // wraps the app lock too, so the lock screen matches.
            val themeMode by container.settingsStore.themeMode
                .collectAsStateWithLifecycle(initialValue = "system")
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
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
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
    }

    private companion object {
        /** The same scrims `enableEdgeToEdge()` uses by default for the navigation bar. */
        val NAV_LIGHT_SCRIM: Int = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val NAV_DARK_SCRIM: Int = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
