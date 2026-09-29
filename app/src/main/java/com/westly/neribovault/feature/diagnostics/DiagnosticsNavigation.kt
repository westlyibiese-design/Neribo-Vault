package com.westly.neribovault.feature.diagnostics

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for Diagnostics. */
object DiagnosticsRoutes {
    const val LIST = Routes.DIAGNOSTICS
}

/** Registers the Diagnostics screen. Replaced by the real screen in a later phase. */
fun NavGraphBuilder.diagnosticsGraph(navController: NavHostController) {
    composable(DiagnosticsRoutes.LIST) {
        PlaceholderScreen(
            title = "Diagnostics",
            message = "Coming in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
