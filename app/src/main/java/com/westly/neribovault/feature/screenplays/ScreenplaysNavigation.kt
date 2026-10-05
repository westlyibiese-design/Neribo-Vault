package com.westly.neribovault.feature.screenplays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.screenplays.editor.ScreenplayEditorScreen
import com.westly.neribovault.feature.screenplays.export.PreviewScreen
import com.westly.neribovault.feature.screenplays.tools.ScenesScreen
import com.westly.neribovault.feature.screenplays.tools.ScriptStatsScreen
import com.westly.neribovault.feature.screenplays.tools.TitlePageScreen
import kotlinx.coroutines.launch

/** The vault id used by the vault lock, Home and Search. */
const val VAULT_ID = "screenplays"

/** The name shown on the lock screen and in the top bar. */
const val VAULT_NAME = "Screenplays"

/** Route names and argument keys for the Screenplays vault. Every route is registered by [screenplaysGraph]. */
object ScreenplaysRoutes {
    const val LIST = Routes.SCREENPLAYS
    const val EDITOR = "screenplays/edit/{screenplayId}"
    const val TITLE_PAGE = "screenplays/title/{screenplayId}"
    const val SCENES = "screenplays/scenes/{screenplayId}"
    const val STATS = "screenplays/stats/{screenplayId}"
    const val PREVIEW = "screenplays/preview/{screenplayId}"
    const val TRASH = "screenplays/trash"
    const val ARG_ID = "screenplayId"

    /** Key used to hand a just-deleted screenplay id back to the list (for the Undo snackbar). */
    const val KEY_DELETED_ID = "deletedId"

    /** Key the Scenes screen uses to tell the editor which block to scroll to (an Int index). */
    const val KEY_JUMP_TO_BLOCK = "jumpToBlock"

    fun editor(id: String) = "screenplays/edit/$id"
    fun titlePage(id: String) = "screenplays/title/$id"
    fun scenes(id: String) = "screenplays/scenes/$id"
    fun stats(id: String) = "screenplays/stats/$id"
    fun preview(id: String) = "screenplays/preview/$id"
}

/** Registers every Screenplays destination, each behind the vault lock. */
fun NavGraphBuilder.screenplaysGraph(navController: NavHostController) {
    composable(ScreenplaysRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(ScreenplaysRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            ScreenplaysScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(ScreenplaysRoutes.KEY_DELETED_ID, null)
                },
                onBack = { navController.popBackStack() },
                onOpenScreenplay = { id ->
                    navController.navigate(ScreenplaysRoutes.editor(id)) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(ScreenplaysRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = ScreenplaysRoutes.EDITOR,
        arguments = listOf(navArgument(ScreenplaysRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(ScreenplaysRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            val goBack = rememberBackWithDeleteHandback(navController, id)
            ScreenplayEditorScreen(
                screenplayId = id,
                onBack = goBack,
                onOpenTitlePage = {
                    navController.navigate(ScreenplaysRoutes.titlePage(id)) { launchSingleTop = true }
                },
                onOpenScenes = {
                    navController.navigate(ScreenplaysRoutes.scenes(id)) { launchSingleTop = true }
                },
                onOpenStats = {
                    navController.navigate(ScreenplaysRoutes.stats(id)) { launchSingleTop = true }
                },
                onOpenPreview = {
                    navController.navigate(ScreenplaysRoutes.preview(id)) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = ScreenplaysRoutes.TITLE_PAGE,
        arguments = listOf(navArgument(ScreenplaysRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(ScreenplaysRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            TitlePageScreen(screenplayId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = ScreenplaysRoutes.SCENES,
        arguments = listOf(navArgument(ScreenplaysRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(ScreenplaysRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            ScenesScreen(
                screenplayId = id,
                onJumpToBlock = { blockIndex ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(ScreenplaysRoutes.KEY_JUMP_TO_BLOCK, blockIndex)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    composable(
        route = ScreenplaysRoutes.STATS,
        arguments = listOf(navArgument(ScreenplaysRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(ScreenplaysRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            ScriptStatsScreen(screenplayId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = ScreenplaysRoutes.PREVIEW,
        arguments = listOf(navArgument(ScreenplaysRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(ScreenplaysRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            PreviewScreen(screenplayId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(ScreenplaysRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            ScreenplaysTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * The editor's back action. The editor screen has a fixed signature with no "deleted" callback,
 * so after it moves a screenplay to the trash and goes back, this checks the database and hands
 * the id to the list screen for the Undo snackbar. A normal back press just pops.
 */
@Composable
private fun rememberBackWithDeleteHandback(
    navController: NavHostController,
    screenplayId: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        (context.applicationContext as NeriboApp).container.screenplaysRepository
    }
    return {
        scope.launch {
            val deleted = repository.getById(screenplayId)?.isDeleted == true
            if (deleted) {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(ScreenplaysRoutes.KEY_DELETED_ID, screenplayId)
            }
            navController.popBackStack()
        }
    }
}
