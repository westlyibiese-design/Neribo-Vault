package com.westly.neribovault.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.repository.GoalsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The fields of the goal form. */
data class GoalForm(
    val title: String = "",
    val description: String = "",
    val category: String = "personal",
    /** Start-of-day millis, or null for no target date. */
    val targetDate: Long? = null,
    val status: String = "active",
)

/** What the goal editor needs from the ViewModel. The form itself lives in the screen. */
data class GoalEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    /** The values the form started with, used to tell whether anything changed. */
    val initial: GoalForm = GoalForm(),
    val isSaving: Boolean = false,
)

/**
 * Loads one goal (or prepares a new one) and saves it when asked. There is no autosave: goal
 * setup is a deliberate action, so nothing is written until [save].
 */
class GoalEditorViewModel(
    private val goalId: String,
    private val repository: GoalsRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = goalId == GoalsRoutes.NEW_GOAL_ID

    private val _state = MutableStateFlow(GoalEditorUiState(isLoaded = isNew))
    val state: StateFlow<GoalEditorUiState> = _state.asStateFlow()

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val goal = repository.getById(goalId)
            if (goal == null || goal.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    isLoaded = true,
                    initial = GoalForm(
                        title = goal.title,
                        description = goal.description,
                        category = goal.category,
                        targetDate = goal.targetDate,
                        status = goal.status,
                    ),
                )
            }
        }
    }

    /**
     * Saves [form] and then calls [onSaved] with the goal's id. A blank title is not saved.
     * Keeps everything the form does not edit (pin, creation time) exactly as it was.
     */
    fun save(form: GoalForm, onSaved: (savedId: String) -> Unit) {
        if (_state.value.isSaving || form.title.isBlank()) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = if (isNew) null else repository.getById(goalId)
            val id = existing?.id ?: newId()
            val completed = form.status == "completed"
            repository.upsert(
                GoalEntity(
                    id = id,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    isDeleted = existing?.isDeleted ?: false,
                    deletedAt = existing?.deletedAt,
                    title = form.title.trim(),
                    description = form.description.trim(),
                    category = form.category,
                    targetDate = form.targetDate,
                    status = form.status,
                    completedAt = if (completed) (existing?.completedAt ?: now) else null,
                    isPinned = existing?.isPinned ?: false,
                ),
            )
            _state.update { it.copy(isSaving = false) }
            onSaved(id)
        }
    }
}
