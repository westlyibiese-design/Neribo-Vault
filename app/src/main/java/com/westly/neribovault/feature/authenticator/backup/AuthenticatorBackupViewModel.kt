package com.westly.neribovault.feature.authenticator.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.CheckResult
import com.westly.neribovault.feature.authenticator.add.sameAccount
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.OtpEntry
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import com.westly.neribovault.feature.authenticator.security.AuthenticatorPrefs
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_FILE_BYTES = 5 * 1024 * 1024
private const val MSG_TOO_BIG = "That file is too big to be a backup"
private const val MSG_NOT_A_BACKUP = "That is not a backup file from the Authenticator"
private const val MSG_WRONG_PASSWORD = "Wrong password or damaged file"
private const val MSG_CANNOT_READ_FILE = "Could not read that file. Please try again."
private const val MSG_SAVE_FAILED = "Could not save the backup. Please try again."
private const val MSG_NO_STORAGE = "Secure storage is not available on this phone"

/** One account of a backup file in the preview. */
class RestoreRow(val account: BackupAccount, val isDuplicate: Boolean)

/** What the restore preview sheet lists. */
class RestorePreview(val rows: List<RestoreRow>, val skipped: Int)

/** Everything the backup screen draws. Secret keys live only inside [preview] and are never drawn. */
data class BackupUiState(
    val lastExportAt: Long = 0L,
    val accountCount: Int = 0,
    val isBusy: Boolean = false,
    val askPassword: Boolean = false,
    val passwordError: String? = null,
    val preview: RestorePreview? = null,
    val checked: Set<Int> = emptySet(),
    /** A one-time line for the snackbar; the screen calls [AuthenticatorBackupViewModel.messageShown]. */
    val message: String? = null,
    val selfTest: List<CheckResult>? = null,
)

/** Makes the encrypted backup file and restores accounts from one. */
class AuthenticatorBackupViewModel(
    private val accounts: TotpAccountsRepository,
) : ViewModel() {

    private val saver = BackupRestoreSaver(accounts)
    private val local = MutableStateFlow(BackupUiState())

    /** The encrypted bytes of a chosen file while the password is asked for. Not secret in itself. */
    private var pendingFile: ByteArray? = null

    val state: StateFlow<BackupUiState> = combine(
        local,
        accounts.observeAll().map { it.size },
    ) { ui, count -> ui.copy(accountCount = count) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Reads the date of the last backup. */
    fun loadLastExport(context: Context) {
        local.update { it.copy(lastExportAt = AuthenticatorPrefs.lastExportAt(context.applicationContext)) }
    }

    fun postMessage(text: String) {
        local.update { it.copy(message = text) }
    }

    fun messageShown() {
        local.update { it.copy(message = null) }
    }

    // ---- Making a backup ----------------------------------------------------------------

    /** Writes an encrypted backup of every non-deleted account to [uri]. */
    fun createBackup(context: Context, uri: Uri, password: String) {
        if (local.value.isBusy) return
        val appContext = context.applicationContext
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { writeBackup(appContext, uri, password) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val message = when {
                outcome == null -> MSG_SAVE_FAILED
                outcome.saved == 0 -> MSG_SAVE_FAILED
                outcome.unreadable == 0 -> "Backup saved: ${countText(outcome.saved)}"
                else -> "Backup saved: ${countText(outcome.saved)}. ${leftOutText(outcome.unreadable)}"
            }
            local.update {
                it.copy(
                    isBusy = false,
                    message = message,
                    lastExportAt = if (outcome != null && outcome.saved > 0) {
                        AuthenticatorPrefs.lastExportAt(appContext)
                    } else {
                        it.lastExportAt
                    },
                )
            }
        }
    }

    private class WriteOutcome(val saved: Int, val unreadable: Int)

    private suspend fun writeBackup(context: Context, uri: Uri, password: String): WriteOutcome {
        val list = ArrayList<BackupAccount>()
        try {
            var unreadable = 0
            val rows = accounts.getAllIncludingTrashed().filter { !it.isDeleted }.sortedBy { it.sortOrder }
            for (row in rows) {
                val secret = AuthenticatorCrypto.decrypt(row.secretCipher, row.secretIv)
                if (secret == null) {
                    unreadable++
                    continue
                }
                list.add(
                    BackupAccount(
                        issuer = row.issuer,
                        accountName = row.accountName,
                        secret = secret,
                        algorithm = row.algorithm,
                        digits = row.digits,
                        periodSeconds = row.periodSeconds,
                        notes = row.notes,
                        isPinned = row.isPinned,
                    ),
                )
            }
            // A backup with nothing in it must never count as a backup.
            if (list.isEmpty()) return WriteOutcome(0, unreadable)
            val bytes = BackupFile.write(list, password, System.currentTimeMillis())
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open the file")
            stream.use {
                it.write(bytes)
                it.flush()
            }
            AuthenticatorPrefs.markExported(context)
            return WriteOutcome(list.size, unreadable)
        } finally {
            list.forEach { it.secret.fill(0) }
        }
    }

    // ---- Restoring ----------------------------------------------------------------------

    /** Reads the chosen file (at most 5 MB) and asks for its password. */
    fun onFileChosen(context: Context, uri: Uri) {
        if (local.value.isBusy) return
        val appContext = context.applicationContext
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val bytes = try {
                withContext(Dispatchers.IO) { readLimited(appContext, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when {
                bytes == null -> local.update { it.copy(isBusy = false, message = MSG_CANNOT_READ_FILE) }
                bytes.size > MAX_FILE_BYTES -> local.update { it.copy(isBusy = false, message = MSG_TOO_BIG) }
                else -> {
                    pendingFile = bytes
                    local.update { it.copy(isBusy = false, askPassword = true, passwordError = null) }
                }
            }
        }
    }

    /** Reads up to one byte more than the limit, so a too-big file is noticed without loading it all. */
    private fun readLimited(context: Context, uri: Uri): ByteArray? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() <= MAX_FILE_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    /** Tries [password] on the chosen file. The password is not kept. */
    fun submitPassword(password: String) {
        val bytes = pendingFile ?: return
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true, passwordError = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { BackupFile.read(bytes, password) }
            when (result) {
                is BackupReadResult.NotABackup -> {
                    pendingFile = null
                    local.update { it.copy(isBusy = false, askPassword = false, message = MSG_NOT_A_BACKUP) }
                }
                is BackupReadResult.WrongPasswordOrDamaged ->
                    local.update { it.copy(isBusy = false, passwordError = MSG_WRONG_PASSWORD) }
                is BackupReadResult.Success -> {
                    pendingFile = null
                    showPreview(result)
                }
            }
        }
    }

    /** Closes the password dialog and forgets the chosen file. */
    fun cancelPassword() {
        pendingFile = null
        local.update { it.copy(askPassword = false, passwordError = null, isBusy = false) }
    }

    private suspend fun showPreview(result: BackupReadResult.Success) {
        val rows = try {
            markDuplicates(result.accounts)
        } catch (e: CancellationException) {
            result.accounts.forEach { it.secret.fill(0) }
            throw e
        } catch (e: Exception) {
            result.accounts.forEach { it.secret.fill(0) }
            local.update { it.copy(isBusy = false, askPassword = false, message = MSG_NO_STORAGE) }
            return
        }
        if (rows.isEmpty()) {
            local.update {
                it.copy(
                    isBusy = false,
                    askPassword = false,
                    message = if (result.skipped > 0) "No valid accounts in that file" else "That backup has no accounts",
                )
            }
            return
        }
        val old = local.value.preview
        local.update {
            it.copy(
                isBusy = false,
                askPassword = false,
                preview = RestorePreview(rows, result.skipped),
                checked = rows.indices.filter { index -> !rows[index].isDuplicate }.toSet(),
            )
        }
        release(old)
    }

    /**
     * Marks each account that matches an existing (not deleted) account, or an earlier account of
     * the file, as a duplicate. Decrypted secrets of existing accounts are zero-filled afterwards.
     */
    private suspend fun markDuplicates(items: List<BackupAccount>): List<RestoreRow> =
        withContext(Dispatchers.Default) {
            val existing = ArrayList<OtpEntry>()
            try {
                for (account in accounts.getAllIncludingTrashed()) {
                    if (account.isDeleted) continue
                    val algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm } ?: continue
                    val secret = AuthenticatorCrypto.decrypt(account.secretCipher, account.secretIv) ?: continue
                    existing.add(
                        OtpEntry(
                            account.issuer,
                            account.accountName,
                            secret,
                            algorithm,
                            account.digits,
                            account.periodSeconds,
                        ),
                    )
                }
                // These entries share the secret arrays of [items]; they are never zero-filled here.
                val entries = items.map { toEntry(it) }
                items.indices.map { index ->
                    val matchesExisting = existing.any { sameAccount(it, entries[index]) }
                    val matchesEarlier = (0 until index).any { sameAccount(entries[it], entries[index]) }
                    RestoreRow(items[index], matchesExisting || matchesEarlier)
                }
            } finally {
                existing.forEach { it.secret.fill(0) }
            }
        }

    private fun toEntry(item: BackupAccount): OtpEntry = OtpEntry(
        item.issuer,
        item.accountName,
        item.secret,
        OtpAlgorithm.values().firstOrNull { it.name == item.algorithm } ?: OtpAlgorithm.SHA1,
        item.digits,
        item.periodSeconds,
    )

    fun toggle(index: Int) {
        val preview = local.value.preview ?: return
        val row = preview.rows.getOrNull(index) ?: return
        if (row.isDuplicate) return
        local.update {
            val next = if (index in it.checked) it.checked - index else it.checked + index
            it.copy(checked = next)
        }
    }

    /** Checks every account that is not a duplicate, or unchecks all when they are all checked. */
    fun toggleAll() {
        val preview = local.value.preview ?: return
        val selectable = preview.rows.indices.filter { !preview.rows[it].isDuplicate }.toSet()
        local.update { it.copy(checked = if (it.checked.containsAll(selectable)) emptySet() else selectable) }
    }

    /** Throws the preview away, zero-filling every secret in it. */
    fun cancelPreview() {
        release(local.value.preview)
        local.update { it.copy(preview = null, checked = emptySet()) }
    }

    /** Adds the checked accounts. Existing accounts are never changed or deleted. */
    fun saveChecked() {
        val current = local.value
        val preview = current.preview ?: return
        if (current.isBusy) return
        val chosen = preview.rows.indices
            .filter { it in current.checked && !preview.rows[it].isDuplicate }
            .map { preview.rows[it].account }
        if (chosen.isEmpty()) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val result = saver.save(chosen)
            // The saver zero-fills the secrets it was given; the rest of the preview is cleared here.
            release(preview)
            val message = when {
                result.saved == 0 -> MSG_NO_STORAGE
                result.failed == 0 -> if (result.saved == 1) "Added 1 account" else "Added ${result.saved} accounts"
                else -> {
                    val added = if (result.saved == 1) "Added 1 account" else "Added ${result.saved} accounts"
                    val failed = if (result.failed == 1) "1 account" else "${result.failed} accounts"
                    "$added. $failed could not be saved."
                }
            }
            local.update { it.copy(isBusy = false, preview = null, checked = emptySet(), message = message) }
        }
    }

    // ---- Debug --------------------------------------------------------------------------

    fun runSelfTest() {
        viewModelScope.launch {
            val results = withContext(Dispatchers.Default) { runBackupSelfTest() }
            local.update { it.copy(selfTest = results) }
        }
    }

    fun dismissSelfTest() {
        local.update { it.copy(selfTest = null) }
    }

    override fun onCleared() {
        release(local.value.preview)
        pendingFile = null
        super.onCleared()
    }

    private fun release(preview: RestorePreview?) {
        preview?.rows?.forEach { it.account.secret.fill(0) }
    }

    private fun countText(n: Int): String = if (n == 1) "1 account" else "$n accounts"

    private fun leftOutText(n: Int): String =
        if (n == 1) "1 account could not be read and was left out." else "$n accounts could not be read and were left out."
}
