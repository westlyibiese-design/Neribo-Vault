package com.westly.neribovault.feature.screenplays.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.engine.Fountain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Script stats screen draws. [stats] is null while loading or when the script is missing. */
data class ScriptStatsUiState(
    val isLoading: Boolean = true,
    val stats: ScriptStats? = null,
    val updatedAt: Long = 0L,
)

/** Measures the script off the main thread whenever it changes. */
class ScriptStatsViewModel(
    repository: ScreenplaysRepository,
    screenplayId: String,
) : ViewModel() {

    val state: StateFlow<ScriptStatsUiState> = repository.observeById(screenplayId)
        .map { entity ->
            if (entity == null) {
                ScriptStatsUiState(isLoading = false)
            } else {
                ScriptStatsUiState(
                    isLoading = false,
                    stats = ScriptAnalysis.analyze(Fountain.parse(entity.content)),
                    updatedAt = entity.updatedAt,
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScriptStatsUiState())
}
