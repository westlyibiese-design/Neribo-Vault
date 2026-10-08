package com.westly.neribovault.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.data.repository.AccountItemsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A deleted item together with the name of the account it belonged to. */
data class TrashedItemRow(val item: AccountItemEntity, val accountName: String)

/** The deleted accounts and deleted items, most recently deleted first. */
data class AccountsTrashUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val items: List<TrashedItemRow> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = accounts.isEmpty() && items.isEmpty()
}

/** State and actions for the Accounts "Recently deleted" screen. */
class AccountsTrashViewModel(
    private val accounts: AccountsRepository,
    private val items: AccountItemsRepository,
) : ViewModel() {

    val state: StateFlow<AccountsTrashUiState> = combine(
        accounts.observeTrashed(),
        items.observeTrashed(),
    ) { trashedAccounts, trashedItems ->
        AccountsTrashUiState(
            accounts = trashedAccounts,
            items = trashedItems.map { item ->
                val name = accounts.getById(item.accountId)?.name?.trim().orEmpty()
                TrashedItemRow(item, name.ifEmpty { UNTITLED_ACCOUNT })
            },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsTrashUiState())

    fun restoreAccount(id: String) {
        viewModelScope.launch { accounts.restore(id) }
    }

    fun restoreItem(id: String) {
        viewModelScope.launch { items.restore(id) }
    }

    /** Also removes the account's items and every field owned by it or by those items. */
    fun deleteAccountForever(id: String) {
        viewModelScope.launch { accounts.deletePermanently(id) }
    }

    fun deleteItemForever(id: String) {
        viewModelScope.launch { items.deletePermanently(id) }
    }

    /** Permanently deletes everything that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            items.observeTrashed().first().forEach { items.deletePermanently(it.id) }
            accounts.observeTrashed().first().forEach { accounts.deletePermanently(it.id) }
        }
    }
}
