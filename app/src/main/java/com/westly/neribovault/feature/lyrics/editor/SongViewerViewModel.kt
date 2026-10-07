package com.westly.neribovault.feature.lyrics.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.engine.LyricsAnalysis
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.LyricsStats
import com.westly.neribovault.feature.lyrics.engine.SongSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the read-only song viewer draws. [song] is null once loaded when the song does not exist. */
data class SongViewerUiState(
    val isLoading: Boolean = true,
    val song: SongEntity? = null,
    val sections: List<SongSection> = emptyList(),
    val labels: List<String> = emptyList(),
    val stats: LyricsStats = LyricsStats(0, 0, 0, 0, 0),
)

/** State and actions for the read-only viewer of one song. A later part replaces the screen with an editor. */
class SongViewerViewModel(
    private val repository: SongsRepository,
    private val songId: String,
) : ViewModel() {

    val state: StateFlow<SongViewerUiState> = repository.observeById(songId)
        .map { song ->
            if (song == null) {
                SongViewerUiState(isLoading = false)
            } else {
                val sections = LyricsFormat.parse(song.content)
                SongViewerUiState(
                    isLoading = false,
                    song = song,
                    sections = sections,
                    labels = sections.indices.map { LyricsFormat.labelAt(sections, it) },
                    stats = LyricsAnalysis.stats(sections),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongViewerUiState())

    fun updateDetails(title: String, writer: String) {
        viewModelScope.launch {
            val existing = repository.getById(songId) ?: return@launch
            repository.upsert(existing.copy(title = title.trim(), writer = writer.trim()))
        }
    }

    /** Moves the song to Recently deleted. Returns once the change is saved. */
    suspend fun delete() {
        repository.softDelete(songId)
    }
}
