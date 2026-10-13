package com.westly.neribovault.feature.coder

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.coder.home.CoderHomeScreen
import com.westly.neribovault.feature.coder.trash.CoderTrashScreen
import com.westly.neribovault.feature.coder.workspace.CoderWorkspaceScreen

/** The vault id used by the vault lock. */
const val VAULT_ID = "coder"

/** The name shown on the lock screen and in the top bar. */
const val VAULT_NAME = "Coder"

/** Route names and argument keys for the Coder vault. Every route is registered by [coderGraph]. */
object CoderRoutes {
    const val LIST = Routes.CODER
    const val PROJECT = "coder/project/{projectId}"
    const val TRASH = "coder/trash"
    const val ARG_PROJECT_ID = "projectId"

    fun project(id: String) = "coder/project/$id"
}

/** Registers every Coder destination, each behind the vault lock. */
fun NavGraphBuilder.coderGraph(navController: NavHostController) {
    composable(CoderRoutes.LIST) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderHomeScreen(
                onBack = { navController.popBackStack() },
                onOpenProject = { id ->
                    navController.navigate(CoderRoutes.project(id)) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(CoderRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = CoderRoutes.PROJECT,
        arguments = listOf(navArgument(CoderRoutes.ARG_PROJECT_ID) { type = NavType.StringType }),
    ) { entry ->
        val projectId = entry.arguments?.getString(CoderRoutes.ARG_PROJECT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderWorkspaceScreen(projectId = projectId, onBack = { navController.popBackStack() })
        }
    }
    composable(CoderRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}
