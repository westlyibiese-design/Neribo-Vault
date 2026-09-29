package com.westly.neribovault.feature.church

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Church vault. */
object ChurchRoutes {
    const val LIST = Routes.CHURCH
}

/** Registers the Church vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.churchGraph(navController: NavHostController) {
    composable(ChurchRoutes.LIST) {
        PlaceholderScreen(
            title = "Church",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
