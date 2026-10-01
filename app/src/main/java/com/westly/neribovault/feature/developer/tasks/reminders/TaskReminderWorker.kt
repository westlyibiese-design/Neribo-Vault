package com.westly.neribovault.feature.developer.tasks.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.westly.neribovault.NeriboApp

/**
 * Runs at (about) the due time of a task and shows the "Task due" notification. It re-reads
 * the task first, so a task that was finished, deleted or rescheduled in the meantime never
 * produces a stale reminder.
 */
class TaskReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.success()
        val app = applicationContext as? NeriboApp ?: return Result.success()
        val task = app.container.tasksRepository.getById(taskId) ?: return Result.success()
        val dueAt = task.dueAt
        val isStale = task.isDeleted ||
            task.isDone ||
            dueAt == null ||
            dueAt > System.currentTimeMillis() + EARLY_TOLERANCE_MS
        if (isStale) return Result.success()
        val projectName = task.projectId
            ?.let { id -> app.container.projectsRepository.getById(id) }
            ?.takeIf { project -> !project.isDeleted }
            ?.name
        TaskReminderScheduler.showReminder(applicationContext, task, projectName)
        return Result.success()
    }

    companion object {
        /** Input data key holding the task id. */
        const val KEY_TASK_ID = "task_id"

        /** A run this much earlier than the stored time is treated as belonging to an old time. */
        private const val EARLY_TOLERANCE_MS = 2 * 60_000L
    }
}
