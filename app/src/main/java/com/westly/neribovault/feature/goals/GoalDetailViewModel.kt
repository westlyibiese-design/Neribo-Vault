package com.westly.neribovault.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import com.westly.neribovault.data.repository.GoalsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the goal detail screen draws. */
data class GoalDetailUiState(
    val goal: GoalEntity? = null,
    val milestones: List<GoalMilestoneEntity> = emptyList(),
    val isLoading: Boolean = true,
    /** True once we know the goal is missing or in the trash. */
    val notFound: Boolean = false,
)

/** One-off things the detail screen answers with a snackbar. */
sealed interface GoalDetailEvent {
    /** The last open step was just ticked. The screen offers to complete the goal. */
    object AllStepsDone : GoalDetailEvent

    /** A step was just deleted. The screen offers Undo. */
    class StepDeleted(val milestone: GoalMilestoneEntity) : GoalDetailEvent
}

/** State and actions for one goal and its steps. */
class GoalDetailViewModel(
    private val goalId: String,
    private val repository: GoalsRepository,
) : ViewModel() {

    val state: StateFlow<GoalDetailUiState> = combine(
        repository.observeById(goalId),
        repository.observeMilestones(goalId),
    ) { goal, milestones ->
        GoalDetailUiState(
            goal = goal,
            milestones = milestones,
            isLoading = false,
            notFound = goal == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalDetailUiState())

    private val eventChannel = Channel<GoalDetailEvent>(Channel.BUFFERED)

    /** Snackbar-worthy events, each delivered once. */
    val events: Flow<GoalDetailEvent> = eventChannel.receiveAsFlow()

    /**
     * Ticks or unticks a step. When this tick closes the last open step of a goal that is not
     * yet completed, the screen is told so it can offer to complete the goal. Never automatic.
     */
    fun toggleMilestone(milestone: GoalMilestoneEntity) {
        val current = state.value
        val becomingDone = !milestone.isDone
        val otherStepsOpen = current.milestones.any { it.id != milestone.id && !it.isDone }
        val goalOpen = current.goal?.status != "completed"
        viewModelScope.launch {
            repository.setMilestoneDone(milestone.id, becomingDone)
            if (becomingDone && !otherStepsOpen && goalOpen) {
                eventChannel.send(GoalDetailEvent.AllStepsDone)
            }
        }
    }

    /** Adds a step at the bottom of the list. Blank text is ignored. */
    fun addMilestone(title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val nextOrder = (state.value.milestones.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            repository.upsertMilestone(
                GoalMilestoneEntity(
                    id = newId(),
                    goalId = goalId,
                    title = clean,
                    sortOrder = nextOrder,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    fun renameMilestone(milestone: GoalMilestoneEntity, title: String) {
        val clean = title.trim()
        if (clean.isEmpty() || clean == milestone.title) return
        viewModelScope.launch { repository.upsertMilestone(milestone.copy(title = clean)) }
    }

    /** Sets or clears (null) the due date of a step. [dueDate] is start-of-day millis. */
    fun setMilestoneDueDate(milestone: GoalMilestoneEntity, dueDate: Long?) {
        viewModelScope.launch { repository.upsertMilestone(milestone.copy(dueDate = dueDate)) }
    }

    /** Moves a step one place up ([delta] -1) or down (+1) and rewrites the sort order. */
    fun moveMilestone(milestone: GoalMilestoneEntity, delta: Int) {
        val list = state.value.milestones
        val from = list.indexOfFirst { it.id == milestone.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        val reordered = list.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        viewModelScope.launch {
            reordered.forEachIndexed { index, item ->
                if (item.sortOrder != index) {
                    repository.upsertMilestone(item.copy(sortOrder = index))
                }
            }
        }
    }

    /** Deletes a step for good, then tells the screen so it can offer Undo. */
    fun deleteMilestone(milestone: GoalMilestoneEntity) {
        viewModelScope.launch {
            repository.deleteMilestone(milestone.id)
            eventChannel.send(GoalDetailEvent.StepDeleted(milestone))
        }
    }

    /** Puts back a step deleted a moment ago. */
    fun restoreMilestone(milestone: GoalMilestoneEntity) {
        viewModelScope.launch { repository.upsertMilestone(milestone) }
    }

    fun setStatus(status: String) {
        viewModelScope.launch { repository.setStatus(goalId, status) }
    }

    fun togglePinned() {
        val goal = state.value.goal ?: return
        viewModelScope.launch { repository.setPinned(goalId, !goal.isPinned) }
    }

    /** Soft-deletes the goal (its steps go with it) and reports its id. */
    fun deleteGoal(onDone: (deletedId: String) -> Unit) {
        viewModelScope.launch {
            repository.softDelete(goalId)
            onDone(goalId)
        }
    }
}
