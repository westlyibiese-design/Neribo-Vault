package com.westly.neribovault.feature.authenticator.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.engine.Base32
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.OtpEntry
import com.westly.neribovault.feature.authenticator.engine.OtpParseResult
import com.westly.neribovault.feature.authenticator.engine.OtpUri
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_SECRET_BYTES = 10
private const val MAX_NAME = 60
private const val MSG_NOT_A_CODE = "That is not an authenticator link or setup key"
private const val MSG_ALREADY = "That account is already added"
private const val MSG_NO_STORAGE = "Secure storage is not available on this phone"

/** One parsed account in the preview. */
class ImportRow(val entry: OtpEntry, val isDuplicate: Boolean)

/** What the preview sheet lists. */
class ImportPreview(
    val rows: List<ImportRow>,
    val skippedCounterBased: Int,
    val skippedUnsupported: Int,
)

/** The result of a finished save, handed to the sheet once. */
class ImportFinished(val saved: Int, val failed: Int)

/** Everything the add flow draws. Secret keys live only inside [preview] and are never drawn. */
data class ImportUiState(
    val preview: ImportPreview? = null,
    val checked: Set<Int> = emptySet(),
    val isBusy: Boolean = false,
    /** A short line shown inside the menu sheet (a failed scan) or the preview sheet (a failed save). */
    val message: String? = null,
    val pasteError: String? = null,
    val needsNames: Boolean = false,
    val finished: ImportFinished? = null,
)

/** Reads links and export codes, marks duplicates and saves what the owner chose. */
class ImportViewModel(
    private val accounts: TotpAccountsRepository,
) : ViewModel() {

    private val saver = ImportSaver(accounts)
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Reads the text of a scanned QR code. The text is not kept. */
    fun importScanned(raw: String) {
        viewModelScope.launch(Dispatchers.Default) {
            when (val result = OtpUri.parse(raw)) {
                is OtpParseResult.Failure -> _state.update { it.copy(message = result.reason) }
                is OtpParseResult.Success -> showPreview(result)
            }
        }
    }

    /** Reads pasted text: a link, an export link or a bare setup key. The text is not kept. */
    fun submitPasted(text: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val trimmed = text.trim()
            when (val result = OtpUri.parse(trimmed)) {
                is OtpParseResult.Success -> showPreview(result)
                is OtpParseResult.Failure -> {
                    if (trimmed.startsWith("otpauth", ignoreCase = true)) {
                        _state.update { it.copy(pasteError = result.reason, needsNames = false) }
                    } else if (isBareKey(trimmed)) {
                        _state.update { it.copy(pasteError = null, needsNames = true) }
                    } else {
                        _state.update { it.copy(pasteError = MSG_NOT_A_CODE, needsNames = false) }
                    }
                }
            }
        }
    }

    /** Shows a short line inside the menu sheet, such as a scanner failure. */
    fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Called when the pasted text is edited, so old errors and the name fields go away. */
    fun pasteEdited() {
        _state.update { it.copy(pasteError = null, needsNames = false) }
    }

    /** Saves a bare setup key with SHA-1, 6 digits and a 30-second step. */
    fun addBareKey(text: String, service: String, account: String) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, pasteError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val secret = Base32.decode(text.trim())
            if (secret == null || secret.size < MIN_SECRET_BYTES) {
                secret?.fill(0)
                _state.update { it.copy(isBusy = false, pasteError = MSG_NOT_A_CODE, needsNames = false) }
                return@launch
            }
            val entry = OtpEntry(
                issuer = service.trim().take(MAX_NAME),
                accountName = account.trim().take(MAX_NAME),
                secret = secret,
                algorithm = OtpAlgorithm.SHA1,
                digits = 6,
                periodSeconds = 30,
            )
            val duplicate = try {
                markDuplicates(listOf(entry)).first().isDuplicate
            } catch (e: Exception) {
                entry.secret.fill(0)
                _state.update { it.copy(isBusy = false, pasteError = MSG_NO_STORAGE) }
                return@launch
            }
            if (duplicate) {
                entry.secret.fill(0)
                _state.update { it.copy(isBusy = false, pasteError = MSG_ALREADY) }
                return@launch
            }
            val result = saver.save(listOf(entry))
            if (result.saved > 0) {
                _state.update { it.copy(isBusy = false, finished = ImportFinished(result.saved, result.failed)) }
            } else {
                _state.update { it.copy(isBusy = false, pasteError = MSG_NO_STORAGE) }
            }
        }
    }

    fun toggle(index: Int) {
        val preview = _state.value.preview ?: return
        val row = preview.rows.getOrNull(index) ?: return
        if (row.isDuplicate) return
        _state.update {
            val next = if (index in it.checked) it.checked - index else it.checked + index
            it.copy(checked = next)
        }
    }

    /** Checks every account that is not a duplicate, or unchecks all when they are all checked. */
    fun toggleAll() {
        val preview = _state.value.preview ?: return
        val selectable = preview.rows.indices.filter { !preview.rows[it].isDuplicate }.toSet()
        _state.update {
            it.copy(checked = if (it.checked.containsAll(selectable)) emptySet() else selectable)
        }
    }

    /** Saves the checked accounts in the order shown. */
    fun saveChecked() {
        val current = _state.value
        val preview = current.preview ?: return
        if (current.isBusy) return
        val chosen = preview.rows.indices
            .filter { it in current.checked && !preview.rows[it].isDuplicate }
            .map { preview.rows[it].entry }
        if (chosen.isEmpty()) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            val result = saver.save(chosen)
            // The saver zero-fills the secrets it was given, so the preview can never be reused.
            release(preview)
            _state.update {
                if (result.saved > 0) {
                    it.copy(
                        isBusy = false,
                        preview = null,
                        checked = emptySet(),
                        finished = ImportFinished(result.saved, result.failed),
                    )
                } else {
                    it.copy(isBusy = false, preview = null, checked = emptySet(), message = MSG_NO_STORAGE)
                }
            }
        }
    }

    /** Throws away everything, zero-filling every secret still held. */
    fun reset() {
        release(_state.value.preview)
        _state.value = ImportUiState()
    }

    override fun onCleared() {
        release(_state.value.preview)
        super.onCleared()
    }

    private suspend fun showPreview(result: OtpParseResult.Success) {
        val rows = try {
            markDuplicates(result.entries)
        } catch (e: Exception) {
            result.entries.forEach { it.secret.fill(0) }
            _state.update { it.copy(message = MSG_NO_STORAGE, pasteError = MSG_NO_STORAGE) }
            return
        }
        val preview = ImportPreview(rows, result.skippedCounterBased, result.skippedUnsupported)
        val old = _state.value.preview
        val checked = rows.indices.filter { !rows[it].isDuplicate }.toSet()
        _state.update {
            it.copy(
                preview = preview,
                checked = checked,
                message = null,
                pasteError = null,
                needsNames = false,
            )
        }
        if (old != null && old !== preview) release(old)
    }

    /**
     * Marks each entry that matches an existing (not deleted) account, or an earlier entry of the
     * same list, as a duplicate. Decrypted secrets of existing accounts are zero-filled afterwards.
     */
    private suspend fun markDuplicates(entries: List<OtpEntry>): List<ImportRow> =
        withContext(Dispatchers.Default) {
            val existing = ArrayList<OtpEntry>()
            try {
                for (account in accounts.getAllIncludingTrashed()) {
                    if (account.isDeleted) continue
                    val algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm }
                        ?: continue
                    val secret = AuthenticatorCrypto.decrypt(account.secretCipher, account.secretIv)
                        ?: continue
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
                val rows = ArrayList<ImportRow>()
                for ((index, entry) in entries.withIndex()) {
                    val matchesExisting = existing.any { sameAccount(it, entry) }
                    val matchesEarlier = (0 until index).any { sameAccount(entries[it], entry) }
                    rows.add(ImportRow(entry, matchesExisting || matchesEarlier))
                }
                rows
            } finally {
                existing.forEach { it.secret.fill(0) }
            }
        }

    private fun isBareKey(text: String): Boolean {
        val bytes = Base32.decode(text) ?: return false
        val enough = bytes.size >= MIN_SECRET_BYTES
        bytes.fill(0)
        return enough
    }

    private fun release(preview: ImportPreview?) {
        preview?.rows?.forEach { it.entry.secret.fill(0) }
    }
}
