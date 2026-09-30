package com.westly.neribovault.feature.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.data.repository.DiaryRepository
import java.time.LocalDate
import java.time.YearMonth
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

/** How the Diary list is drawn. */
enum class DiaryMode { List, Calendar }

/** Everything the Diary list screen draws. */
data class DiaryUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val mode: DiaryMode = DiaryMode.List,
    val today: LocalDate = LocalDate.now(),
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDay: LocalDate = LocalDate.now(),
    /** Every non-deleted entry, newest first (List mode, no search). */
    val entries: List<DiaryEntryEntity> = emptyList(),
    /** Search results, ungrouped, newest first. */
    val searchResults: List<DiaryEntryEntity> = emptyList(),
    /** The entries written for [selectedDay] (Calendar mode). */
    val dayEntries: List<DiaryEntryEntity> = emptyList(),
    /** Days that have at least one entry, for the calendar dots. */
    val entryDays: Set<LocalDate> = emptySet(),
    val streak: Int = 0,
    val isLoading: Boolean = true,
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val hasEntryToday: Boolean get() = today in entryDays
}

private data class Controls(
    val query: String,
    val isSearchOpen: Boolean,
    val mode: DiaryMode,
    val today: LocalDate,
)

private data class Calendar(
    val visibleMonth: YearMonth,
    val selectedDay: LocalDate,
    val entryDays: Set<LocalDate>,
    val dayEntries: List<DiaryEntryEntity>,
)

private data class Lists(
    val entries: List<DiaryEntryEntity>,
    val searchResults: List<DiaryEntryEntity>,
)

/** State and actions for the Diary list: list or calendar, search, streak and delete/undo. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(private val repository: DiaryRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val modeFlow = MutableStateFlow(DiaryMode.List)
    private val todayFlow = MutableStateFlow(LocalDate.now(zone()))
    private val monthFlow = MutableStateFlow(YearMonth.from(LocalDate.now(zone())))
    private val selectedDayFlow = MutableStateFlow(LocalDate.now(zone()))

    private val controls: Flow<Controls> = combine(
        queryFlow,
        searchOpenFlow,
        modeFlow,
        todayFlow,
    ) { query, open, mode, today ->
        Controls(query = query, isSearchOpen = open, mode = mode, today = today)
    }

    private val entryDays: Flow<Set<LocalDate>> = repository.observeEntryDates()
        .map { dates -> dates.map { it.toLocalDate() }.toSet() }

    private val dayEntries: Flow<List<DiaryEntryEntity>> = selectedDayFlow
        .flatMapLatest { day ->
            val start = day.toStartOfDayMillis()
            val end = day.plusDays(1).toStartOfDayMillis()
            repository.observeInRange(start, end)
        }

    private val calendar: Flow<Calendar> = combine(
        monthFlow,
        selectedDayFlow,
        entryDays,
        dayEntries,
    ) { month, day, days, entriesOfDay ->
        Calendar(visibleMonth = month, selectedDay = day, entryDays = days, dayEntries = entriesOfDay)
    }

    private val lists: Flow<Lists> = queryFlow
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            val results: Flow<List<DiaryEntryEntity>> =
                if (query.isEmpty()) repository.observeAll().map { emptyList<DiaryEntryEntity>() } else repository.search(query)
            combine(repository.observeAll(), results) { all, found ->
                Lists(entries = all, searchResults = found)
            }
        }

    val state: StateFlow<DiaryUiState> = combine(controls, calendar, lists) { c, cal, l ->
        DiaryUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            mode = c.mode,
            today = c.today,
            visibleMonth = cal.visibleMonth,
            selectedDay = cal.selectedDay,
            entries = l.entries,
            searchResults = l.searchResults,
            dayEntries = cal.dayEntries,
            entryDays = cal.entryDays,
            streak = computeStreak(cal.entryDays, c.today),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

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

    /** Switches between the list and the calendar. Entering the calendar selects today. */
    fun toggleMode() {
        val next = if (modeFlow.value == DiaryMode.List) DiaryMode.Calendar else DiaryMode.List
        modeFlow.value = next
        if (next == DiaryMode.Calendar) {
            val today = LocalDate.now(zone())
            selectedDayFlow.value = today
            monthFlow.value = YearMonth.from(today)
        }
    }

    fun selectDay(day: LocalDate) {
        selectedDayFlow.value = day
        monthFlow.value = YearMonth.from(day)
    }

    fun previousMonth() {
        monthFlow.value = monthFlow.value.minusMonths(1)
    }

    fun nextMonth() {
        monthFlow.value = monthFlow.value.plusMonths(1)
    }

    /** Re-reads today's date, so the streak and "Today" prompt stay right after midnight. */
    fun refreshToday() {
        todayFlow.value = LocalDate.now(zone())
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
