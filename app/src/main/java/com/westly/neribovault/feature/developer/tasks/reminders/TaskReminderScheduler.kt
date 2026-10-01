package com.westly.neribovault.feature.developer.tasks.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.westly.neribovault.data.local.entity.TaskEntity
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels the "Task due" reminders. Each open task with a future due time gets
 * one unique WorkManager job named `task_reminder_<taskId>`. WorkManager is approximate: a
 * reminder can arrive a few minutes late. Nothing here needs exact alarms or a manifest entry.
 */
object TaskReminderScheduler {
    const val CHANNEL_ID = "task_reminders"

    private const val CHANNEL_NAME = "Task reminders"
    private const val PREFS_NAME = "task_reminders"
    private const val KEY_ASKED_PERMISSION = "asked_notification_permission"

    /** The unique work name for one task. */
    fun workName(taskId: String): String = "task_reminder_$taskId"

    /**
     * Makes the reminder match [task]: an open, undeleted task with a future due time gets (or
     * keeps) a reminder at that time, anything else has its reminder cancelled. Safe to call
     * after every create, edit, done, un-done, delete and restore.
     */
    fun sync(context: Context, task: TaskEntity) {
        val appContext = context.applicationContext
        val at = task.dueAt
        val now = System.currentTimeMillis()
        if (task.isDeleted || task.isDone || at == null || at <= now) {
            cancel(appContext, task.id)
            return
        }
        ensureChannel(appContext)
        val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(TaskReminderWorker.KEY_TASK_ID to task.id))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(workName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels the pending reminder for [taskId] and removes its notification if it is showing. */
    fun cancel(context: Context, taskId: String) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(taskId))
        appContext.getSystemService(NotificationManager::class.java)?.cancel(notificationId(taskId))
    }

    /** Creates the `task_reminders` channel. Does nothing before Android 8 or if it exists. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A gentle nudge when a task is due."
        }
        manager.createNotificationChannel(channel)
    }

    /** True when the app is allowed to show notifications (permission and settings). */
    fun areNotificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True until the notification permission prompt has been shown once. */
    fun shouldAskForPermission(context: Context): Boolean =
        !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED_PERMISSION, false)

    /** Remembers that the permission prompt was shown, so it is only asked for once. */
    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED_PERMISSION, true)
            .apply()
    }

    /**
     * Shows the "Task due" notification: the task title, then a middle dot and the project name
     * when there is one. Tapping it opens the app's launcher activity (so the lock comes first).
     */
    internal fun showReminder(context: Context, task: TaskEntity, projectName: String?) {
        if (!areNotificationsAllowed(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = task.title.trim().ifEmpty { "Untitled task" }
        val project = projectName?.trim().orEmpty()
        val text = if (project.isEmpty()) name else "$name \u00B7 $project"
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Task due")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (launchIntent != null) {
            val pending = PendingIntent.getActivity(
                context,
                notificationId(task.id),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(pending)
        }
        manager.notify(notificationId(task.id), builder.build())
    }

    private fun notificationId(taskId: String): Int = taskId.hashCode()
}
