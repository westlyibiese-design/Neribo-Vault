package com.westly.neribovault.feature.memories

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Memories vault. */
object MemoriesRoutes {
    const val LIST = Routes.MEMORIES
}

/** Registers the Memories vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.memoriesGraph(navController: NavHostController) {
    composable(MemoriesRoutes.LIST) {
        PlaceholderScreen(
            title = "Memories",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
