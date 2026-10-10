package com.westly.neribovault.feature.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.data.repository.TotpAccountsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted accounts, most recently deleted first. */
data class AuthenticatorTrashUiState(
    val accounts: List<TotpAccountEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = accounts.isEmpty()
}

/** State and actions for the Authenticator "Recently deleted" screen. */
class AuthenticatorTrashViewModel(
    private val accounts: TotpAccountsRepository,
) : ViewModel() {

    val state: StateFlow<AuthenticatorTrashUiState> = accounts.observeTrashed()
        .map { AuthenticatorTrashUiState(accounts = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthenticatorTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { accounts.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { accounts.deletePermanently(id) }
    }

    /** Permanently deletes everything that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            accounts.observeTrashed().first().forEach { accounts.deletePermanently(it.id) }
        }
    }
}
