package com.westly.neribovault.feature.coder.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.data.repository.ProjectsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val UNTITLED_PROJECT = "Untitled project"

/** How one Coder project relates to the Developer vault: not linked, linked to a project that is there, or linked to one that is gone. */
sealed interface CoderLinkState {
    /** The project is not linked to any Developer project. */
    object Unlinked : CoderLinkState

    /** The project is linked to the Developer project called [name]. */
    data class Linked(val name: String) : CoderLinkState

    /** A link is stored, but the Developer project is deleted or in the trash. The link comes back if the project is restored. */
    object Missing : CoderLinkState
}

/**
 * The link between one Coder project and a Developer project. Only the Developer project's id is stored;
 * nothing of it is copied, and linking never changes anything in the Developer vault.
 */
class CoderLinkViewModel(
    private val coderProjectId: String,
    private val coderProjects: CoderProjectsRepository,
    private val developerProjects: ProjectsRepository,
) : ViewModel() {

    private val linkedIdFlow: Flow<String?> = coderProjects.observeById(coderProjectId)
        .map { it?.developerProjectId }
        .distinctUntilChanged()

    /** Whether the project is linked, and to what. Follows both vaults, so it changes when a Developer project is renamed, deleted or restored. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val linkState: StateFlow<CoderLinkState> = linkedIdFlow
        .flatMapLatest { developerId -> stateOf(developerId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoderLinkState.Unlinked)

    /** The id of the stored link (even when that project is gone), or null. The sheet checks the matching row. */
    val linkedId: StateFlow<String?> = linkedIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The Developer projects that can be chosen, newest first. Null until they are loaded. */
    val projects: StateFlow<List<ProjectEntity>?> = developerProjects.observeAll()
        .map<List<ProjectEntity>, List<ProjectEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun stateOf(developerId: String?): Flow<CoderLinkState> {
        if (developerId == null) return flowOf(CoderLinkState.Unlinked)
        return developerProjects.observeById(developerId).map { project ->
            if (project == null) CoderLinkState.Missing else CoderLinkState.Linked(displayName(project))
        }
    }

    /** Links the Coder project to the Developer project [developerProjectId]. Ignored when that project is missing or in the trash. */
    fun link(developerProjectId: String) {
        viewModelScope.launch {
            val target = developerProjects.getById(developerProjectId)
            if (target == null || target.isDeleted) return@launch
            coderProjects.setDeveloperLink(coderProjectId, developerProjectId)
        }
    }

    /** Removes the link. The Developer project and the Coder files are not touched. */
    fun unlink() {
        viewModelScope.launch { coderProjects.setDeveloperLink(coderProjectId, null) }
    }
}

/** The name of a Developer project as shown next to a link. */
internal fun displayName(project: ProjectEntity): String = project.name.trim().ifEmpty { UNTITLED_PROJECT }
