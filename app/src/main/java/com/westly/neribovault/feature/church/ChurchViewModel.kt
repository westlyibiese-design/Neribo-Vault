package com.westly.neribovault.feature.church

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.data.repository.ChurchRepository
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

/** One speaker and how many records name them. */
data class SpeakerCount(val name: String, val count: Int)

/** The records of one calendar month, newest first. */
data class MonthGroup(val key: Int, val title: String, val records: List<ChurchRecordEntity>)

/** Everything the Church list screen draws. */
data class ChurchUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val selectedType: String? = null,
    val selectedSpeaker: String? = null,
    val speakers: List<SpeakerCount> = emptyList(),
    val pinned: List<ChurchRecordEntity> = emptyList(),
    val months: List<MonthGroup> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && months.isEmpty()
    val isFiltering: Boolean
        get() = query.isNotBlank() || selectedType != null || selectedSpeaker != null
}

private data class Controls(
    val query: String,
    val isSearchOpen: Boolean,
    val type: String?,
    val speaker: String?,
)

private val NEWEST_FIRST: Comparator<ChurchRecordEntity> =
    compareByDescending<ChurchRecordEntity> { it.recordDate }.thenByDescending { it.createdAt }

private fun buildSpeakerCounts(records: List<ChurchRecordEntity>): List<SpeakerCount> =
    records
        .map { it.speaker.trim() }
        .filter { it.isNotEmpty() }
        .groupBy { it.lowercase() }
        .map { (_, names) -> SpeakerCount(name = names.first(), count = names.size) }
        .sortedWith(compareByDescending<SpeakerCount> { it.count }.thenBy { it.name.lowercase() })

/** State and actions for the Church list. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChurchViewModel(private val repository: ChurchRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val typeFlow = MutableStateFlow<String?>(null)
    private val speakerFlow = MutableStateFlow<String?>(null)

    private val controls: Flow<Controls> = combine(
        queryFlow,
        searchOpenFlow,
        typeFlow,
        speakerFlow,
    ) { query, open, type, speaker ->
        Controls(query = query, isSearchOpen = open, type = type, speaker = speaker)
    }

    /** All records, or the ones matching the search text. Pinned first, then newest first. */
    private val records: Flow<List<ChurchRecordEntity>> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.isEmpty()) repository.observeAll() else repository.search(query)
        }

    /** Every speaker across all records, ignoring search and filters. */
    private val speakers: Flow<List<SpeakerCount>> = repository.observeAll()
        .map { buildSpeakerCounts(it) }

    val state: StateFlow<ChurchUiState> = combine(controls, records, speakers) { c, found, people ->
        // A speaker filter whose records are all gone quietly stops applying.
        val speaker = c.speaker?.let { chosen ->
            people.firstOrNull { it.name.equals(chosen, ignoreCase = true) }?.name
        }
        val filtered = found
            .filter { record ->
                (c.type == null || record.type == c.type) &&
                    (speaker == null || record.speaker.trim().equals(speaker, ignoreCase = true))
            }
            .sortedWith(NEWEST_FIRST)
        val months = filtered
            .filterNot { it.isPinned }
            .groupBy { monthKeyOf(it.recordDate) }
            .map { (key, list) ->
                MonthGroup(key = key, title = monthTitleOf(list.first().recordDate), records = list)
            }
        ChurchUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            selectedType = c.type,
            selectedSpeaker = speaker,
            speakers = people,
            pinned = filtered.filter { it.isPinned },
            months = months,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChurchUiState())

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

    /** Filters by [type], or shows every type when [type] is null. */
    fun selectType(type: String?) {
        typeFlow.value = type
    }

    /** Filters by [name] until cleared with null. */
    fun selectSpeaker(name: String?) {
        speakerFlow.value = name
    }

    fun togglePinned(record: ChurchRecordEntity) {
        viewModelScope.launch { repository.setPinned(record.id, !record.isPinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
