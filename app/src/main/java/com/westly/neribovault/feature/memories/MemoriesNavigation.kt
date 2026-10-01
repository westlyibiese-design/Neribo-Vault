package com.westly.neribovault.feature.memories

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes

/** Route names and argument keys for the Memories vault. */
object MemoriesRoutes {
    const val LIST = Routes.MEMORIES
    const val EDITOR = "memories/edit/{memoryId}"
    const val DETAIL = "memories/detail/{memoryId}"
    const val PHOTO = "memories/photo/{memoryId}/{index}"
    const val TRASH = "memories/trash"

    /** Name of the path argument that carries a memory id. */
    const val ARG_MEMORY_ID = "memoryId"

    /** Name of the path argument in [PHOTO] that carries the photo position. */
    const val ARG_INDEX = "index"

    /** The [ARG_MEMORY_ID] value that means "create a new memory". */
    const val NEW_MEMORY_ID = "new"

    /** Key used to hand a just-deleted memory id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(memoryId: String) = "memories/edit/$memoryId"

    fun detail(memoryId: String) = "memories/detail/$memoryId"

    fun photo(memoryId: String, index: Int) = "memories/photo/$memoryId/$index"
}

/** Registers the Memories vault screens: list, detail, editor, photo viewer and Recently deleted. */
fun NavGraphBuilder.memoriesGraph(navController: NavHostController) {
    // A memory deleted from the detail screen or the editor hands its id to the list, which is
    // always further down the back stack. Everything above the list is then closed.
    val finishDelete: (String?) -> Unit = { deletedId ->
        if (deletedId != null) {
            runCatching { navController.getBackStackEntry(MemoriesRoutes.LIST) }
                .getOrNull()
                ?.savedStateHandle
                ?.set(MemoriesRoutes.KEY_DELETED_ID, deletedId)
        }
        if (!navController.popBackStack(MemoriesRoutes.LIST, inclusive = false)) {
            navController.popBackStack()
        }
    }

    composable(MemoriesRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(MemoriesRoutes.KEY_DELETED_ID, null)
        }
        MemoriesScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(MemoriesRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenMemory = { id ->
                navController.navigate(MemoriesRoutes.detail(id)) { launchSingleTop = true }
            },
            onEditMemory = { id ->
                navController.navigate(MemoriesRoutes.editor(id)) { launchSingleTop = true }
            },
            onNewMemory = {
                navController.navigate(MemoriesRoutes.editor(MemoriesRoutes.NEW_MEMORY_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenTrash = {
                navController.navigate(MemoriesRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = MemoriesRoutes.DETAIL,
        arguments = listOf(
            navArgument(MemoriesRoutes.ARG_MEMORY_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val memoryId = entry.arguments?.getString(MemoriesRoutes.ARG_MEMORY_ID).orEmpty()
        MemoryDetailScreen(
            memoryId = memoryId,
            onBack = { navController.popBackStack() },
            onEdit = {
                navController.navigate(MemoriesRoutes.editor(memoryId)) { launchSingleTop = true }
            },
            onOpenPhoto = { index ->
                navController.navigate(MemoriesRoutes.photo(memoryId, index)) {
                    launchSingleTop = true
                }
            },
            onDeleted = finishDelete,
        )
    }
    composable(
        route = MemoriesRoutes.EDITOR,
        arguments = listOf(
            navArgument(MemoriesRoutes.ARG_MEMORY_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val memoryId = entry.arguments?.getString(MemoriesRoutes.ARG_MEMORY_ID)
            ?: MemoriesRoutes.NEW_MEMORY_ID
        MemoryEditorScreen(
            memoryId = memoryId,
            onBack = { navController.popBackStack() },
            onDeleted = finishDelete,
        )
    }
    composable(
        route = MemoriesRoutes.PHOTO,
        arguments = listOf(
            navArgument(MemoriesRoutes.ARG_MEMORY_ID) { type = NavType.StringType },
            navArgument(MemoriesRoutes.ARG_INDEX) { type = NavType.IntType },
        ),
    ) { entry ->
        val memoryId = entry.arguments?.getString(MemoriesRoutes.ARG_MEMORY_ID).orEmpty()
        val index = entry.arguments?.getInt(MemoriesRoutes.ARG_INDEX) ?: 0
        PhotoViewerScreen(
            memoryId = memoryId,
            startIndex = index,
            onBack = { navController.popBackStack() },
        )
    }
    composable(MemoriesRoutes.TRASH) {
        MemoriesTrashScreen(onBack = { navController.popBackStack() })
    }
}
