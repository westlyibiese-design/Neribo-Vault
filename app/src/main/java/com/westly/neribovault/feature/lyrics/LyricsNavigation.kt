package com.westly.neribovault.feature.lyrics

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
import com.westly.neribovault.feature.lyrics.details.SongDetailsScreen
import com.westly.neribovault.feature.lyrics.editor.SongEditorScreen
import com.westly.neribovault.feature.lyrics.performance.PerformanceScreen
import com.westly.neribovault.feature.lyrics.sheet.LyricSheetScreen
import com.westly.neribovault.feature.lyrics.tools.SongToolsScreen
import kotlinx.coroutines.launch

/** The vault id used by the vault lock, Home and Search. */
const val VAULT_ID = "lyrics"

/** The name shown on the lock screen and in the top bar. */
const val VAULT_NAME = "Lyrics"

/** Route names and argument keys for the Lyrics vault. Every route is registered by [lyricsGraph]. */
object LyricsRoutes {
    const val LIST = Routes.LYRICS
    const val EDITOR = "lyrics/edit/{songId}"
    const val DETAILS = "lyrics/details/{songId}"
    const val PERFORMANCE = "lyrics/perform/{songId}"
    const val TOOLS = "lyrics/tools/{songId}"
    const val SHEET = "lyrics/sheet/{songId}"
    const val TRASH = "lyrics/trash"
    const val ARG_ID = "songId"

    /** Key used to hand a just-deleted song id back to the list (for the Undo snackbar). */
    const val KEY_DELETED_ID = "deletedId"

    /** Key the Tools screen uses to tell the editor which section to scroll to (an Int index). */
    const val KEY_JUMP_TO_SECTION = "jumpToSection"

    fun editor(id: String) = "lyrics/edit/$id"
    fun details(id: String) = "lyrics/details/$id"
    fun performance(id: String) = "lyrics/perform/$id"
    fun tools(id: String) = "lyrics/tools/$id"
    fun sheet(id: String) = "lyrics/sheet/$id"
}

/** Registers every Lyrics destination, each behind the vault lock. */
fun NavGraphBuilder.lyricsGraph(navController: NavHostController) {
    composable(LyricsRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(LyricsRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            LyricsScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(LyricsRoutes.KEY_DELETED_ID, null)
                },
                onBack = { navController.popBackStack() },
                onOpenSong = { id ->
                    navController.navigate(LyricsRoutes.editor(id)) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(LyricsRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = LyricsRoutes.EDITOR,
        arguments = listOf(navArgument(LyricsRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(LyricsRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            val goBack = rememberBackWithDeleteHandback(navController, id)
            SongEditorScreen(
                songId = id,
                onBack = goBack,
                onOpenDetails = {
                    navController.navigate(LyricsRoutes.details(id)) { launchSingleTop = true }
                },
                onOpenPerformance = {
                    navController.navigate(LyricsRoutes.performance(id)) { launchSingleTop = true }
                },
                onOpenTools = {
                    navController.navigate(LyricsRoutes.tools(id)) { launchSingleTop = true }
                },
                onOpenSheet = {
                    navController.navigate(LyricsRoutes.sheet(id)) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = LyricsRoutes.DETAILS,
        arguments = listOf(navArgument(LyricsRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(LyricsRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            SongDetailsScreen(songId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = LyricsRoutes.PERFORMANCE,
        arguments = listOf(navArgument(LyricsRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(LyricsRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            PerformanceScreen(songId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = LyricsRoutes.TOOLS,
        arguments = listOf(navArgument(LyricsRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(LyricsRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            SongToolsScreen(
                songId = id,
                onJumpToSection = { sectionIndex ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(LyricsRoutes.KEY_JUMP_TO_SECTION, sectionIndex)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
    composable(
        route = LyricsRoutes.SHEET,
        arguments = listOf(navArgument(LyricsRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(LyricsRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            LyricSheetScreen(songId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(LyricsRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            LyricsTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * The editor's back action. The editor screen has a fixed signature with no "deleted" callback,
 * so after it moves a song to the trash and goes back, this checks the database and hands the id
 * to the list screen for the Undo snackbar. A normal back press just pops.
 */
@Composable
private fun rememberBackWithDeleteHandback(
    navController: NavHostController,
    songId: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        (context.applicationContext as NeriboApp).container.songsRepository
    }
    return {
        scope.launch {
            val deleted = repository.getById(songId)?.isDeleted == true
            if (deleted) {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(LyricsRoutes.KEY_DELETED_ID, songId)
            }
            navController.popBackStack()
        }
    }
}
