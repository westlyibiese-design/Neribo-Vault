package com.westly.neribovault

import android.app.Application
import android.util.Log
import com.westly.neribovault.core.di.AppContainer
import com.westly.neribovault.data.cloud.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application class. Owns the [AppContainer] and purges old trash on start. */
class NeriboApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Created lazily on first use. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.start(this)
        appScope.launch {
            try {
                container.purgeExpiredTrash(System.currentTimeMillis() - TRASH_RETENTION_MILLIS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("NeriboApp", "Trash purge failed", e)
            }
        }
    }

    private companion object {
        const val TRASH_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
    }
}
