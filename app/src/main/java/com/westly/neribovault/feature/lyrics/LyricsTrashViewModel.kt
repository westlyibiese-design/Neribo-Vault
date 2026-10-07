package com.westly.neribovault.feature.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted songs, most recently deleted first. */
data class LyricsTrashUiState(
    val songs: List<SongEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Lyrics "Recently deleted" screen. */
class LyricsTrashViewModel(private val repository: SongsRepository) : ViewModel() {

    val state: StateFlow<LyricsTrashUiState> = repository.observeTrashed()
        .map { list -> LyricsTrashUiState(songs = list, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id) }
    }

    /** Permanently deletes every song that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { song ->
                repository.deletePermanently(song.id)
            }
        }
    }
}
