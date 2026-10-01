package com.westly.neribovault.feature.documents

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.lock.VaultLockGate
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.documents.viewer.AttachmentViewerScreen

/** Route names and argument keys for the Documents vault. */
object DocumentsRoutes {
    const val LIST = Routes.DOCUMENTS
    const val EDITOR = "documents/edit/{documentId}"
    const val DETAIL = "documents/detail/{documentId}"
    const val VIEWER = "documents/viewer/{documentId}"
    const val TRASH = "documents/trash"

    /** Name of the path argument that carries a document id. */
    const val ARG_DOCUMENT_ID = "documentId"

    /** The [ARG_DOCUMENT_ID] value that means "create a new document". */
    const val NEW_DOCUMENT_ID = "new"

    /** Key used to hand a just-deleted document id back to the list screen. */
    const val KEY_DELETED_ID = "deletedId"

    fun editor(documentId: String) = "documents/edit/$documentId"

    fun detail(documentId: String) = "documents/detail/$documentId"

    fun viewer(documentId: String) = "documents/viewer/$documentId"
}

private const val VAULT_ID = "documents"
private const val VAULT_NAME = "Documents"

/**
 * Registers the Documents vault screens: list, detail, attachment viewer, editor and Recently
 * deleted. Every destination sits behind the Documents lock. Replaces the earlier placeholder.
 */
fun NavGraphBuilder.documentsGraph(navController: NavHostController) {
    val goBack: () -> Unit = { navController.popBackStack() }

    // A document deleted from the detail screen or the editor hands its id to the list, which is
    // always further down the back stack. Everything above the list is then closed.
    val finishDelete: (String?) -> Unit = { deletedId ->
        if (deletedId != null) {
            runCatching { navController.getBackStackEntry(DocumentsRoutes.LIST) }
                .getOrNull()
                ?.savedStateHandle
                ?.set(DocumentsRoutes.KEY_DELETED_ID, deletedId)
        }
        if (!navController.popBackStack(DocumentsRoutes.LIST, inclusive = false)) {
            navController.popBackStack()
        }
    }

    composable(DocumentsRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(DocumentsRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = goBack) {
            DocumentsScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(DocumentsRoutes.KEY_DELETED_ID, null)
                },
                onBack = goBack,
                onOpenDocument = { id ->
                    navController.navigate(DocumentsRoutes.detail(id)) { launchSingleTop = true }
                },
                onEditDocument = { id ->
                    navController.navigate(DocumentsRoutes.editor(id)) { launchSingleTop = true }
                },
                onNewDocument = {
                    navController.navigate(DocumentsRoutes.editor(DocumentsRoutes.NEW_DOCUMENT_ID)) {
                        launchSingleTop = true
                    }
                },
                onOpenTrash = {
                    navController.navigate(DocumentsRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = DocumentsRoutes.DETAIL,
        arguments = listOf(
            navArgument(DocumentsRoutes.ARG_DOCUMENT_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val documentId = entry.arguments?.getString(DocumentsRoutes.ARG_DOCUMENT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = goBack) {
            DocumentDetailScreen(
                documentId = documentId,
                onBack = goBack,
                onEdit = {
                    navController.navigate(DocumentsRoutes.editor(documentId)) { launchSingleTop = true }
                },
                onOpenAttachment = {
                    navController.navigate(DocumentsRoutes.viewer(documentId)) { launchSingleTop = true }
                },
                onDeleted = finishDelete,
            )
        }
    }
    composable(
        route = DocumentsRoutes.VIEWER,
        arguments = listOf(
            navArgument(DocumentsRoutes.ARG_DOCUMENT_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val documentId = entry.arguments?.getString(DocumentsRoutes.ARG_DOCUMENT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = goBack) {
            AttachmentViewerScreen(documentId = documentId, onBack = goBack)
        }
    }
    composable(
        route = DocumentsRoutes.EDITOR,
        arguments = listOf(
            navArgument(DocumentsRoutes.ARG_DOCUMENT_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val documentId = entry.arguments?.getString(DocumentsRoutes.ARG_DOCUMENT_ID)
            ?: DocumentsRoutes.NEW_DOCUMENT_ID
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = goBack) {
            DocumentEditorScreen(
                documentId = documentId,
                onBack = goBack,
                onDeleted = finishDelete,
            )
        }
    }
    composable(DocumentsRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = goBack) {
            DocumentsTrashScreen(onBack = goBack)
        }
    }
}
