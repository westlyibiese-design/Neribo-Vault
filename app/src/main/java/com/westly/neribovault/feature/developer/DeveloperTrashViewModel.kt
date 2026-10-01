package com.westly.neribovault.feature.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.data.repository.SecretsRepository
import com.westly.neribovault.feature.developer.secrets.SecretsVault
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted projects and secrets, most recently deleted first. */
data class DeveloperTrashUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val secrets: List<SecretEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = projects.isEmpty() && secrets.isEmpty()
}

/** State and actions for the Developer "Recently deleted" screen. */
class DeveloperTrashViewModel(
    private val projects: ProjectsRepository,
    private val secrets: SecretsRepository,
) : ViewModel() {

    val state: StateFlow<DeveloperTrashUiState> = combine(
        projects.observeTrashed(),
        secrets.observeTrashed(),
    ) { deletedProjects, deletedSecrets ->
        DeveloperTrashUiState(
            projects = deletedProjects,
            secrets = deletedSecrets,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeveloperTrashUiState())

    fun restoreProject(id: String) {
        viewModelScope.launch { projects.restore(id) }
    }

    fun restoreSecret(id: String) {
        viewModelScope.launch { secrets.restore(id) }
    }

    fun deleteProjectForever(id: String) {
        viewModelScope.launch { projects.deletePermanently(id) }
    }

    fun deleteSecretForever(id: String) {
        viewModelScope.launch {
            secrets.deletePermanently(id)
            SecretsVault.logEvent("secret_deleted", id)
        }
    }

    /** Permanently deletes every project and secret that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            projects.observeTrashed().first().forEach { projects.deletePermanently(it.id) }
            secrets.observeTrashed().first().forEach { secrets.deletePermanently(it.id) }
        }
    }
}
