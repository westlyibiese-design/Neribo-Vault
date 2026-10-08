package com.westly.neribovault.feature.accounts

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
import com.westly.neribovault.feature.accounts.detail.AccountDetailScreen
import com.westly.neribovault.feature.accounts.editor.AccountEditorScreen
import com.westly.neribovault.feature.accounts.editor.ItemEditorScreen
import com.westly.neribovault.feature.accounts.security.AccountsSecurityScreen
import com.westly.neribovault.feature.accounts.tools.AccountsActivityScreen
import com.westly.neribovault.feature.accounts.tools.PasswordHealthScreen
import kotlinx.coroutines.launch

/** The vault id used by the vault lock, Home and Search. */
const val VAULT_ID = "accounts"

/** The name shown on the lock screen and in the top bar. */
const val VAULT_NAME = "Accounts"

/** Route names and argument keys for the Accounts vault. Every route is registered by [accountsGraph]. */
object AccountsRoutes {
    const val LIST = Routes.ACCOUNTS
    const val DETAIL = "accounts/detail/{accountId}"
    const val EDITOR = "accounts/edit/{accountId}"
    const val ITEM = "accounts/item/{accountId}/{itemId}"
    const val SECURITY = "accounts/security"
    const val HEALTH = "accounts/health"
    const val ACTIVITY = "accounts/activity"
    const val TRASH = "accounts/trash"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_ITEM_ID = "itemId"

    /** Key used to hand a just-deleted account id back to the list (for the Undo snackbar). */
    const val KEY_DELETED_ID = "deletedId"

    fun detail(id: String) = "accounts/detail/$id"
    fun editor(id: String) = "accounts/edit/$id"
    fun item(accountId: String, itemId: String) = "accounts/item/$accountId/$itemId"
}

/** Registers every Accounts destination, each behind the vault lock. */
fun NavGraphBuilder.accountsGraph(navController: NavHostController) {
    composable(AccountsRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(AccountsRoutes.KEY_DELETED_ID, null)
        }
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AccountsScreen(
                deletedIdFlow = deletedIdFlow,
                onDeletedIdConsumed = {
                    entry.savedStateHandle.set<String?>(AccountsRoutes.KEY_DELETED_ID, null)
                },
                onBack = { navController.popBackStack() },
                onOpenAccount = { id ->
                    navController.navigate(AccountsRoutes.detail(id)) { launchSingleTop = true }
                },
                onOpenSecurity = {
                    navController.navigate(AccountsRoutes.SECURITY) { launchSingleTop = true }
                },
                onOpenHealth = {
                    navController.navigate(AccountsRoutes.HEALTH) { launchSingleTop = true }
                },
                onOpenActivity = {
                    navController.navigate(AccountsRoutes.ACTIVITY) { launchSingleTop = true }
                },
                onOpenTrash = {
                    navController.navigate(AccountsRoutes.TRASH) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = AccountsRoutes.DETAIL,
        arguments = listOf(navArgument(AccountsRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(AccountsRoutes.ARG_ACCOUNT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            val goBack = rememberBackWithDeleteHandback(navController, id)
            AccountDetailScreen(
                accountId = id,
                onBack = goBack,
                onEdit = {
                    navController.navigate(AccountsRoutes.editor(id)) { launchSingleTop = true }
                },
                onOpenItem = { itemId ->
                    navController.navigate(AccountsRoutes.item(id, itemId)) { launchSingleTop = true }
                },
                onAddItem = {
                    navController.navigate(AccountsRoutes.item(id, "new")) { launchSingleTop = true }
                },
            )
        }
    }
    composable(
        route = AccountsRoutes.EDITOR,
        arguments = listOf(navArgument(AccountsRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType }),
    ) { entry ->
        val id = entry.arguments?.getString(AccountsRoutes.ARG_ACCOUNT_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AccountEditorScreen(accountId = id, onBack = { navController.popBackStack() })
        }
    }
    composable(
        route = AccountsRoutes.ITEM,
        arguments = listOf(
            navArgument(AccountsRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType },
            navArgument(AccountsRoutes.ARG_ITEM_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val accountId = entry.arguments?.getString(AccountsRoutes.ARG_ACCOUNT_ID).orEmpty()
        val itemId = entry.arguments?.getString(AccountsRoutes.ARG_ITEM_ID).orEmpty()
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            ItemEditorScreen(
                accountId = accountId,
                itemId = itemId,
                onBack = { navController.popBackStack() },
            )
        }
    }
    composable(AccountsRoutes.SECURITY) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AccountsSecurityScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(AccountsRoutes.HEALTH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            PasswordHealthScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(AccountsRoutes.ACTIVITY) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AccountsActivityScreen(onBack = { navController.popBackStack() })
        }
    }
    composable(AccountsRoutes.TRASH) {
        VaultLockGate(VAULT_ID, VAULT_NAME, onBack = { navController.popBackStack() }) {
            AccountsTrashScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * The detail screen's back action. After the viewer moves the account to the trash and goes back,
 * this checks the database and hands the id to the list screen for the Undo snackbar. A normal
 * back press just pops.
 */
@Composable
private fun rememberBackWithDeleteHandback(
    navController: NavHostController,
    accountId: String,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        (context.applicationContext as NeriboApp).container.accountsRepository
    }
    return {
        scope.launch {
            val deleted = repository.getById(accountId)?.isDeleted == true
            if (deleted) {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(AccountsRoutes.KEY_DELETED_ID, accountId)
            }
            navController.popBackStack()
        }
    }
}
