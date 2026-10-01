package com.westly.neribovault.feature.developer.secrets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.AuditLogEntity
import com.westly.neribovault.data.repository.AuditRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The recent secrets activity, newest first. Entries never carry labels or values. */
data class SecretActivityUiState(
    val entries: List<AuditLogEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Secret activity screen. */
class SecretActivityViewModel(private val repository: AuditRepository) : ViewModel() {

    val state: StateFlow<SecretActivityUiState> = repository.observeRecent(limit = 200)
        .map { all ->
            SecretActivityUiState(
                entries = all.filter { it.entityType == "secret" },
                isLoading = false,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecretActivityUiState())

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}

/** Friendly text for an audit action. Says nothing beyond what happened. */
fun activityText(action: String): String = when (action) {
    "secret_created" -> "A secret was added"
    "secret_revealed" -> "A secret was revealed"
    "secret_copied" -> "A secret was copied"
    "secret_updated" -> "A secret was edited"
    "secret_deleted" -> "A secret was deleted"
    "pin_changed" -> "Secrets PIN settings changed"
    "decoy_pin_set" -> "Secrets PIN settings changed"
    "secrets_unlock_failed" -> "A wrong PIN was entered"
    else -> "Secrets activity"
}
