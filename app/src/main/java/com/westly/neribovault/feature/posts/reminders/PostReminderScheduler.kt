package com.westly.neribovault.feature.posts.reminders

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
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.feature.posts.STATUS_SCHEDULED
import com.westly.neribovault.feature.posts.platformInfo
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels the "Time to post" reminders. Each scheduled post gets one unique
 * WorkManager job named `post_reminder_<postId>`. WorkManager is approximate: a reminder can
 * arrive a few minutes late. Nothing here needs exact alarms or a manifest entry.
 */
object PostReminderScheduler {
    const val CHANNEL_ID = "post_reminders"

    private const val CHANNEL_NAME = "Post reminders"
    private const val PREFS_NAME = "post_reminders"
    private const val KEY_ASKED_PERMISSION = "asked_notification_permission"

    /** The unique work name for one post. */
    fun workName(postId: String): String = "post_reminder_$postId"

    /**
     * Makes the reminder match [post]: a scheduled post with a future time gets (or keeps) a
     * reminder at that time, anything else has its reminder cancelled. Safe to call after
     * every save, restore, mark-as-posted and delete.
     */
    fun sync(context: Context, post: SocialPostEntity) {
        val appContext = context.applicationContext
        val at = post.scheduledAt
        val now = System.currentTimeMillis()
        if (post.isDeleted || post.status != STATUS_SCHEDULED || at == null || at <= now) {
            cancel(appContext, post.id)
            return
        }
        ensureChannel(appContext)
        val request = OneTimeWorkRequestBuilder<PostReminderWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(PostReminderWorker.KEY_POST_ID to post.id))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(workName(post.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels the pending reminder for [postId] and removes its notification if it is showing. */
    fun cancel(context: Context, postId: String) {
        val appContext = context.applicationContext
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(postId))
        appContext.getSystemService(NotificationManager::class.java)?.cancel(notificationId(postId))
    }

    /** Creates the `post_reminders` channel. Does nothing before Android 8 or if it exists. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A gentle nudge when a scheduled post is due."
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

    /** Shows the "Time to post" notification. Tapping it opens the app's launcher activity. */
    internal fun showReminder(context: Context, post: SocialPostEntity) {
        if (!areNotificationsAllowed(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val name = post.title.trim().ifEmpty { "Untitled post" }
        val text = "$name \u00B7 ${platformInfo(post.platform).displayName}"
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Time to post")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (launchIntent != null) {
            val pending = PendingIntent.getActivity(
                context,
                notificationId(post.id),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(pending)
        }
        manager.notify(notificationId(post.id), builder.build())
    }

    private fun notificationId(postId: String): Int = postId.hashCode()
}
