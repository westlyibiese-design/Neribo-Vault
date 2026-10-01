package com.westly.neribovault.feature.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.data.repository.MemoriesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the memory detail screen (and the photo viewer) draws. */
data class MemoryDetailUiState(
    val memory: MemoryEntity? = null,
    val isLoading: Boolean = true,
    /** True once we know the memory is missing or in the trash. */
    val notFound: Boolean = false,
)

/** State and actions for reading one memory. */
class MemoryDetailViewModel(
    private val memoryId: String,
    private val repository: MemoriesRepository,
) : ViewModel() {

    val state: StateFlow<MemoryDetailUiState> = repository.observeById(memoryId)
        .map { memory ->
            MemoryDetailUiState(memory = memory, isLoading = false, notFound = memory == null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryDetailUiState())

    /** Soft-deletes the memory (its photos stay on disk for Restore), then reports its id. */
    fun delete(onDone: (deletedId: String) -> Unit) {
        viewModelScope.launch {
            repository.softDelete(memoryId)
            onDone(memoryId)
        }
    }
}
