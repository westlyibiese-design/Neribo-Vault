package com.westly.neribovault.feature.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.data.repository.IdeasRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted ideas, most recently deleted first. */
data class IdeasTrashUiState(
    val ideas: List<IdeaEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Ideas "Recently deleted" screen. */
class IdeasTrashViewModel(private val repository: IdeasRepository) : ViewModel() {

    val state: StateFlow<IdeasTrashUiState> = repository.observeTrashed()
        .map { ideas -> IdeasTrashUiState(ideas = ideas, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdeasTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every idea that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { idea ->
                repository.deletePermanently(idea.id)
            }
        }
    }
}
