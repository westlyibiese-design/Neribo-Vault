package com.westly.neribovault.feature.goals

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Goals vault. */
object GoalsRoutes {
    const val LIST = Routes.GOALS
}

/** Registers the Goals vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.goalsGraph(navController: NavHostController) {
    composable(GoalsRoutes.LIST) {
        PlaceholderScreen(
            title = "Goals",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
