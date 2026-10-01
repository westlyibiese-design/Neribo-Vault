package com.westly.neribovault.feature.documents.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.feature.documents.ExpiryState
import com.westly.neribovault.feature.documents.expiryState

/**
 * Runs at (about) 9:00 AM on the reminder day and shows the "expires in N days" notification.
 * It re-reads the document first, so one that was deleted, already expired or moved to another
 * date in the meantime never produces a stale reminder.
 */
class DocumentReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.success()
        val app = applicationContext as? NeriboApp ?: return Result.success()
        val document = app.container.personalDocumentsRepository.getById(documentId)
            ?: return Result.success()
        val expiry = document.expiryDate
        if (document.isDeleted || expiry == null) return Result.success()
        val now = System.currentTimeMillis()
        if (expiryState(expiry, document.remindDaysBefore, now) == ExpiryState.Expired) {
            return Result.success()
        }
        val trigger = DocumentReminderScheduler.triggerAtMillis(expiry, document.remindDaysBefore)
        // A run much earlier than the stored time belongs to an old date that was since changed.
        if (trigger > now + EARLY_TOLERANCE_MS) return Result.success()
        if (DocumentReminderScheduler.showReminder(applicationContext, document)) {
            DocumentReminderScheduler.markNotified(applicationContext, document)
        }
        return Result.success()
    }

    companion object {
        /** Input data key holding the document id. */
        const val KEY_DOCUMENT_ID = "document_id"

        private const val EARLY_TOLERANCE_MS = 2 * 60_000L
    }
}
