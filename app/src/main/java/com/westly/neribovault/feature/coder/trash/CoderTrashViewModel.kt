package com.westly.neribovault.feature.coder.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted projects, most recently deleted first. */
data class CoderTrashUiState(
    val projects: List<CoderProjectEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = projects.isEmpty()
}

/** State and actions for the Coder "Recently deleted" screen. */
class CoderTrashViewModel(
    private val projects: CoderProjectsRepository,
) : ViewModel() {

    val state: StateFlow<CoderTrashUiState> = projects.observeTrashed()
        .map { CoderTrashUiState(projects = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoderTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { projects.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { projects.deletePermanently(id) }
    }

    /** Permanently deletes everything that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            projects.observeTrashed().first().forEach { projects.deletePermanently(it.id) }
        }
    }
}
