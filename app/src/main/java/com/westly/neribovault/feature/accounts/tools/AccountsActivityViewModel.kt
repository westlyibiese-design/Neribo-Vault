package com.westly.neribovault.feature.accounts.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.AuditRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val ACCOUNT_ENTITY_TYPE = "account"
private const val RECENT_LIMIT = 100

/** One line of the activity list: the friendly [text] and when it happened. No ids, names or values. */
data class ActivityRow(val id: String, val text: String, val createdAt: Long)

/** The Accounts entries of the audit log, newest first. */
data class AccountsActivityUiState(
    val rows: List<ActivityRow> = emptyList(),
    val isLoading: Boolean = true,
)

/** State for the Accounts activity screen. */
class AccountsActivityViewModel(audit: AuditRepository) : ViewModel() {
    val state: StateFlow<AccountsActivityUiState> = audit.observeRecent(RECENT_LIMIT)
        .map { entries ->
            AccountsActivityUiState(
                rows = entries
                    .filter { it.entityType == ACCOUNT_ENTITY_TYPE }
                    .sortedByDescending { it.createdAt }
                    .map { ActivityRow(it.id, activityText(it.action), it.createdAt) },
                isLoading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsActivityUiState())
}
