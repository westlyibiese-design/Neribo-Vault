package com.westly.neribovault.feature.security

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for Security. */
object SecurityRoutes {
    const val LIST = Routes.SECURITY
}

/** Registers the Security screen. Replaced by the real screen in a later phase. */
fun NavGraphBuilder.securityGraph(navController: NavHostController) {
    composable(SecurityRoutes.LIST) {
        PlaceholderScreen(
            title = "Security",
            message = "Coming in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
