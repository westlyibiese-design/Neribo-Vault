package com.westly.neribovault.data.cloud

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.westly.neribovault.NeriboApp
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Background sync every few hours while signed in and online. Never shows a notification. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? NeriboApp ?: return Result.success()
        val container = app.container
        val signedIn = withContext(Dispatchers.IO) { container.cloudAuth.isSignedIn() }
        if (!signedIn) return Result.success()
        return when (val result = container.syncEngine.syncNow()) {
            is SyncResult.Success -> Result.success()
            SyncResult.NotSignedIn -> Result.success()
            SyncResult.AlreadyRunning -> Result.success()
            is SyncResult.Failed ->
                if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/** Starts and stops automatic sync: the foreground trigger and the periodic WorkManager job. */
object SyncScheduler {
    const val WORK_NAME = "neribo_cloud_sync"
    private const val PERIOD_HOURS = 6L
    private const val FOREGROUND_MIN_INTERVAL_MILLIS = 5L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastForegroundAttempt = AtomicLong(0L)

    @Volatile
    private var started = false

    /** Call once from the main thread in Application.onCreate. */
    fun start(app: NeriboApp) {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onForeground(app)
                }
            },
        )
        scope.launch {
            if (app.container.cloudAuth.isSignedIn()) schedulePeriodic(app)
        }
    }

    /** Schedules the 6-hourly job. Keeps an existing schedule instead of restarting it. */
    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /** Syncs when the app comes to the foreground: signed in, online, at most every 5 minutes. */
    private fun onForeground(app: NeriboApp) {
        val nowElapsed = SystemClock.elapsedRealtime()
        scope.launch {
            val container = app.container
            if (!container.cloudAuth.isSignedIn()) return@launch
            if (!isOnline(app)) return@launch
            val lastAttempt = lastForegroundAttempt.get()
            if (lastAttempt != 0L && nowElapsed - lastAttempt < FOREGROUND_MIN_INTERVAL_MILLIS) return@launch
            val sinceLastSync = System.currentTimeMillis() - container.cloudAuth.config.lastSyncAt
            if (sinceLastSync in 0 until FOREGROUND_MIN_INTERVAL_MILLIS) return@launch
            lastForegroundAttempt.set(nowElapsed)
            container.syncEngine.syncNow()
        }
    }
}
