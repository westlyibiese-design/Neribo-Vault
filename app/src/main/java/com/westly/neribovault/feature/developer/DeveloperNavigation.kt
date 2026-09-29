package com.westly.neribovault.feature.developer

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Developer vault. */
object DeveloperRoutes {
    const val LIST = Routes.DEVELOPER
}

/** Registers the Developer vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.developerGraph(navController: NavHostController) {
    composable(DeveloperRoutes.LIST) {
        PlaceholderScreen(
            title = "Developer",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
