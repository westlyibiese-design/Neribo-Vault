package com.westly.neribovault.feature.screenplays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.data.repository.ScreenplaysRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted screenplays, most recently deleted first. */
data class ScreenplaysTrashUiState(
    val screenplays: List<ScreenplayEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Screenplays "Recently deleted" screen. */
class ScreenplaysTrashViewModel(private val repository: ScreenplaysRepository) : ViewModel() {

    val state: StateFlow<ScreenplaysTrashUiState> = repository.observeTrashed()
        .map { list -> ScreenplaysTrashUiState(screenplays = list, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenplaysTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every screenplay that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { screenplay ->
                repository.deletePermanently(screenplay.id)
            }
        }
    }
}
