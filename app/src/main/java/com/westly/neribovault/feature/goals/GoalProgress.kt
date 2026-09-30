package com.westly.neribovault.feature.goals

import com.westly.neribovault.core.util.daysUntil
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity

/** How far along a goal is, ready to draw. */
data class GoalProgress(
    val doneCount: Int,
    val totalCount: Int,
    /** 0f to 1f. */
    val fraction: Float,
    /** For example "3 of 5 steps". */
    val label: String,
)

/**
 * The one progress rule used everywhere in Goals. With steps, progress is the done steps over
 * all steps. Without steps it is 100% only when the goal itself is completed, otherwise 0%.
 */
fun goalProgress(status: String, milestones: List<GoalMilestoneEntity>): GoalProgress {
    val total = milestones.size
    if (total > 0) {
        val done = milestones.count { it.isDone }
        return GoalProgress(
            doneCount = done,
            totalCount = total,
            fraction = done.toFloat() / total.toFloat(),
            label = if (total == 1) "$done of 1 step" else "$done of $total steps",
        )
    }
    val completed = status == "completed"
    return GoalProgress(
        doneCount = 0,
        totalCount = 0,
        fraction = if (completed) 1f else 0f,
        label = if (completed) "Completed" else "No steps yet",
    )
}

/** A due date split so that only the [suffix] can be drawn in the warning color. */
data class DueInfo(
    /** For example "Due 14 Dec 2026". */
    val prefix: String,
    /** For example "76 days left" or "Overdue". Empty when there is nothing to add. */
    val suffix: String,
    val isOverdue: Boolean,
)

/**
 * Due line for a goal. Completed goals show only the date. Otherwise it adds the days left, or
 * "Overdue" once the day has passed. Uses calendar days, so it flips exactly at midnight.
 */
fun goalDueInfo(targetDate: Long, isCompleted: Boolean): DueInfo {
    val prefix = "Due ${formatDate(targetDate)}"
    if (isCompleted) return DueInfo(prefix = prefix, suffix = "", isOverdue = false)
    val days = daysUntil(targetDate)
    return when {
        days < 0 -> DueInfo(prefix, "Overdue", isOverdue = true)
        days == 0 -> DueInfo(prefix, "Today", isOverdue = false)
        days == 1 -> DueInfo(prefix, "1 day left", isOverdue = false)
        else -> DueInfo(prefix, "$days days left", isOverdue = false)
    }
}

/** Due line for a single step: the date, plus "Overdue" when it is open and past due. */
fun stepDueInfo(dueDate: Long, isDone: Boolean): DueInfo {
    val prefix = "Due ${formatDate(dueDate)}"
    val overdue = !isDone && daysUntil(dueDate) < 0
    return DueInfo(prefix = prefix, suffix = if (overdue) "Overdue" else "", isOverdue = overdue)
}
