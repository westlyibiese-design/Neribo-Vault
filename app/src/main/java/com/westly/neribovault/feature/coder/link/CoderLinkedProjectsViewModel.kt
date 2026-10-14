package com.westly.neribovault.feature.coder.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Coder projects linked to one Developer project, for the card on its Overview tab. */
class CoderLinkedProjectsViewModel(
    developerProjectId: String,
    coderProjects: CoderProjectsRepository,
) : ViewModel() {
    /** The linked Coder projects (not in the trash), newest first. Null until they are loaded. */
    val projects: StateFlow<List<CoderProjectEntity>?> = coderProjects.observeByDeveloperProject(developerProjectId)
        .map<List<CoderProjectEntity>, List<CoderProjectEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
