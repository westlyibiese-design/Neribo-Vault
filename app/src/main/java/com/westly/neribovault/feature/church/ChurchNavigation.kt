package com.westly.neribovault.feature.church

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Church vault. */
object ChurchRoutes {
    const val LIST = Routes.CHURCH
    const val EDITOR = "church/edit/{recordId}"
    const val TRASH = "church/trash"

    /** Name of the path argument in [EDITOR]. */
    const val ARG_RECORD_ID = "recordId"

    /** The [ARG_RECORD_ID] value that means "create a new record". */
    const val NEW_RECORD_ID = "new"

    /** Key the editor uses to hand a just-deleted record id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(recordId: String) = "church/edit/$recordId"
}

/** Registers the Church vault screens: list, editor and Recently deleted. */
fun NavGraphBuilder.churchGraph(navController: NavHostController) {
    composable(ChurchRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(ChurchRoutes.KEY_DELETED_ID, null)
        }
        ChurchScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(ChurchRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenRecord = { id ->
                navController.navigate(ChurchRoutes.editor(id)) { launchSingleTop = true }
            },
            onNewRecord = {
                navController.navigate(ChurchRoutes.editor(ChurchRoutes.NEW_RECORD_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(ChurchRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = ChurchRoutes.EDITOR,
        arguments = listOf(
            navArgument(ChurchRoutes.ARG_RECORD_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val recordId = entry.arguments?.getString(ChurchRoutes.ARG_RECORD_ID)
            ?: ChurchRoutes.NEW_RECORD_ID
        ChurchRecordEditorScreen(
            recordId = recordId,
            onBack = { navController.popBackStack() },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ChurchRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
        )
    }
    composable(ChurchRoutes.TRASH) {
        ChurchTrashScreen(onBack = { navController.popBackStack() })
    }
}
