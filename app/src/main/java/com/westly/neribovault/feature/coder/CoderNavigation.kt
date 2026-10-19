package com.westly.neribovault.feature.coder

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.coder.ai.CoderAiSettingsScreen
import com.westly.neribovault.feature.coder.build.BuildWizardScreen
import com.westly.neribovault.feature.coder.env.EnvironmentScreen
import com.westly.neribovault.feature.coder.github.CoderGitHubScreen
import com.westly.neribovault.feature.coder.guidance.CoderHelpScreen
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
    const val AI_SETTINGS = "coder/ai"
    const val HELP = "coder/help"
    const val GITHUB = "coder/github/{projectId}"
    const val ENV = "coder/env/{projectId}"
    const val BUILD = "coder/build/{projectId}"
    const val ARG_PROJECT_ID = "projectId"

    fun project(id: String) = "coder/project/$id"

    fun github(id: String) = "coder/github/$id"

    fun env(id: String) = "coder/env/$id"

    fun build(id: String) = "coder/build/$id"
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
                onOpenAiSettings = {
                    navController.navigate(CoderRoutes.AI_SETTINGS) { launchSingleTop = true }
                },
                onOpenHelp = {
                    navController.navigate(CoderRoutes.HELP) { launchSingleTop = true }
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
            CoderWorkspaceScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
                onOpenAiSettings = {
                    navController.navigate(CoderRoutes.AI_SETTINGS) { launchSingleTop = true }
                },
                onOpenGitHub = {
                    navController.navigate(CoderRoutes.github(projectId)) { launchSingleTop = true }
                },
                onOpenEnvironment = {
                    navController.navigate(CoderRoutes.env(projectId)) { launchSingleTop = true }
                },
                onOpenBuild = {
                    navController.navigate(CoderRoutes.build(projectId)) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = CoderRoutes.ENV,
        arguments = listOf(navArgument(CoderRoutes.ARG_PROJECT_ID) { type = NavType.StringType }),
    ) { entry ->
        val projectId = entry.arguments?.getString(CoderRoutes.ARG_PROJECT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            EnvironmentScreen(projectId = projectId, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = CoderRoutes.BUILD,
        arguments = listOf(navArgument(CoderRoutes.ARG_PROJECT_ID) { type = NavType.StringType }),
    ) { entry ->
        val projectId = entry.arguments?.getString(CoderRoutes.ARG_PROJECT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            BuildWizardScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
                onOpenGitHub = {
                    navController.navigate(CoderRoutes.github(projectId)) { launchSingleTop = true }
                },
                onOpenEnvironment = {
                    navController.navigate(CoderRoutes.env(projectId)) { launchSingleTop = true }
                },
                onAskAi = { navController.popBackStack() },
            )
        }
    }
    composable(
        route = CoderRoutes.GITHUB,
        arguments = listOf(navArgument(CoderRoutes.ARG_PROJECT_ID) { type = NavType.StringType }),
    ) { entry ->
        val projectId = entry.arguments?.getString(CoderRoutes.ARG_PROJECT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderGitHubScreen(projectId = projectId, onBack = { navController.popBackStack() })
        }
    }
    composable(CoderRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderTrashScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(CoderRoutes.AI_SETTINGS) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderAiSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(CoderRoutes.HELP) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            CoderHelpScreen(onBack = { navController.popBackStack() })
        }
    }
}
