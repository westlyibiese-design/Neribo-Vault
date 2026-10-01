package com.westly.neribovault.feature.developer.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.feature.developer.DeveloperRoutes
import com.westly.neribovault.feature.developer.MAX_TECH_ITEMS
import com.westly.neribovault.feature.developer.MAX_TECH_LENGTH
import com.westly.neribovault.feature.developer.isValidWebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The project form. */
data class ProjectEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val name: String = "",
    val description: String = "",
    val status: String = "planning",
    val techStack: List<String> = emptyList(),
    val repoUrl: String = "",
    val liveUrl: String = "",
    val nameError: Boolean = false,
    val repoError: Boolean = false,
    val liveError: Boolean = false,
    val hasChanges: Boolean = false,
    val isSaving: Boolean = false,
) {
    /** True when nothing has been filled in (a new project like this is simply discarded). */
    val isBlank: Boolean
        get() = name.isBlank() && description.isBlank() && techStack.isEmpty() &&
            repoUrl.isBlank() && liveUrl.isBlank()
}

/** State and actions for the project editor. Saving is explicit. */
class ProjectEditorViewModel(
    projectId: String,
    private val repository: ProjectsRepository,
) : ViewModel() {

    private val id = projectId
    private val isNew = projectId == DeveloperRoutes.NEW
    private var existing: ProjectEntity? = null

    private val _state = MutableStateFlow(ProjectEditorUiState(isLoading = !isNew, isNew = isNew))
    val state: StateFlow<ProjectEditorUiState> = _state.asStateFlow()

    init {
        if (!isNew) {
            viewModelScope.launch {
                val project = repository.getById(id)
                if (project == null || project.isDeleted) {
                    _state.update { it.copy(isLoading = false, notFound = true) }
                } else {
                    existing = project
                    _state.update {
                        it.copy(
                            isLoading = false,
                            name = project.name,
                            description = project.description,
                            status = project.status,
                            techStack = project.techStack,
                            repoUrl = project.repoUrl,
                            liveUrl = project.liveUrl,
                        )
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) =
        _state.update { it.copy(name = value, nameError = false, hasChanges = true) }

    fun onDescriptionChange(value: String) =
        _state.update { it.copy(description = value, hasChanges = true) }

    fun onStatusChange(value: String) =
        _state.update { it.copy(status = value, hasChanges = true) }

    fun onRepoChange(value: String) =
        _state.update { it.copy(repoUrl = value, repoError = false, hasChanges = true) }

    fun onLiveChange(value: String) =
        _state.update { it.copy(liveUrl = value, liveError = false, hasChanges = true) }

    /** Adds [tech] unless it is empty, a duplicate (ignoring case) or the stack is full. */
    fun addTech(tech: String) {
        val clean = tech.trim().take(MAX_TECH_LENGTH)
        _state.update { current ->
            val duplicate = current.techStack.any { it.equals(clean, ignoreCase = true) }
            if (clean.isEmpty() || duplicate || current.techStack.size >= MAX_TECH_ITEMS) {
                current
            } else {
                current.copy(techStack = current.techStack + clean, hasChanges = true)
            }
        }
    }

    fun removeTech(tech: String) =
        _state.update { it.copy(techStack = it.techStack - tech, hasChanges = true) }

    /** Validates and saves, then calls [onSaved]. A new project with nothing in it is discarded. */
    fun save(onSaved: () -> Unit) {
        val form = _state.value
        if (form.isSaving) return
        if (form.isNew && form.isBlank) {
            onSaved()
            return
        }
        val nameBad = form.name.isBlank()
        val repoBad = !isValidWebUrl(form.repoUrl)
        val liveBad = !isValidWebUrl(form.liveUrl)
        if (nameBad || repoBad || liveBad) {
            _state.update { it.copy(nameError = nameBad, repoError = repoBad, liveError = liveBad) }
            return
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val base = existing
            val project = if (base != null) {
                base.copy(
                    name = form.name.trim(),
                    description = form.description.trim(),
                    status = form.status,
                    techStack = form.techStack,
                    repoUrl = form.repoUrl.trim(),
                    liveUrl = form.liveUrl.trim(),
                )
            } else {
                ProjectEntity(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    name = form.name.trim(),
                    description = form.description.trim(),
                    status = form.status,
                    techStack = form.techStack,
                    repoUrl = form.repoUrl.trim(),
                    liveUrl = form.liveUrl.trim(),
                )
            }
            repository.upsert(project)
            onSaved()
        }
    }
}
