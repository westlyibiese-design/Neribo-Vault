package com.westly.neribovault.feature.security

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Route names for Security. */
object SecurityRoutes {
    const val LIST = Routes.SECURITY
}

/** Registers the Security settings screen. */
fun NavGraphBuilder.securityGraph(navController: NavHostController) {
    composable(SecurityRoutes.LIST) {
        SecurityScreen(onBack = { navController.popBackStack() })
    }
}
