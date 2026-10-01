package com.westly.neribovault.feature.developer.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.repository.ProjectsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Developer home draws. */
data class DeveloperHomeUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val selectedStatus: String? = null,
    val projects: List<ProjectEntity> = emptyList(),
    val totalProjects: Int = 0,
    val isLoading: Boolean = true,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || selectedStatus != null
}

/** State and actions for the Developer home. */
class DeveloperHomeViewModel(private val repository: ProjectsRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val statusFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<DeveloperHomeUiState> = combine(
        repository.observeAll(),
        queryFlow,
        searchOpenFlow,
        statusFlow,
    ) { all, query, open, status ->
        val needle = query.trim()
        val shown = all.filter { project ->
            (status == null || project.status == status) &&
                (needle.isEmpty() || project.matches(needle))
        }
        DeveloperHomeUiState(
            query = query,
            isSearchOpen = open,
            selectedStatus = status,
            projects = shown,
            totalProjects = all.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeveloperHomeUiState())

    private fun ProjectEntity.matches(needle: String): Boolean =
        name.contains(needle, ignoreCase = true) ||
            description.contains(needle, ignoreCase = true) ||
            techStack.any { it.contains(needle, ignoreCase = true) }

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

    /** Filters by [status], or shows every project when [status] is null. */
    fun selectStatus(status: String?) {
        statusFlow.value = status
    }

    /** Archives an active project, or brings an archived one back as active. */
    fun toggleArchived(project: ProjectEntity) {
        val newStatus = if (project.status == "archived") "active" else "archived"
        viewModelScope.launch { repository.upsert(project.copy(status = newStatus)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }
}
