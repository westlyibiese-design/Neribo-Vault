package com.westly.neribovault.feature.authenticator.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.engine.Base32
import com.westly.neribovault.feature.authenticator.engine.CodeFormat
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.Totp
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NEW_ID = "new"
private const val MAX_NAME = 60
private const val MAX_NOTES = 500
private const val MIN_SECRET_BYTES = 10
private const val DEFAULT_PERIOD = 30

/** The fields of the form. [secretText] is only used for a new account or when [replaceSecret] is on. */
data class TotpForm(
    val issuer: String = "",
    val accountName: String = "",
    val secretText: String = "",
    val replaceSecret: Boolean = false,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodText: String = DEFAULT_PERIOD.toString(),
    val notes: String = "",
)

data class TotpEditorUiState(
    val form: TotpForm = TotpForm(),
    val isNew: Boolean = true,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
)

/** What happened when Save was pressed. */
sealed interface TotpSaveResult {
    object Saved : TotpSaveResult
    class Failed(val message: String) : TotpSaveResult
}

/** State and actions for the account form. Nothing is written until [save]. */
class TotpEditorViewModel(
    private val accountId: String,
    private val repository: TotpAccountsRepository,
) : ViewModel() {

    private val isNew = accountId == NEW_ID
    private var initialForm = TotpForm()
    private val _state = MutableStateFlow(TotpEditorUiState(isNew = isNew, isLoading = !isNew))
    val state: StateFlow<TotpEditorUiState> = _state.asStateFlow()

    init {
        if (!isNew) {
            viewModelScope.launch {
                val account = repository.getById(accountId)
                if (account == null || account.isDeleted) {
                    _state.update { it.copy(isLoading = false, notFound = true) }
                } else {
                    val form = TotpForm(
                        issuer = account.issuer,
                        accountName = account.accountName,
                        algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm }
                            ?: OtpAlgorithm.SHA1,
                        digits = if (account.digits == 8) 8 else 6,
                        periodText = account.periodSeconds.toString(),
                        notes = account.notes,
                    )
                    initialForm = form
                    _state.update { it.copy(form = form, isLoading = false) }
                }
            }
        }
    }

    private fun change(transform: (TotpForm) -> TotpForm) {
        _state.update {
            val form = transform(it.form)
            it.copy(form = form, isDirty = form != initialForm)
        }
    }

    fun setIssuer(value: String) = change { it.copy(issuer = value.take(MAX_NAME)) }
    fun setAccountName(value: String) = change { it.copy(accountName = value.take(MAX_NAME)) }
    fun setSecretText(value: String) = change { it.copy(secretText = value) }
    fun setNotes(value: String) = change { it.copy(notes = value.take(MAX_NOTES)) }
    fun setAlgorithm(value: OtpAlgorithm) = change { it.copy(algorithm = value) }
    fun setDigits(value: Int) = change { it.copy(digits = value) }
    fun setPeriodText(value: String) = change { it.copy(periodText = value.filter { c -> c.isDigit() }.take(3)) }

    fun setReplaceSecret(value: Boolean) = change {
        it.copy(replaceSecret = value, secretText = if (value) it.secretText else "")
    }

    /** Whether the form shows the secret key field. */
    private fun needsSecret(form: TotpForm): Boolean = isNew || form.replaceSecret

    /** A short message about the secret key, or null when it is fine or still empty. */
    fun secretProblem(form: TotpForm = _state.value.form): String? {
        if (!needsSecret(form) || form.secretText.isBlank()) return null
        val bytes = Base32.decode(form.secretText) ?: return "That key does not look right"
        val tooShort = bytes.size < MIN_SECRET_BYTES
        bytes.fill(0)
        return if (tooShort) "The key is too short" else null
    }

    /** The period in seconds when the text is a number from 15 to 120, otherwise null. */
    fun periodOrNull(form: TotpForm = _state.value.form): Int? {
        val number = form.periodText.toIntOrNull() ?: return null
        return if (number in 15..120) number else null
    }

    /**
     * The live test code for the key being typed, grouped for reading, or null when the key or the
     * time step is not valid yet. The key bytes are decoded, used and zero-filled right here.
     */
    fun testCode(nowMillis: Long): String? {
        val form = _state.value.form
        if (!needsSecret(form)) return null
        val period = periodOrNull(form) ?: return null
        val bytes = Base32.decode(form.secretText) ?: return null
        return try {
            if (bytes.size < MIN_SECRET_BYTES) {
                null
            } else {
                CodeFormat.group(Totp.code(bytes, nowMillis, form.digits, period, form.algorithm))
            }
        } catch (e: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    /** The first thing that stops the form from being saved, or null when it can be saved. */
    fun validate(): String? {
        val form = _state.value.form
        if (form.issuer.isBlank() && form.accountName.isBlank()) return "Enter the service or the account name"
        if (needsSecret(form)) {
            if (form.secretText.isBlank()) return "Enter the secret key"
            secretProblem(form)?.let { return it }
        }
        if (periodOrNull(form) == null) return "The time step must be from 15 to 120 seconds"
        return null
    }

    /** Encrypts the secret (when there is one to store) and saves. */
    fun save(onResult: (TotpSaveResult) -> Unit) {
        val problem = validate()
        if (problem != null) {
            onResult(TotpSaveResult.Failed(problem))
            return
        }
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        val form = _state.value.form
        viewModelScope.launch {
            val result = try {
                persist(form)
            } catch (e: Exception) {
                TotpSaveResult.Failed("Could not save this account")
            }
            if (result is TotpSaveResult.Saved) {
                // The key text is not kept once it is stored.
                _state.update { it.copy(form = it.form.copy(secretText = ""), isSaving = false) }
            } else {
                _state.update { it.copy(isSaving = false) }
            }
            onResult(result)
        }
    }

    private suspend fun persist(form: TotpForm): TotpSaveResult {
        var cipher: String? = null
        var iv: String? = null
        if (needsSecret(form)) {
            val bytes = Base32.decode(form.secretText)
                ?: return TotpSaveResult.Failed("That key does not look right")
            try {
                val sealed = withContext(Dispatchers.Default) {
                    val made = AuthenticatorCrypto.encrypt(bytes)
                    // Prove the stored secret can be read back before anything is saved.
                    val back = made?.let { AuthenticatorCrypto.decrypt(it.ciphertext, it.iv) }
                    val same = back != null && back.contentEquals(bytes)
                    back?.fill(0)
                    if (same) made else null
                } ?: return TotpSaveResult.Failed("Secure storage is not available on this phone")
                cipher = sealed.ciphertext
                iv = sealed.iv
            } finally {
                bytes.fill(0)
            }
        }
        val period = periodOrNull(form) ?: return TotpSaveResult.Failed("The time step must be from 15 to 120 seconds")
        val now = System.currentTimeMillis()
        val issuer = form.issuer.trim()
        val accountName = form.accountName.trim()
        if (isNew) {
            val entity = TotpAccountEntity(
                id = newId(),
                createdAt = now,
                updatedAt = now,
                issuer = issuer,
                accountName = accountName,
                secretCipher = cipher.orEmpty(),
                secretIv = iv.orEmpty(),
                algorithm = form.algorithm.name,
                digits = form.digits,
                periodSeconds = period,
                isPinned = false,
                sortOrder = repository.maxSortOrder() + 1,
                notes = form.notes,
            )
            repository.upsert(entity)
        } else {
            val latest = repository.getById(accountId)
                ?: return TotpSaveResult.Failed("This account no longer exists")
            repository.upsert(
                latest.copy(
                    issuer = issuer,
                    accountName = accountName,
                    secretCipher = cipher ?: latest.secretCipher,
                    secretIv = iv ?: latest.secretIv,
                    algorithm = form.algorithm.name,
                    digits = form.digits,
                    periodSeconds = period,
                    notes = form.notes,
                ),
            )
        }
        return TotpSaveResult.Saved
    }

    /** Moves this account to Recently deleted. */
    fun delete(onDone: () -> Unit) {
        if (isNew) {
            onDone()
            return
        }
        viewModelScope.launch {
            repository.softDelete(accountId)
            onDone()
        }
    }

    override fun onCleared() {
        // Forget any key text that was typed but never saved.
        _state.update { it.copy(form = it.form.copy(secretText = "")) }
        super.onCleared()
    }
}
