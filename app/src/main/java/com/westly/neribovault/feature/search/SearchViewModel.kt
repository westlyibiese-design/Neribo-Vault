package com.westly.neribovault.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.search.GlobalIndex
import com.westly.neribovault.core.search.SearchGroup
import com.westly.neribovault.core.search.SearchableItem
import com.westly.neribovault.core.search.groupHitsByVault
import com.westly.neribovault.core.search.parseSearchTerms
import com.westly.neribovault.core.search.searchItems
import com.westly.neribovault.core.search.toSearchable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the Search screen shows. */
data class SearchUiState(
    val query: String = "",
    val committedQuery: String = "",
    val terms: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val groups: List<SearchGroup> = emptyList(),
    val totalHits: Int = 0,
    val selectedVault: String? = null,
    val recentSearches: List<String> = emptyList(),
) {
    /** The vault filter in use, or null for All (also when the chosen vault has no results now). */
    val effectiveVault: String?
        get() = selectedVault?.takeIf { id -> groups.any { it.vaultId == id } }

    /** The groups to show after the vault filter. */
    val visibleGroups: List<SearchGroup>
        get() {
            val vault = effectiveVault ?: return groups
            return groups.filter { it.vaultId == vault }
        }
}

/**
 * Searches the in-memory index of every vault. The query lives here so it survives rotation.
 * Typing is debounced by 200ms; clearing the box shows the empty state at once.
 */
class SearchViewModel(private val index: GlobalIndex) : ViewModel() {

    private val rawQuery = MutableStateFlow("")
    private val indexFlow = MutableStateFlow<List<SearchableItem>>(emptyList())
    private val _state = MutableStateFlow(SearchUiState())

    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var indexReady = false

    init {
        viewModelScope.launch {
            SessionSearchHistory.recent.collect { recent ->
                _state.update { it.copy(recentSearches = recent) }
            }
        }
        viewModelScope.launch {
            // collectLatest cancels the waiting block when a newer query arrives, which makes
            // the delay a debounce. A blank query is handled at once.
            combine(rawQuery, indexFlow) { query, items -> query to items }
                .collectLatest { (query, items) ->
                    if (query.isNotBlank()) delay(DEBOUNCE_MILLIS)
                    runSearch(query, items)
                }
        }
    }

    /** Called on every keystroke. */
    fun onQueryChange(query: String) {
        rawQuery.value = query
        _state.update { it.copy(query = query) }
    }

    fun selectVault(vaultId: String?) {
        _state.update { it.copy(selectedVault = vaultId) }
    }

    /** Remembers the current search for this session only. Called when a result is opened. */
    fun rememberCurrentSearch() {
        SessionSearchHistory.remember(_state.value.committedQuery)
    }

    /**
     * (Re)loads the index without the vaults in [hiddenVaults]. Called when the screen is shown
     * and whenever a vault lock is turned on or off.
     */
    fun reload(hiddenVaults: Set<String>) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!indexReady) _state.update { it.copy(isLoading = true) }
            val searchable = try {
                val loaded = index.load(hiddenVaults)
                withContext(Dispatchers.Default) { loaded.map { it.toSearchable() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            indexReady = true
            indexFlow.value = searchable
            _state.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun runSearch(query: String, items: List<SearchableItem>) {
        val terms = parseSearchTerms(query)
        val groups = withContext(Dispatchers.Default) {
            groupHitsByVault(searchItems(items, terms))
        }
        val total = groups.sumOf { group -> group.hits.size }
        _state.update {
            it.copy(
                committedQuery = query.trim(),
                terms = terms,
                groups = groups,
                totalHits = total,
            )
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 200L
    }
}

/**
 * The last few searches of this app session, kept in memory only. Nothing is ever written to
 * disk, and the list is gone when the app process ends.
 */
internal object SessionSearchHistory {
    private const val MAX_ENTRIES = 3

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun remember(query: String) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return
        _recent.update { current ->
            (listOf(cleaned) + current.filter { !it.equals(cleaned, ignoreCase = true) })
                .take(MAX_ENTRIES)
        }
    }
}
