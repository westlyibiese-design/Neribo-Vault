package com.westly.neribovault.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Registers the global Search screen. */
fun NavGraphBuilder.searchGraph(navController: NavHostController) {
    composable(Routes.SEARCH) {
        SearchScreen(
            onBack = { navController.popBackStack() },
            onOpenRoute = { route ->
                navController.navigate(route) { launchSingleTop = true }
            },
        )
    }
}
