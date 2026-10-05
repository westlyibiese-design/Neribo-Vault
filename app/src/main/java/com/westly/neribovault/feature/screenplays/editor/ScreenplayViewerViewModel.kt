package com.westly.neribovault.feature.screenplays.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.ScriptCounts
import com.westly.neribovault.feature.screenplays.countsOf
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the read-only viewer draws. [screenplay] is null once loaded when it does not exist. */
data class ScreenplayViewerUiState(
    val isLoading: Boolean = true,
    val screenplay: ScreenplayEntity? = null,
    val blocks: List<ScriptBlock> = emptyList(),
    val counts: ScriptCounts = ScriptCounts(pages = 0, scenes = 0, characters = 0, isEmpty = true),
)

/** State and actions for the read-only screenplay viewer (replaced by the editor in part S2). */
class ScreenplayViewerViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
) : ViewModel() {

    val state: StateFlow<ScreenplayViewerUiState> = repository.observeById(screenplayId)
        .map { screenplay ->
            if (screenplay == null) {
                ScreenplayViewerUiState(isLoading = false)
            } else {
                val blocks = Fountain.parse(screenplay.content)
                ScreenplayViewerUiState(
                    isLoading = false,
                    screenplay = screenplay,
                    blocks = blocks,
                    counts = countsOf(blocks),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenplayViewerUiState())

    fun updateDetails(title: String, author: String) {
        viewModelScope.launch {
            val existing = repository.getById(screenplayId) ?: return@launch
            repository.upsert(existing.copy(title = title.trim(), author = author.trim()))
        }
    }

    /** Moves the screenplay to Recently deleted. Returns once the change is written. */
    suspend fun softDelete() {
        withContext(NonCancellable) { repository.softDelete(screenplayId) }
    }
}
