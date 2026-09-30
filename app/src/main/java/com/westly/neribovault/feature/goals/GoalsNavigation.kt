package com.westly.neribovault.feature.goals

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Goals vault. */
object GoalsRoutes {
    const val LIST = Routes.GOALS
    const val DETAIL = "goals/detail/{goalId}"
    const val EDIT = "goals/edit/{goalId}"
    const val TRASH = "goals/trash"

    /** Name of the path argument in [DETAIL] and [EDIT]. */
    const val ARG_GOAL_ID = "goalId"

    /** The [ARG_GOAL_ID] value that means "create a new goal". */
    const val NEW_GOAL_ID = "new"

    /** Key used to hand a just-deleted goal id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun detail(goalId: String) = "goals/detail/$goalId"

    fun edit(goalId: String) = "goals/edit/$goalId"
}

/** Registers the Goals vault screens: list, detail, editor and Recently deleted. */
fun NavGraphBuilder.goalsGraph(navController: NavHostController) {
    composable(GoalsRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(GoalsRoutes.KEY_DELETED_ID, null)
        }
        GoalsScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(GoalsRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenGoal = { id ->
                navController.navigate(GoalsRoutes.detail(id)) { launchSingleTop = true }
            },
            onNewGoal = {
                navController.navigate(GoalsRoutes.edit(GoalsRoutes.NEW_GOAL_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(GoalsRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = GoalsRoutes.DETAIL,
        arguments = listOf(
            navArgument(GoalsRoutes.ARG_GOAL_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val goalId = entry.arguments?.getString(GoalsRoutes.ARG_GOAL_ID).orEmpty()
        GoalDetailScreen(
            goalId = goalId,
            onBack = { navController.popBackStack() },
            onEdit = {
                navController.navigate(GoalsRoutes.edit(goalId)) { launchSingleTop = true }
            },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(GoalsRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
        )
    }
    composable(
        route = GoalsRoutes.EDIT,
        arguments = listOf(
            navArgument(GoalsRoutes.ARG_GOAL_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val goalId = entry.arguments?.getString(GoalsRoutes.ARG_GOAL_ID) ?: GoalsRoutes.NEW_GOAL_ID
        GoalEditorScreen(
            goalId = goalId,
            onBack = { navController.popBackStack() },
            onSaved = { savedId, wasNew ->
                if (wasNew) {
                    // Straight to the new goal so its steps can be added right away.
                    navController.navigate(GoalsRoutes.detail(savedId)) {
                        popUpTo(GoalsRoutes.EDIT) { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    navController.popBackStack()
                }
            },
        )
    }
    composable(GoalsRoutes.TRASH) {
        GoalsTrashScreen(onBack = { navController.popBackStack() })
    }
}
