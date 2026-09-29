package com.westly.neribovault

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.lock.AppLockHost
import com.westly.neribovault.core.navigation.NeriboNavHost

/** Single activity. Extends FragmentActivity because biometrics (Phase 2) need it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeriboTheme {
                AppLockHost {
                    NeriboNavHost()
                }
            }
        }
    }
}
