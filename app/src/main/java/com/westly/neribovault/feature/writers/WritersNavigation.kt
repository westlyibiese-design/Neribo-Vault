package com.westly.neribovault.feature.writers

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Writers vault. */
object WritersRoutes {
    const val LIST = Routes.WRITERS
}

/** Registers the Writers vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.writersGraph(navController: NavHostController) {
    composable(WritersRoutes.LIST) {
        PlaceholderScreen(
            title = "Writers",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
