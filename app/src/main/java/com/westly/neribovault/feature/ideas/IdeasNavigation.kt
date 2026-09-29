package com.westly.neribovault.feature.ideas

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Ideas vault. */
object IdeasRoutes {
    const val LIST = Routes.IDEAS
}

/** Registers the Ideas vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.ideasGraph(navController: NavHostController) {
    composable(IdeasRoutes.LIST) {
        PlaceholderScreen(
            title = "Ideas",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
