package com.westly.neribovault.feature.writers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.repository.StoriesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted stories, most recently deleted first. */
data class WritersTrashUiState(
    val stories: List<StoryEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Writers "Recently deleted" screen. */
class WritersTrashViewModel(private val repository: StoriesRepository) : ViewModel() {

    val state: StateFlow<WritersTrashUiState> = repository.observeTrashed()
        .map { stories -> WritersTrashUiState(stories = stories, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WritersTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    /** Permanently deletes the story together with its chapters, characters and notes. */
    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every story that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { story ->
                repository.deletePermanently(story.id)
            }
        }
    }
}
