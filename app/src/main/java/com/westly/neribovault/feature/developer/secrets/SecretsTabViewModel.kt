package com.westly.neribovault.feature.developer.secrets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.data.repository.SecretsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The secrets of one project, and which of them are currently revealed. Never holds a value. */
data class SecretsTabUiState(
    val secrets: List<SecretEntity> = emptyList(),
    val revealedIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)

/** State and actions for the Secrets tab of one project. */
class SecretsTabViewModel(
    projectId: String,
    private val repository: SecretsRepository,
) : ViewModel() {

    private val revealed = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<SecretsTabUiState> = combine(
        repository.observeByProject(projectId),
        revealed,
    ) { secrets, revealedIds ->
        SecretsTabUiState(secrets = secrets, revealedIds = revealedIds, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecretsTabUiState())

    init {
        // Everything masks again the moment the session ends.
        viewModelScope.launch {
            SecretsVault.mode.collect { mode ->
                if (mode == SessionMode.Locked) revealed.value = emptySet()
            }
        }
    }

    /** Marks [secret] as revealed and writes the audit entry. */
    fun reveal(secretId: String) {
        revealed.update { it + secretId }
        viewModelScope.launch { SecretsVault.logEvent("secret_revealed", secretId) }
    }

    fun hide(secretId: String) {
        revealed.update { it - secretId }
    }

    fun logCopied(secretId: String) {
        viewModelScope.launch { SecretsVault.logEvent("secret_copied", secretId) }
    }

    /** Soft-deletes a secret. Its ciphertext is kept so Restore works. */
    fun delete(secretId: String) {
        revealed.update { it - secretId }
        viewModelScope.launch {
            repository.softDelete(secretId)
            SecretsVault.logEvent("secret_deleted", secretId)
        }
    }

    fun restore(secretId: String) {
        viewModelScope.launch { repository.restore(secretId) }
    }
}
