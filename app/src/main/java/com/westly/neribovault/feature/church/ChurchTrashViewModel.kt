package com.westly.neribovault.feature.church

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.data.repository.ChurchRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted records, most recently deleted first. */
data class ChurchTrashUiState(
    val records: List<ChurchRecordEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Church "Recently deleted" screen. */
class ChurchTrashViewModel(private val repository: ChurchRepository) : ViewModel() {

    val state: StateFlow<ChurchTrashUiState> = repository.observeTrashed()
        .map { records -> ChurchTrashUiState(records = records, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChurchTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every record that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { record ->
                repository.deletePermanently(record.id)
            }
        }
    }
}
