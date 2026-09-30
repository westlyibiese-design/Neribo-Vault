package com.westly.neribovault.feature.writers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.repository.StoriesRepository
import com.westly.neribovault.data.repository.StoryStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The status chips above the stories list. */
enum class StoryFilter(val label: String, val status: String?) {
    All("All", null),
    Idea("Idea", "idea"),
    Drafting("Drafting", "drafting"),
    Revising("Revising", "revising"),
    Complete("Complete", "complete"),
}

/** Everything the stories list screen draws. */
data class WritersUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val filter: StoryFilter = StoryFilter.All,
    val stories: List<StoryEntity> = emptyList(),
    val stats: Map<String, StoryStats> = emptyMap(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = stories.isEmpty()
    val isFiltering: Boolean get() = query.isNotBlank() || filter != StoryFilter.All
}

private data class StoryControls(
    val query: String,
    val isSearchOpen: Boolean,
    val filter: StoryFilter,
)

/** State and actions for the stories list. */
@OptIn(ExperimentalCoroutinesApi::class)
class WritersViewModel(private val repository: StoriesRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val filterFlow = MutableStateFlow(StoryFilter.All)

    private val controls: Flow<StoryControls> = combine(
        queryFlow,
        searchOpenFlow,
        filterFlow,
    ) { query, open, filter ->
        StoryControls(query = query, isSearchOpen = open, filter = filter)
    }

    private val stories: Flow<List<StoryEntity>> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) repository.observeAll() else repository.search(query)
        }

    val state: StateFlow<WritersUiState> = combine(
        controls,
        stories,
        repository.observeStoryStats(),
    ) { c, list, stats ->
        val status = c.filter.status
        val visible = if (status == null) list else list.filter { it.status == status }
        WritersUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            filter = c.filter,
            stories = visible,
            stats = stats,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WritersUiState())

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    fun selectFilter(value: StoryFilter) {
        filterFlow.value = value
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
