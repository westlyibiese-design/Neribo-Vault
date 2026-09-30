package com.westly.neribovault.feature.diary

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Diary vault. */
object DiaryRoutes {
    const val LIST = Routes.DIARY
    const val ENTRY = "diary/entry/{entryId}"
    const val TRASH = "diary/trash"

    /** Name of the path argument in [ENTRY]. */
    const val ARG_ENTRY_ID = "entryId"

    /**
     * The [ARG_ENTRY_ID] value that means "create a new entry". It may carry the day the entry is
     * for, as `new-<startOfDayMillis>`. Real ids are UUIDs, which can never start with "n".
     */
    const val NEW_ENTRY_ID = "new"

    /** Key the entry screen uses to hand a just-deleted entry id back to the list. */
    const val KEY_DELETED_ID = "deletedId"

    fun entry(id: String) = "diary/entry/$id"

    /** Route for a new entry, optionally for a chosen day (start-of-day millis). */
    fun newEntry(dateMillis: Long?) =
        entry(if (dateMillis == null) NEW_ENTRY_ID else "$NEW_ENTRY_ID-$dateMillis")
}

private const val VAULT_ID = "diary"
private const val VAULT_NAME = "Diary"

/** Registers the Diary vault screens (list, entry, Recently deleted), each behind the vault lock. */
fun NavGraphBuilder.diaryGraph(navController: NavHostController) {
    composable(DiaryRoutes.LIST) { backStackEntry ->
        val deletedIdFlow = remember(backStackEntry) {
            backStackEntry.savedStateHandle.getStateFlow<String?>(DiaryRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            DiaryScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    backStackEntry.savedStateHandle.set<String?>(DiaryRoutes.KEY_DELETED_ID, null)
                },
                onBack = { navController.popBackStack() },
                onOpenEntry = { id ->
                    navController.navigate(DiaryRoutes.entry(id)) { launchSingleTop = true }
                },
                onNewEntry = { dateMillis ->
                    navController.navigate(DiaryRoutes.newEntry(dateMillis)) {
                        launchSingleTop = true
                    }
                },
                onOpenTrash = {
                    navController.navigate(DiaryRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = DiaryRoutes.ENTRY,
        arguments = listOf(navArgument(DiaryRoutes.ARG_ENTRY_ID) { type = NavType.StringType }),
    ) { backStackEntry ->
        val entryId = backStackEntry.arguments?.getString(DiaryRoutes.ARG_ENTRY_ID)
            ?: DiaryRoutes.NEW_ENTRY_ID
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            DiaryEntryScreen(
                entryId = entryId,
                onBack = { navController.popBackStack() },
                onDeleted = { deletedId ->
                    if (deletedId != null) {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(DiaryRoutes.KEY_DELETED_ID, deletedId)
                    }
                    navController.popBackStack()
                },
            )
        }
    }
    composable(DiaryRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            DiaryTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}
