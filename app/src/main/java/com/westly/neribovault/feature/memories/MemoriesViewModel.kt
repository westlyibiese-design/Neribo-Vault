package com.westly.neribovault.feature.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.data.repository.MemoriesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How many of the most used tags and people get a chip. */
private const val MAX_TAG_CHIPS = 6
private const val MAX_PERSON_CHIPS = 8

/** One filter chip: either a tag or a person's name. */
data class MemoryFilter(val isPerson: Boolean, val value: String)

/** The memories of one calendar month, newest first. */
data class MemoryMonthGroup(val key: Int, val title: String, val memories: List<MemoryEntity>)

/** Everything the Memories list screen draws. */
data class MemoriesUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val filters: List<MemoryFilter> = emptyList(),
    val selectedFilter: MemoryFilter? = null,
    val groups: List<MemoryMonthGroup> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
    val isFiltering: Boolean get() = query.isNotBlank() || selectedFilter != null
}

private data class Controls(
    val query: String,
    val isSearchOpen: Boolean,
    val filter: MemoryFilter?,
)

private val NEWEST_FIRST: Comparator<MemoryEntity> =
    compareByDescending<MemoryEntity> { it.memoryDate }.thenByDescending { it.createdAt }

/** Case-insensitive counts of [values], keeping the first spelling seen for each. */
private fun countValues(values: List<String>): List<Pair<String, Int>> =
    values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupBy { it.lowercase() }
        .map { (_, group) -> group.first() to group.size }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

private fun MemoryEntity.matchesQuery(needle: String): Boolean =
    title.contains(needle, ignoreCase = true) ||
        description.contains(needle, ignoreCase = true) ||
        location.contains(needle, ignoreCase = true) ||
        people.any { it.contains(needle, ignoreCase = true) } ||
        tags.any { it.contains(needle, ignoreCase = true) }

private fun MemoryEntity.matchesFilter(filter: MemoryFilter): Boolean =
    if (filter.isPerson) {
        people.any { it.trim().equals(filter.value, ignoreCase = true) }
    } else {
        tags.any { it.trim().equals(filter.value, ignoreCase = true) }
    }

/** State and actions for the Memories list. */
class MemoriesViewModel(private val repository: MemoriesRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val filterFlow = MutableStateFlow<MemoryFilter?>(null)

    private val controls: Flow<Controls> = combine(queryFlow, searchOpenFlow, filterFlow) { query, open, filter ->
        Controls(query = query, isSearchOpen = open, filter = filter)
    }

    val state: StateFlow<MemoriesUiState> = combine(controls, repository.observeAll()) { c, all ->
        val tagCounts = countValues(all.flatMap { it.tags })
        val peopleCounts = countValues(all.flatMap { it.people })

        // A chosen filter nobody uses any more quietly stops applying.
        val activeFilter = c.filter?.takeIf { chosen ->
            val pool = if (chosen.isPerson) peopleCounts else tagCounts
            pool.any { it.first.equals(chosen.value, ignoreCase = true) }
        }

        val chips = buildList<MemoryFilter> {
            tagCounts.take(MAX_TAG_CHIPS).forEach { add(MemoryFilter(isPerson = false, value = it.first)) }
            peopleCounts.take(MAX_PERSON_CHIPS).forEach { add(MemoryFilter(isPerson = true, value = it.first)) }
        }.let { shown ->
            // The chosen chip stays visible even when it is not among the most used.
            if (activeFilter != null && shown.none { sameFilter(it, activeFilter) }) {
                listOf(activeFilter) + shown
            } else {
                shown
            }
        }

        val needle = c.query.trim()
        val visible = all
            .filter { needle.isEmpty() || it.matchesQuery(needle) }
            .filter { activeFilter == null || it.matchesFilter(activeFilter) }
            .sortedWith(NEWEST_FIRST)
        val groups = visible
            .groupBy { monthKeyOf(it.memoryDate) }
            .map { (key, list) ->
                MemoryMonthGroup(key = key, title = monthTitleOf(list.first().memoryDate), memories = list)
            }

        MemoriesUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            filters = chips,
            selectedFilter = activeFilter,
            groups = groups,
            isLoading = false,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoriesUiState())

    private fun sameFilter(a: MemoryFilter, b: MemoryFilter): Boolean =
        a.isPerson == b.isPerson && a.value.equals(b.value, ignoreCase = true)

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

    /** Selects [filter], or clears it when it is already the chosen one. */
    fun toggleFilter(filter: MemoryFilter) {
        val current = filterFlow.value
        filterFlow.value = if (current != null && sameFilter(current, filter)) null else filter
    }

    fun clearFilter() {
        filterFlow.value = null
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
