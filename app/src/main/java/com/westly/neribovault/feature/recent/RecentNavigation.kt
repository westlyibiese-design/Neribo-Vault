package com.westly.neribovault.feature.recent

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes

/** Registers the Recent screen. */
fun NavGraphBuilder.recentGraph(navController: NavHostController) {
    composable(Routes.RECENT) {
        RecentScreen(
            onBack = { navController.popBackStack() },
            onOpenRoute = { route ->
                navController.navigate(route) { launchSingleTop = true }
            },
        )
    }
}
