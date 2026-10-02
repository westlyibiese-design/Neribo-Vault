package com.westly.neribovault.feature.backup

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.di.AppContainer
import com.westly.neribovault.core.util.formatDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Smallest backup password allowed. */
const val MIN_PASSWORD_LENGTH = 8

/** What the Backup screen is doing. */
sealed interface BackupPhase {
    /** Nothing running: the forms are shown. */
    data object Idle : BackupPhase

    /** Creating a backup or restoring one; [message] is the current step. */
    data class Working(val message: String, val isRestore: Boolean) : BackupPhase

    /** A backup was written. */
    data class BackupDone(val sizeText: String, val lines: List<String>) : BackupPhase

    /** The owner must confirm replacing everything with [lines]. */
    data class ConfirmRestore(val lines: List<String>, val createdText: String?) : BackupPhase

    /** The restore finished. [warnings] is empty after a clean restore. */
    data class RestoreDone(val warnings: List<String>) : BackupPhase
}

/** Everything the Backup screen shows. */
data class BackupUiState(
    val phase: BackupPhase = BackupPhase.Idle,
    val password: String = "",
    val confirmPassword: String = "",
    val restorePassword: String = "",
    val pickedRestoreName: String? = null,
    val error: String? = null,
    val restoreError: String? = null,
) {
    val passwordTooShort: Boolean get() = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val passwordsDiffer: Boolean get() = confirmPassword.isNotEmpty() && confirmPassword != password
    val canCreate: Boolean
        get() = password.length >= MIN_PASSWORD_LENGTH && password == confirmPassword
}

/** Creates and restores encrypted backups. Passwords live only in this ViewModel's memory. */
class BackupViewModel(
    private val container: AppContainer,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var pickedRestoreUri: Uri? = null
    private var prepared: PreparedBackup? = null
    private var job: Job? = null

    private val reader = BackupReader(application, container.database)

    // ---- Form input ---------------------------------------------------------------------

    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }

    fun onRestorePassword(value: String) = _state.update { it.copy(restorePassword = value, restoreError = null) }

    /** The suggested file name, for example `NeriboVault-2026-09-29.nvbackup` (today's date). */
    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "NeriboVault-$day.nvbackup"
    }

    /** True when something else (a sync) is using the database right now. */
    private fun syncBusy(): Boolean = container.syncEngine.isBusy

    private val busyMessage = "A cloud sync is running. Wait for it to finish, then try again."

    // ---- Create -------------------------------------------------------------------------

    /** The owner picked where to save. Returns false (and shows why) if it cannot start. */
    fun canStartCreate(): Boolean {
        val current = _state.value
        if (!current.canCreate) return false
        if (syncBusy()) {
            _state.update { it.copy(error = busyMessage) }
            return false
        }
        return true
    }

    fun createBackup(target: Uri) {
        val password = _state.value.password
        if (job?.isActive == true) return
        if (syncBusy()) {
            _state.update { it.copy(error = busyMessage) }
            return
        }
        _state.update { it.copy(phase = BackupPhase.Working("Securing your backup\u2026", false), error = null) }
        job = viewModelScope.launch {
            try {
                val summary = container.syncEngine.withSyncPaused {
                    withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        val output = application.contentResolver.openOutputStream(target, "w")
                            ?: throw BackupException("That location could not be written to.")
                        BackupWriter(application, container.database).write(
                            output = output,
                            password = password,
                            progress = { message -> setWorking(message, false) },
                            checkActive = { context.ensureActive() },
                        )
                    }
                }
                _state.update {
                    it.copy(
                        phase = BackupPhase.BackupDone(
                            sizeText = formatBytes(summary.bytes),
                            lines = summaryLines(summary.tableCounts, summary.photoCount, summary.documentCount),
                        ),
                        password = "",
                        confirmPassword = "",
                    )
                }
            } catch (e: CancellationException) {
                removePartialFile(target)
                throw e
            } catch (e: Exception) {
                removePartialFile(target)
                val message = (e as? BackupException)?.message
                    ?: "The backup could not be created. Check that there is enough space and try again."
                _state.update { it.copy(phase = BackupPhase.Idle, error = message) }
            }
        }
    }

    private fun removePartialFile(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(application.contentResolver, uri) }
    }

    // ---- Restore ------------------------------------------------------------------------

    fun onRestoreFilePicked(uri: Uri, displayName: String?) {
        pickedRestoreUri = uri
        _state.update { it.copy(pickedRestoreName = displayName ?: "Selected backup", restoreError = null) }
    }

    /** Decrypts and checks the chosen backup, then asks for confirmation. */
    fun checkBackup() {
        val uri = pickedRestoreUri ?: return
        val password = _state.value.restorePassword
        if (password.isEmpty() || job?.isActive == true) return
        if (syncBusy()) {
            _state.update { it.copy(restoreError = busyMessage) }
            return
        }
        _state.update {
            it.copy(phase = BackupPhase.Working("Opening your backup\u2026", true), restoreError = null)
        }
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = application.contentResolver.openInputStream(uri)
                        ?: throw BackupException("That file could not be opened.")
                    input.use { reader.prepare(it, password) }
                }
                prepared = result
                _state.update {
                    it.copy(
                        phase = BackupPhase.ConfirmRestore(
                            lines = summaryLines(result.tableCounts, result.photoCount, result.documentCount),
                            createdText = result.createdAt.takeIf { at -> at > 0L }?.let { at -> formatDateTime(at) },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? BackupException)?.message ?: "Wrong password or damaged file."
                _state.update { it.copy(phase = BackupPhase.Idle, restoreError = message) }
            }
        }
    }

    /** The owner confirmed. Replaces everything on this phone with the prepared backup. */
    fun confirmRestore() {
        val backup = prepared ?: return
        if (job?.isActive == true) return
        if (syncBusy()) {
            _state.update { it.copy(phase = BackupPhase.Idle, restoreError = busyMessage) }
            return
        }
        _state.update { it.copy(phase = BackupPhase.Working("Restoring your data\u2026", true)) }
        // NonCancellable: once the database is replaced the file swap must not be cut short.
        job = viewModelScope.launch {
            try {
                val report = withContext(NonCancellable) {
                    container.syncEngine.withSyncPaused {
                        withContext(Dispatchers.IO) {
                            reader.restore(backup) { message -> setWorking(message, true) }
                        }
                    }
                }
                prepared = null
                _state.update {
                    it.copy(
                        phase = BackupPhase.RestoreDone(report.warnings),
                        restorePassword = "",
                        pickedRestoreName = null,
                    )
                }
                pickedRestoreUri = null
            } catch (e: Exception) {
                val message = (e as? BackupException)?.message
                    ?: "The restore could not be completed. Nothing on this phone was changed."
                _state.update { it.copy(phase = BackupPhase.Idle, restoreError = message) }
                withContext(NonCancellable) { withContext(Dispatchers.IO) { reader.discard(backup) } }
                prepared = null
            }
        }
    }

    /** The owner cancelled at the confirmation. Nothing was changed. */
    fun cancelRestore() {
        val backup = prepared
        prepared = null
        _state.update { it.copy(phase = BackupPhase.Idle) }
        if (backup != null) {
            viewModelScope.launch(Dispatchers.IO) { reader.discard(backup) }
        }
    }

    /** Back to the forms after a success message. */
    fun dismissResult() {
        _state.update { it.copy(phase = BackupPhase.Idle) }
    }

    private fun setWorking(message: String, isRestore: Boolean) {
        _state.update { current ->
            if (current.phase is BackupPhase.Working) {
                current.copy(phase = BackupPhase.Working(message, isRestore))
            } else {
                current
            }
        }
    }

    override fun onCleared() {
        // Leaves no decrypted temporary file behind if the owner walks away at the confirmation.
        val backup = prepared
        if (backup != null && job?.isActive != true) backup.zipFile.delete()
        super.onCleared()
    }
}

/** A file size such as "812 KB" or "4.2 MB". */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
