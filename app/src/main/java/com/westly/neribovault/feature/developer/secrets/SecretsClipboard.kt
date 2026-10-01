package com.westly.neribovault.feature.developer.secrets

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Copies a secret to the clipboard and makes sure it does not stay there.
 *
 * The copy is marked sensitive where Android supports it (API 33+), and it is cleared after
 * [CLEAR_AFTER_MS] if it is still the current clip. The clip is recognised by a random label
 * (never by its content), so a later copy the person made elsewhere is left alone. A
 * background job covers the case where the app process is gone before the timer fires.
 */
object SecretsClipboard {
    /** How long a copied secret may stay on the clipboard. */
    const val CLEAR_AFTER_MS = 30_000L

    private const val LABEL_PREFIX = "neribo-secret-"
    private const val WORK_NAME = "neribo_secret_clipboard_clear"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var currentLabel: String? = null
    private var copiedAt = 0L
    private var clearJob: Job? = null

    /** Copies [value] as a sensitive clip and schedules it to be cleared. Call on the main thread. */
    fun copy(context: Context, value: String) {
        val app = context.applicationContext
        val manager = app.getSystemService(ClipboardManager::class.java) ?: return
        val label = LABEL_PREFIX + System.nanoTime()
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        manager.setPrimaryClip(clip)

        appContext = app
        currentLabel = label
        copiedAt = System.currentTimeMillis()
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLEAR_AFTER_MS)
            clearNow()
        }
        scheduleBackupClear(app, label)
    }

    /** Clears the clipboard now if it still holds the clip this object put there. */
    fun clearNow() {
        val app = appContext ?: return
        val label = currentLabel ?: return
        currentLabel = null
        clearJob?.cancel()
        clearJob = null
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
        clearIfLabel(app, label)
    }

    /** Clears the clipboard if the timer should already have fired (for example after a freeze). */
    fun clearIfExpired() {
        if (currentLabel == null) return
        if (System.currentTimeMillis() - copiedAt >= CLEAR_AFTER_MS) clearNow()
    }

    /**
     * Clears the clipboard if its current clip carries [label]. When Android hides the clip
     * from this app (it is in the background), the clip is cleared anyway: leaving a secret
     * behind is the worse mistake.
     */
    internal fun clearIfLabel(context: Context, label: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val currentClipLabel: String? = try {
            manager.primaryClipDescription?.label?.toString()
        } catch (e: Exception) {
            null
        }
        if (currentClipLabel != null && currentClipLabel != label) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Nothing more can be done if the system refuses.
        }
    }

    private fun scheduleBackupClear(app: Context, label: String) {
        try {
            val request = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                .setInitialDelay(CLEAR_AFTER_MS + 5_000L, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(ClipboardClearWorker.KEY_LABEL to label))
                .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            // The in-process timer still runs.
        }
    }
}

/** Backup for the clipboard timer: runs if the app process was stopped before it fired. */
class ClipboardClearWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val label = inputData.getString(KEY_LABEL) ?: return Result.success()
        SecretsClipboard.clearIfLabel(applicationContext, label)
        return Result.success()
    }

    companion object {
        /** Input data key holding the random label of the clip to clear. */
        const val KEY_LABEL = "clip_label"
    }
}
