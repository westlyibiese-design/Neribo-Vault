package com.westly.neribovault.feature.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.data.repository.MemoriesRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The deleted memories, most recently deleted first. */
data class MemoriesTrashUiState(
    val memories: List<MemoryEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * State and actions for the Memories "Recently deleted" screen. Restore keeps the photos;
 * Delete forever and Empty trash remove the photo files first and then the memory.
 */
class MemoriesTrashViewModel(
    private val repository: MemoriesRepository,
    private val photoStore: MemoryPhotoStore,
) : ViewModel() {

    val state: StateFlow<MemoriesTrashUiState> = repository.observeTrashed()
        .map { memories -> MemoriesTrashUiState(memories = memories, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoriesTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                val memory = repository.getById(id)
                if (memory != null) photoStore.deleteFilesFor(memory.photoUris)
                repository.deletePermanently(id)
            }
        }
    }

    /** Permanently deletes every memory that is in the trash right now, photos included. */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                repository.observeTrashed().first().forEach { memory ->
                    photoStore.deleteFilesFor(memory.photoUris)
                    repository.deletePermanently(memory.id)
                }
            }
        }
    }
}
