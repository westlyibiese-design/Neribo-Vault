package com.westly.neribovault.feature.authenticator

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
import com.westly.neribovault.feature.authenticator.backup.AuthenticatorBackupScreen
import com.westly.neribovault.feature.authenticator.editor.TotpEditorScreen
import com.westly.neribovault.feature.authenticator.security.AuthenticatorSecureWindowEffect
import kotlinx.coroutines.launch

/** The vault id used by the vault lock. */
const val VAULT_ID = "authenticator"

/** The name shown on the lock screen and in the top bar. */
const val VAULT_NAME = "Authenticator"

/** Route names and argument keys for the Authenticator vault. Every route is registered by [authenticatorGraph]. */
object AuthenticatorRoutes {
    const val LIST = Routes.AUTHENTICATOR
    const val EDITOR = "authenticator/edit/{accountId}"
    const val BACKUP = "authenticator/backup"
    const val TRASH = "authenticator/trash"
    const val ARG_ID = "accountId"
    const val KEY_DELETED_ID = "deletedId"

    fun editor(id: String) = "authenticator/edit/$id"
}

/** Registers every Authenticator destination, each behind the vault lock and with screenshots blocked. */
fun NavGraphBuilder.authenticatorGraph(navController: NavHostController) {
    composable(AuthenticatorRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(AuthenticatorRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AuthenticatorSecureWindowEffect(true)
            AuthenticatorScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(AuthenticatorRoutes.KEY_DELETED_ID, null)
                },
                onBack = { navController.popBackStack() },
                onOpenEditor = { id ->
                    navController.navigate(AuthenticatorRoutes.editor(id)) { launchSingleTop = true }
                },
                onOpenBackup = {
                    navController.navigate(AuthenticatorRoutes.BACKUP) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(AuthenticatorRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = AuthenticatorRoutes.EDITOR,
        arguments = listOf(navArgument(AuthenticatorRoutes.ARG_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(AuthenticatorRoutes.ARG_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AuthenticatorSecureWindowEffect(true)
            val goBack = rememberBackWithDeleteHandback(navController, id)
            TotpEditorScreen(accountId = id, onBack = goBack)
        }
    }
    composable(AuthenticatorRoutes.BACKUP) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AuthenticatorSecureWindowEffect(true)
            AuthenticatorBackupScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(AuthenticatorRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AuthenticatorSecureWindowEffect(true)
            AuthenticatorTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * The editor's back action. When the account was deleted from the editor, this hands its id to the
 * list screen for the Undo snackbar before going back. A normal back press just pops.
 */
@Composable
private fun rememberBackWithDeleteHandback(
    navController: NavHostController,
    accountId: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        (context.applicationContext as NeriboApp).container.totpAccountsRepository
    }
    return {
        scope.launch {
            val deleted = accountId != "new" && repository.getById(accountId)?.isDeleted == true
            if (deleted) {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(AuthenticatorRoutes.KEY_DELETED_ID, accountId)
            }
            navController.popBackStack()
        }
    }
}
