package com.westly.neribovault.feature.developer.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.repository.ProjectsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The project on the detail screen, or null once it is gone. */
data class ProjectDetailUiState(
    val project: ProjectEntity? = null,
    val isLoading: Boolean = true,
)

/** State and actions for the project detail screen. */
class ProjectDetailViewModel(
    private val projectId: String,
    private val repository: ProjectsRepository,
) : ViewModel() {

    val state: StateFlow<ProjectDetailUiState> = repository.observeById(projectId)
        .map { project -> ProjectDetailUiState(project = project, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

    /** Soft-deletes the project, then calls [onDone]. Linked items are left as they are. */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.softDelete(projectId)
            onDone()
        }
    }
}
