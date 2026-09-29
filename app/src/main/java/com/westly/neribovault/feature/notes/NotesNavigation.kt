package com.westly.neribovault.feature.notes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Notes vault. */
object NotesRoutes {
    const val LIST = Routes.NOTES
}

/** Registers the Notes vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.notesGraph(navController: NavHostController) {
    composable(NotesRoutes.LIST) {
        PlaceholderScreen(
            title = "Notes",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
