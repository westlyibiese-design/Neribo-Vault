package com.westly.neribovault.feature.lyrics.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.SongSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What performance mode draws: the parsed song and the printed label of every section. */
data class PerformanceUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val sections: List<SongSection> = emptyList(),
    val labels: List<String> = emptyList(),
)

/** Loads one song for performance mode. The session settings (size, speed, labels) live in the screen. */
class PerformanceViewModel(
    repository: SongsRepository,
    songId: String,
) : ViewModel() {

    val state: StateFlow<PerformanceUiState> = repository.observeById(songId)
        .map { song ->
            if (song == null) {
                PerformanceUiState(isLoading = false, notFound = true)
            } else {
                val sections = LyricsFormat.parse(song.content)
                PerformanceUiState(
                    isLoading = false,
                    sections = sections,
                    labels = sections.indices.map { LyricsFormat.labelAt(sections, it) },
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerformanceUiState())
}
