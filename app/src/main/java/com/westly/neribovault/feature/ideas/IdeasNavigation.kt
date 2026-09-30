package com.westly.neribovault.feature.ideas

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Ideas vault. */
object IdeasRoutes {
    const val LIST = Routes.IDEAS
    const val EDITOR = "ideas/edit/{ideaId}"
    const val TRASH = "ideas/trash"

    /** Name of the path argument in [EDITOR]. */
    const val ARG_IDEA_ID = "ideaId"

    /** The [ARG_IDEA_ID] value that means "create a new idea". */
    const val NEW_IDEA_ID = "new"

    /** Key the editor uses to hand a just-deleted idea id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(ideaId: String) = "ideas/edit/$ideaId"
}

/** Registers the Ideas vault screens: list, editor and Recently deleted. */
fun NavGraphBuilder.ideasGraph(navController: NavHostController) {
    composable(IdeasRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(IdeasRoutes.KEY_DELETED_ID, null)
        }
        IdeasScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(IdeasRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenIdea = { id ->
                navController.navigate(IdeasRoutes.editor(id)) { launchSingleTop = true }
            },
            onNewIdea = {
                navController.navigate(IdeasRoutes.editor(IdeasRoutes.NEW_IDEA_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(IdeasRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = IdeasRoutes.EDITOR,
        arguments = listOf(
            navArgument(IdeasRoutes.ARG_IDEA_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val ideaId = entry.arguments?.getString(IdeasRoutes.ARG_IDEA_ID) ?: IdeasRoutes.NEW_IDEA_ID
        IdeaEditorScreen(
            ideaId = ideaId,
            onBack = { navController.popBackStack() },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(IdeasRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
        )
    }
    composable(IdeasRoutes.TRASH) {
        IdeasTrashScreen(onBack = { navController.popBackStack() })
    }
}
