package com.westly.neribovault.feature.posts

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Posts vault. */
object PostsRoutes {
    const val LIST = Routes.POSTS
    const val EDITOR = "posts/edit/{postId}"
    const val TRASH = "posts/trash"

    /** Name of the path argument in [EDITOR]. */
    const val ARG_POST_ID = "postId"

    /** The [ARG_POST_ID] value that means "create a new post". */
    const val NEW_POST_ID = "new"

    /** Key the editor uses to hand a just-deleted post id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(postId: String) = "posts/edit/$postId"
}

/** Registers the Posts vault screens: list, editor and Recently deleted. */
fun NavGraphBuilder.postsGraph(navController: NavHostController) {
    composable(PostsRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(PostsRoutes.KEY_DELETED_ID, null)
        }
        PostsScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(PostsRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenPost = { id ->
                navController.navigate(PostsRoutes.editor(id)) { launchSingleTop = true }
            },
            onNewPost = {
                navController.navigate(PostsRoutes.editor(PostsRoutes.NEW_POST_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(PostsRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = PostsRoutes.EDITOR,
        arguments = listOf(
            navArgument(PostsRoutes.ARG_POST_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val postId = entry.arguments?.getString(PostsRoutes.ARG_POST_ID) ?: PostsRoutes.NEW_POST_ID
        PostEditorScreen(
            postId = postId,
            onBack = { navController.popBackStack() },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(PostsRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
            onOpenPost = { id -> navController.navigate(PostsRoutes.editor(id)) },
        )
    }
    composable(PostsRoutes.TRASH) {
        PostsTrashScreen(onBack = { navController.popBackStack() })
    }
}
