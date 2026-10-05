package com.westly.neribovault.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.NoteEntity
import com.westly.neribovault.data.repository.NotesRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which notes the list shows. */
enum class NotesScope { Notes, Archived }

/** Everything the Notes list screen draws. */
data class NotesUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val scope: NotesScope = NotesScope.Notes,
    val selectedTag: String? = null,
    val availableTags: List<String> = emptyList(),
    val pinned: List<NoteEntity> = emptyList(),
    val others: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
    val isFiltering: Boolean get() = query.isNotBlank() || selectedTag != null
}

private data class Controls(
    val query: String,
    val isSearchOpen: Boolean,
    val scope: NotesScope,
    val selectedTag: String?,
)

private data class ScopedNotes(val scope: NotesScope, val notes: List<NoteEntity>)

private fun NoteEntity.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        body.contains(query, ignoreCase = true) ||
        tags.any { it.contains(query, ignoreCase = true) }

/** State and actions for the Notes list. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val repository: NotesRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val scopeFlow = MutableStateFlow(NotesScope.Notes)
    private val selectedTagFlow = MutableStateFlow<String?>(null)

    private val controls: Flow<Controls> = combine(
        queryFlow,
        searchOpenFlow,
        scopeFlow,
        selectedTagFlow,
    ) { query, open, scope, tag ->
        Controls(query = query, isSearchOpen = open, scope = scope, selectedTag = tag)
    }

    private fun observeScope(scope: NotesScope): Flow<List<NoteEntity>> = when (scope) {
        NotesScope.Notes -> repository.observeActive()
        NotesScope.Archived -> repository.observeArchived()
    }

    /** The notes for the current scope and search text, tagged with the scope they belong to. */
    private val results: Flow<ScopedNotes> = combine(scopeFlow, queryFlow) { scope, query ->
        scope to query.trim()
    }
        .distinctUntilChanged()
        .flatMapLatest { (scope, query) ->
            val source: Flow<List<NoteEntity>> = if (scope == NotesScope.Notes && query.isNotEmpty()) {
                repository.search(query)
            } else {
                observeScope(scope).map { list ->
                    if (query.isEmpty()) list else list.filter { it.matches(query) }
                }
            }
            source.map { notes -> ScopedNotes(scope, notes) }
        }

    /** Every tag used by the notes of the current scope, ignoring the search text. */
    private val availableTags: Flow<List<String>> = scopeFlow
        .flatMapLatest { scope -> observeScope(scope) }
        .map { notes -> notes.flatMap { it.tags }.distinct().sorted() }

    val state: StateFlow<NotesUiState> = combine(controls, results, availableTags) { c, scoped, tags ->
        val tag = c.selectedTag?.takeIf { it in tags }
        if (scoped.scope != c.scope) {
            // The list for the new scope has not arrived yet; never show the old one.
            NotesUiState(
                query = c.query,
                isSearchOpen = c.isSearchOpen,
                scope = c.scope,
                selectedTag = tag,
                availableTags = tags,
                isLoading = true,
            )
        } else {
            val filtered = if (tag == null) scoped.notes else scoped.notes.filter { tag in it.tags }
            val showPinned = c.scope == NotesScope.Notes
            NotesUiState(
                query = c.query,
                isSearchOpen = c.isSearchOpen,
                scope = c.scope,
                selectedTag = tag,
                availableTags = tags,
                pinned = if (showPinned) filtered.filter { it.isPinned } else emptyList(),
                others = if (showPinned) filtered.filterNot { it.isPinned } else filtered,
                isLoading = false,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

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

    fun selectScope(value: NotesScope) {
        if (scopeFlow.value == value) return
        scopeFlow.value = value
        selectedTagFlow.value = null
    }

    fun toggleTag(tag: String) {
        selectedTagFlow.update { current -> if (current == tag) null else tag }
    }

    fun togglePinned(note: NoteEntity) {
        viewModelScope.launch { repository.setPinned(note.id, !note.isPinned) }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(id, archived) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id, "Notes list: Delete") }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
