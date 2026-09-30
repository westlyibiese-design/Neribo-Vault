package com.westly.neribovault.feature.ideas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.data.repository.IdeasRepository
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

/** Everything the Ideas list screen draws. */
data class IdeasUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    /** Stored category value, or null for "All". */
    val category: String? = null,
    /** Stored status value, or null for every status. */
    val status: String? = null,
    val pinned: List<IdeaEntity> = emptyList(),
    val others: List<IdeaEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
    val isFiltering: Boolean get() = query.isNotBlank() || category != null || status != null
}

private data class IdeaControls(
    val query: String,
    val isSearchOpen: Boolean,
    val category: String?,
    val status: String?,
)

/** State and actions for the Ideas list. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdeasViewModel(private val repository: IdeasRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val categoryFlow = MutableStateFlow<String?>(null)
    private val statusFlow = MutableStateFlow<String?>(null)

    private val controls: Flow<IdeaControls> = combine(
        queryFlow,
        searchOpenFlow,
        categoryFlow,
        statusFlow,
    ) { query, open, category, status ->
        IdeaControls(query = query, isSearchOpen = open, category = category, status = status)
    }

    /** Pinned first, then most recently updated, for the current search text. */
    private val results: Flow<List<IdeaEntity>> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) repository.observeAll() else repository.search(query)
        }

    val state: StateFlow<IdeasUiState> = combine(controls, results) { c, ideas ->
        val filtered = ideas.filter { idea ->
            (c.category == null || idea.category == c.category) &&
                (c.status == null || idea.status == c.status)
        }
        IdeasUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            category = c.category,
            status = c.status,
            pinned = filtered.filter { it.isPinned },
            others = filtered.filterNot { it.isPinned },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdeasUiState())

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

    fun selectCategory(value: String?) {
        categoryFlow.value = value
    }

    fun selectStatus(value: String?) {
        statusFlow.value = value
    }

    fun clearFilters() {
        categoryFlow.value = null
        statusFlow.value = null
    }

    fun togglePinned(idea: IdeaEntity) {
        viewModelScope.launch { repository.setPinned(idea.id, !idea.isPinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
