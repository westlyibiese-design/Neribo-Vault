package com.westly.neribovault.feature.accounts.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import com.westly.neribovault.data.repository.AccountFieldsRepository
import com.westly.neribovault.data.repository.AccountItemsRepository
import com.westly.neribovault.data.repository.AccountsRepository
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

/** State for the read-only account viewer. */
class AccountDetailViewModel(
    private val accountId: String,
    private val accounts: AccountsRepository,
    items: AccountItemsRepository,
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
}
