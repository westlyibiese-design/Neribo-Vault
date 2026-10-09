package com.westly.neribovault.feature.accounts.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.data.repository.AccountFieldsRepository
import com.westly.neribovault.data.repository.AccountItemsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [account] is null once it is missing or deleted (and [isLoading] is false). */
data class AccountDetailUiState(
    val account: AccountEntity? = null,
    val items: List<AccountItemEntity> = emptyList(),
    val fields: List<AccountFieldEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * State for the account viewer. It never holds a decrypted value: revealing and copying happen
 * in the screen, which asks [AccountsVault] for the text at that moment.
 */
class AccountDetailViewModel(
    private val accountId: String,
    private val accounts: AccountsRepository,
    private val items: AccountItemsRepository,
    fields: AccountFieldsRepository,
) : ViewModel() {

    val state: StateFlow<AccountDetailUiState> = combine(
        accounts.observeById(accountId),
        items.observeForAccount(accountId),
        fields.observeForOwner("account", accountId),
    ) { account, accountItems, accountFields ->
        AccountDetailUiState(
            account = account,
            items = accountItems,
            fields = accountFields,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailUiState())

    /** Moves the account to the trash, then calls [onDone] so the screen can go back. */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            accounts.softDelete(accountId)
            onDone()
        }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { accounts.setPinned(accountId, pinned) }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { items.softDelete(itemId) }
    }

    fun restoreItem(itemId: String) {
        viewModelScope.launch { items.restore(itemId) }
    }

    /** Writes an audit entry (never a label or a value). */
    fun log(action: String, entityId: String?) {
        viewModelScope.launch { AccountsVault.logEvent(action, entityId) }
    }
}
