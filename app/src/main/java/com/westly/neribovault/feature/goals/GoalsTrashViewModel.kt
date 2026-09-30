package com.westly.neribovault.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.repository.GoalsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted goals, most recently deleted first. */
data class GoalsTrashUiState(
    val goals: List<GoalEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Goals "Recently deleted" screen. */
class GoalsTrashViewModel(private val repository: GoalsRepository) : ViewModel() {

    val state: StateFlow<GoalsTrashUiState> = repository.observeTrashed()
        .map { goals -> GoalsTrashUiState(goals = goals, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    /** Removes the goal and all of its steps for good. */
    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every goal that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { goal ->
                repository.deletePermanently(goal.id)
            }
        }
    }
}
