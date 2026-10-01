package com.westly.neribovault.feature.documents

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import com.westly.neribovault.feature.documents.reminders.DocumentReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The filter chosen in the chip row. */
sealed interface DocumentFilter {
    object All : DocumentFilter

    /** Expired plus expiring soon. Reached from the summary card; it has no chip of its own. */
    object NeedsAttention : DocumentFilter

    object ExpiringSoon : DocumentFilter

    object Expired : DocumentFilter

    data class Category(val name: String) : DocumentFilter
}

/** The documents in one urgency group, already sorted. */
data class DocumentSection(
    val state: ExpiryState,
    val title: String,
    val documents: List<PersonalDocumentEntity>,
)

/** Everything the Documents list screen draws. */
data class DocumentsUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val selectedFilter: DocumentFilter = DocumentFilter.All,
    val categories: List<String> = emptyList(),
    val sections: List<DocumentSection> = emptyList(),
    val expiredCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val hasAnyDocuments: Boolean = false,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = sections.isEmpty()
    val isFiltering: Boolean
        get() = query.isNotBlank() || selectedFilter != DocumentFilter.All
    val needsAttention: Boolean get() = expiredCount > 0 || expiringSoonCount > 0
}

private data class Controls(
    val query: String,
    val isSearchOpen: Boolean,
    val filter: DocumentFilter,
)

private val GROUP_ORDER = listOf(
    ExpiryState.Expired,
    ExpiryState.ExpiringSoon,
    ExpiryState.Valid,
    ExpiryState.NoExpiry,
)

private fun sectionTitle(state: ExpiryState): String = when (state) {
    ExpiryState.Expired -> "EXPIRED"
    ExpiryState.ExpiringSoon -> "EXPIRING SOON"
    ExpiryState.Valid -> "VALID"
    ExpiryState.NoExpiry -> "NO EXPIRY"
}

/** Expired: most recently expired first. Soon and valid: nearest date first. No expiry: A to Z. */
private fun sortGroup(state: ExpiryState, documents: List<PersonalDocumentEntity>): List<PersonalDocumentEntity> =
    when (state) {
        ExpiryState.Expired -> documents.sortedByDescending { it.expiryDate ?: Long.MIN_VALUE }
        ExpiryState.ExpiringSoon, ExpiryState.Valid ->
            documents.sortedBy { it.expiryDate ?: Long.MAX_VALUE }
        ExpiryState.NoExpiry -> documents.sortedBy { it.title.trim().lowercase() }
    }

private fun PersonalDocumentEntity.matchesQuery(needle: String): Boolean =
    title.contains(needle, ignoreCase = true) ||
        category.contains(needle, ignoreCase = true) ||
        issuer.contains(needle, ignoreCase = true) ||
        notes.contains(needle, ignoreCase = true)

/**
 * State and actions for the Documents list. On first creation it re-syncs every document's
 * reminder once (idempotent), so reminders survive a reinstall, a cleared WorkManager queue or a
 * restored backup.
 */
class DocumentsViewModel(
    private val appContext: Context,
    private val repository: PersonalDocumentsRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val filterFlow = MutableStateFlow<DocumentFilter>(DocumentFilter.All)

    private val controls: Flow<Controls> =
        combine(queryFlow, searchOpenFlow, filterFlow) { query, open, filter ->
            Controls(query = query, isSearchOpen = open, filter = filter)
        }

    val state: StateFlow<DocumentsUiState> = combine(controls, repository.observeAll()) { c, all ->
        val now = System.currentTimeMillis()
        val classified = all.map { doc -> doc to expiryState(doc.expiryDate, doc.remindDaysBefore, now) }
        val expiredCount = classified.count { it.second == ExpiryState.Expired }
        val soonCount = classified.count { it.second == ExpiryState.ExpiringSoon }

        // Categories that exist, most used first.
        val categories = all
            .map { it.category.trim() }
            .filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }
            .map { (_, group) -> group.first() to group.size }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() },
            )
            .map { it.first }

        // A chosen category nobody uses any more quietly stops applying.
        val chosen = c.filter
        val activeFilter: DocumentFilter =
            if (chosen is DocumentFilter.Category &&
                categories.none { it.equals(chosen.name, ignoreCase = true) }
            ) {
                DocumentFilter.All
            } else {
                chosen
            }

        val needle = c.query.trim()
        val visible = classified
            .filter { entry -> needle.isEmpty() || entry.first.matchesQuery(needle) }
            .filter { entry ->
                val group = entry.second
                when (activeFilter) {
                    DocumentFilter.All -> true
                    DocumentFilter.NeedsAttention ->
                        group == ExpiryState.Expired || group == ExpiryState.ExpiringSoon
                    DocumentFilter.ExpiringSoon -> group == ExpiryState.ExpiringSoon
                    DocumentFilter.Expired -> group == ExpiryState.Expired
                    is DocumentFilter.Category ->
                        entry.first.category.trim().equals(activeFilter.name, ignoreCase = true)
                }
            }

        val sections = GROUP_ORDER.mapNotNull { group ->
            val docs = visible.filter { it.second == group }.map { it.first }
            if (docs.isEmpty()) {
                null
            } else {
                DocumentSection(group, sectionTitle(group), sortGroup(group, docs))
            }
        }

        DocumentsUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            selectedFilter = activeFilter,
            categories = categories,
            sections = sections,
            expiredCount = expiredCount,
            expiringSoonCount = soonCount,
            hasAnyDocuments = all.isNotEmpty(),
            isLoading = false,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsUiState())

    init {
        viewModelScope.launch {
            val all = repository.observeAll().first()
            withContext(Dispatchers.Default) {
                DocumentReminderScheduler.syncAll(appContext, all)
            }
        }
    }

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

    /** Selects [filter], or goes back to All when it is already the chosen one. */
    fun toggleFilter(filter: DocumentFilter) {
        filterFlow.value = if (filterFlow.value == filter) DocumentFilter.All else filter
    }

    fun clearFilter() {
        filterFlow.value = DocumentFilter.All
    }

    /** Soft-deletes a document (its file stays for Undo) and cancels its reminder. */
    fun delete(id: String) {
        viewModelScope.launch {
            repository.softDelete(id)
            DocumentReminderScheduler.cancel(appContext, id)
        }
    }

    /** Brings a deleted document back and schedules its reminder again. */
    fun restore(id: String) {
        viewModelScope.launch {
            repository.restore(id)
            val document = repository.getById(id)
            if (document != null) DocumentReminderScheduler.sync(appContext, document)
        }
    }
}
