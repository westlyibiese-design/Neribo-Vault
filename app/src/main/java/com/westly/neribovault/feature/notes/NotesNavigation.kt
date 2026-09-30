package com.westly.neribovault.feature.notes

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Notes vault. */
object NotesRoutes {
    const val LIST = Routes.NOTES
    const val EDITOR = "notes/edit/{noteId}"
    const val TRASH = "notes/trash"

    /** Name of the path argument in [EDITOR]. */
    const val ARG_NOTE_ID = "noteId"

    /** The [ARG_NOTE_ID] value that means "create a new note". */
    const val NEW_NOTE_ID = "new"

    /** Key the editor uses to hand a just-deleted note id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(noteId: String) = "notes/edit/$noteId"
}

/** Registers the Notes vault screens: list, editor and Recently deleted. */
fun NavGraphBuilder.notesGraph(navController: NavHostController) {
    composable(NotesRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(NotesRoutes.KEY_DELETED_ID, null)
        }
        NotesScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(NotesRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenNote = { id ->
                navController.navigate(NotesRoutes.editor(id)) { launchSingleTop = true }
            },
            onNewNote = {
                navController.navigate(NotesRoutes.editor(NotesRoutes.NEW_NOTE_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(NotesRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = NotesRoutes.EDITOR,
        arguments = listOf(
            navArgument(NotesRoutes.ARG_NOTE_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val noteId = entry.arguments?.getString(NotesRoutes.ARG_NOTE_ID) ?: NotesRoutes.NEW_NOTE_ID
        NoteEditorScreen(
            noteId = noteId,
            onBack = { navController.popBackStack() },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(NotesRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
        )
    }
    composable(NotesRoutes.TRASH) {
        NotesTrashScreen(onBack = { navController.popBackStack() })
    }
}
