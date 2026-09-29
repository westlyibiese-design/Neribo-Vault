package com.westly.neribovault.feature.diagnostics

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Route names for Diagnostics. */
object DiagnosticsRoutes {
    const val LIST = Routes.DIAGNOSTICS
}

/** Registers the Diagnostics screen. */
fun NavGraphBuilder.diagnosticsGraph(navController: NavHostController) {
    composable(DiagnosticsRoutes.LIST) {
        DiagnosticsScreen(onBack = { navController.popBackStack() })
    }
}
