package com.westly.neribovault.feature.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.data.repository.DiaryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted diary entries, most recently deleted first. */
data class DiaryTrashUiState(
    val entries: List<DiaryEntryEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Diary "Recently deleted" screen. */
class DiaryTrashViewModel(private val repository: DiaryRepository) : ViewModel() {

    val state: StateFlow<DiaryTrashUiState> = repository.observeTrashed()
        .map { entries -> DiaryTrashUiState(entries = entries, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every entry that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { entry ->
                repository.deletePermanently(entry.id)
            }
        }
    }
}
