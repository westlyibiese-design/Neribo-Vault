package com.westly.neribovault.feature.posts

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Route names for the Posts vault. */
object PostsRoutes {
    const val LIST = Routes.POSTS
}

/** Registers the Posts vault screens. Replaced by the real screens in a later phase. */
fun NavGraphBuilder.postsGraph(navController: NavHostController) {
    composable(PostsRoutes.LIST) {
        PlaceholderScreen(
            title = "Posts",
            message = "This vault is being built. It will appear in a later update.",
            onBack = { navController.popBackStack() },
        )
    }
}
