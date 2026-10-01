package com.westly.neribovault.feature.documents.reminders

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
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.feature.documents.ExpiryState
import com.westly.neribovault.feature.documents.daysUntilExpiry
import com.westly.neribovault.feature.documents.expiryState
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels "document expires soon" reminders. Each document with an expiry date gets
 * one unique WorkManager job named `document_reminder_<id>` that fires at 9:00 AM local time on
 * the day `expiryDate - remindDaysBefore`. WorkManager is approximate: a reminder can arrive a few
 * minutes late. Nothing here needs exact alarms or a manifest entry. Notifications carry only the
 * title and the expiry, never the contents of a document.
 */
object DocumentReminderScheduler {
    const val CHANNEL_ID = "document_reminders"

    private const val CHANNEL_NAME = "Document reminders"
    private const val PREFS_NAME = "document_reminders"
    private const val KEY_ASKED_PERMISSION = "asked_notification_permission"
    private const val KEY_NOTIFIED_PREFIX = "notified_"
    private const val REMINDER_HOUR = 9

    /** When the reminder time has already passed but the document is still valid, fire this soon. */
    private const val LATE_DELAY_MS = 60_000L

    /** The unique work name for one document. */
    fun workName(documentId: String): String = "document_reminder_$documentId"

    /** 9:00 AM local time on the day [remindDaysBefore] days before the expiry day. */
    internal fun triggerAtMillis(expiryDate: Long, remindDaysBefore: Int): Long {
        val zone = ZoneId.systemDefault()
        val expiryDay = Instant.ofEpochMilli(expiryDate).atZone(zone).toLocalDate()
        return expiryDay
            .minusDays(remindDaysBefore.toLong())
            .atTime(REMINDER_HOUR, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Makes the reminder match [document]: a live document with an expiry date that has not yet
     * passed gets (or keeps) a reminder, anything else has its reminder cancelled. If the reminder
     * time already passed but the document has not expired, it fires in about a minute, once.
     * Safe to call after every save, restore and delete, and idempotent (REPLACE).
     */
    fun sync(context: Context, document: PersonalDocumentEntity) {
        val appContext = context.applicationContext
        val expiry = document.expiryDate
        val now = System.currentTimeMillis()
        if (document.isDeleted || expiry == null) {
            cancel(appContext, document.id)
            return
        }
        if (expiryState(expiry, document.remindDaysBefore, now) == ExpiryState.Expired) {
            cancel(appContext, document.id)
            return
        }
        val trigger = triggerAtMillis(expiry, document.remindDaysBefore)
        val delayMs = if (trigger > now) {
            trigger - now
        } else if (wasNotified(appContext, document)) {
            // Already warned for exactly this expiry and lead time. Do not nag on every sync.
            cancelWork(appContext, document.id)
            return
        } else {
            LATE_DELAY_MS
        }
        ensureChannel(appContext)
        val request = OneTimeWorkRequestBuilder<DocumentReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DocumentReminderWorker.KEY_DOCUMENT_ID to document.id))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(workName(document.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** Runs [sync] for every document. Used once when the Documents list first opens. */
    fun syncAll(context: Context, documents: List<PersonalDocumentEntity>) {
        documents.forEach { sync(context, it) }
    }

    /** Cancels the pending reminder for [documentId] and removes its notification if showing. */
    fun cancel(context: Context, documentId: String) {
        val appContext = context.applicationContext
        cancelWork(appContext, documentId)
        appContext.getSystemService(NotificationManager::class.java)?.cancel(notificationId(documentId))
    }

    /** Cancels everything for a document that is gone for good, including the "already warned" mark. */
    fun forget(context: Context, documentId: String) {
        val appContext = context.applicationContext
        cancel(appContext, documentId)
        prefs(appContext).edit().remove(KEY_NOTIFIED_PREFIX + documentId).apply()
    }

    /** Creates the `document_reminders` channel. Does nothing before Android 8 or if it exists. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A heads-up before an important document expires."
        }
        manager.createNotificationChannel(channel)
    }

    /** True when the app is allowed to show notifications (permission and settings). */
    fun areNotificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** True until the notification permission prompt has been shown once. */
    fun shouldAskForPermission(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_ASKED_PERMISSION, false)

    /** Remembers that the permission prompt was shown, so it is only asked for once. */
    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_PERMISSION, true).apply()
    }

    /**
     * Shows "<Title> expires in N days" (or "expires today"). Tapping it opens the app's launcher
     * activity, so the app lock screen appears first. Returns true when it was shown.
     */
    internal fun showReminder(context: Context, document: PersonalDocumentEntity): Boolean {
        if (!areNotificationsAllowed(context)) return false
        val expiry = document.expiryDate ?: return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val name = document.title.trim().ifEmpty { "Untitled document" }
        val days = daysUntilExpiry(expiry)
        val text = when {
            days <= 0 -> "$name expires today"
            days == 1 -> "$name expires in 1 day"
            else -> "$name expires in $days days"
        }
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Document expiring")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        if (launchIntent != null) {
            val pending = PendingIntent.getActivity(
                context,
                notificationId(document.id),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(pending)
        }
        manager.notify(notificationId(document.id), builder.build())
        return true
    }

    /** Remembers that the owner was warned for this document's current expiry and lead time. */
    internal fun markNotified(context: Context, document: PersonalDocumentEntity) {
        prefs(context).edit()
            .putString(KEY_NOTIFIED_PREFIX + document.id, signatureOf(document))
            .apply()
    }

    private fun wasNotified(context: Context, document: PersonalDocumentEntity): Boolean =
        prefs(context).getString(KEY_NOTIFIED_PREFIX + document.id, null) == signatureOf(document)

    private fun signatureOf(document: PersonalDocumentEntity): String =
        "${document.expiryDate}:${document.remindDaysBefore}"

    private fun cancelWork(context: Context, documentId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(documentId))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun notificationId(documentId: String): Int = documentId.hashCode()
}
