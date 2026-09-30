package com.westly.neribovault.feature.posts.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.feature.posts.STATUS_SCHEDULED

/**
 * Runs at (about) the scheduled time of a post and shows the "Time to post" notification.
 * It re-reads the post first, so a post that was posted, deleted or moved in the meantime
 * never produces a stale reminder.
 */
class PostReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val postId = inputData.getString(KEY_POST_ID) ?: return Result.success()
        val app = applicationContext as? NeriboApp ?: return Result.success()
        val post = app.container.postsRepository.getById(postId) ?: return Result.success()
        val scheduledAt = post.scheduledAt
        val isStale = post.isDeleted ||
            post.status != STATUS_SCHEDULED ||
            scheduledAt == null ||
            scheduledAt > System.currentTimeMillis() + EARLY_TOLERANCE_MS
        if (isStale) return Result.success()
        PostReminderScheduler.showReminder(applicationContext, post)
        return Result.success()
    }

    companion object {
        /** Input data key holding the post id. */
        const val KEY_POST_ID = "post_id"

        /** A run this much earlier than the stored time is treated as belonging to an old time. */
        private const val EARLY_TOLERANCE_MS = 2 * 60_000L
    }
}
