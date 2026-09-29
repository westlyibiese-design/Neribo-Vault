package com.westly.neribovault.feature.documents

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Documents vault. */
object DocumentsRoutes {
    const val LIST = Routes.DOCUMENTS
}

/** Registers the Documents vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.documentsGraph(navController: NavHostController) {
    composable(DocumentsRoutes.LIST) {
        PlaceholderScreen(
            title = "Documents",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
