package com.westly.neribovault.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.repository.GoalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which goals the list shows. The stored status each scope maps to is in [status]. */
enum class GoalsScope(val status: String, val label: String) {
    Active("active", "Active"),
    Paused("paused", "Paused"),
    Completed("completed", "Completed"),
}

/** One row of the Goals list: the goal and its progress. */
data class GoalListItem(
    val goal: GoalEntity,
    val progress: GoalProgress,
)

/** Everything the Goals list screen draws. */
data class GoalsUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val scope: GoalsScope = GoalsScope.Active,
    /** Stored category value, or null for "All". */
    val category: String? = null,
    val pinned: List<GoalListItem> = emptyList(),
    val others: List<GoalListItem> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
}

private data class GoalControls(
    val query: String,
    val isSearchOpen: Boolean,
    val scope: GoalsScope,
    val category: String?,
)

private fun GoalEntity.matches(query: String): Boolean =
    title.contains(query, ignoreCase = true) ||
        description.contains(query, ignoreCase = true) ||
        goalCategoryLabel(category).contains(query, ignoreCase = true)

/** State and actions for the Goals list. */
class GoalsViewModel(private val repository: GoalsRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val scopeFlow = MutableStateFlow(GoalsScope.Active)
    private val categoryFlow = MutableStateFlow<String?>(null)

    private val controls: Flow<GoalControls> = combine(
        queryFlow,
        searchOpenFlow,
        scopeFlow,
        categoryFlow,
    ) { query, open, scope, category ->
        GoalControls(query = query, isSearchOpen = open, scope = scope, category = category)
    }

    val state: StateFlow<GoalsUiState> = combine(
        controls,
        repository.observeAll(),
        repository.observeAllMilestones(),
    ) { c, goals, milestones ->
        val byGoal = milestones.groupBy { it.goalId }
        val query = c.query.trim()
        val inScope = goals
            .filter { it.status == c.scope.status }
            .filter { c.category == null || it.category == c.category }
            .filter { query.isEmpty() || it.matches(query) }
        // The repository returns most recently updated first. Finished goals read better by
        // the day they were finished.
        val ordered = if (c.scope == GoalsScope.Completed) {
            inScope.sortedByDescending { it.completedAt ?: it.updatedAt }
        } else {
            inScope
        }
        val items = ordered.map { goal ->
            GoalListItem(
                goal = goal,
                progress = goalProgress(goal.status, byGoal[goal.id].orEmpty()),
            )
        }
        GoalsUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            scope = c.scope,
            category = c.category,
            pinned = items.filter { it.goal.isPinned },
            others = items.filterNot { it.goal.isPinned },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalsUiState())

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

    fun selectScope(value: GoalsScope) {
        scopeFlow.value = value
    }

    fun selectCategory(value: String?) {
        categoryFlow.value = value
    }

    fun clearCategory() {
        categoryFlow.value = null
    }

    fun togglePinned(goal: GoalEntity) {
        viewModelScope.launch { repository.setPinned(goal.id, !goal.isPinned) }
    }

    fun setStatus(id: String, status: String) {
        viewModelScope.launch { repository.setStatus(id, status) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
