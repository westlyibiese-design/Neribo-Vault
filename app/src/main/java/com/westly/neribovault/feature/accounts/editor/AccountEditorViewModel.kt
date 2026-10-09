package com.westly.neribovault.feature.accounts.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.repository.AccountFieldsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_ACTIVE
import com.westly.neribovault.feature.accounts.PlatformPreset
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.SIGN_IN_EMAIL_PASSWORD
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val OWNER_ACCOUNT = "account"

/**
 * Everything typed in the account form. [platformId] is a preset id (or the "custom" id, in
 * which case [customName] is the platform name). [newPassword] is only filled while
 * [changingPassword] is true; it is plain text for as long as the form is open.
 */
data class AccountForm(
    val platformId: String = PlatformPresets.CUSTOM_ID,
    val customName: String = "",
    val name: String = "",
    val signInMethod: String = SIGN_IN_EMAIL_PASSWORD,
    val loginId: String = "",
    val url: String = "",
    val twoFactor: String = "none",
    val recovery: String = "",
    val notes: String = "",
    val status: String = ACCOUNT_STATUS_ACTIVE,
    val tags: List<String> = emptyList(),
    val changingPassword: Boolean = false,
    val newPassword: String = "",
    val passwordRemoved: Boolean = false,
    val decoyText: String = "",
) {
    /** The value stored in `AccountEntity.platform`. */
    val storedPlatform: String
        get() = if (platformId == PlatformPresets.CUSTOM_ID) customName.trim() else platformId
}

/** [initial] is the form as loaded; the form is dirty when it differs. */
data class AccountEditorUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val form: AccountForm = AccountForm(),
    val initial: AccountForm = AccountForm(),
    val hasStoredPassword: Boolean = false,
    val isSaving: Boolean = false,
) {
    val isDirty: Boolean get() = form != initial

    /** True when a password is stored and this edit does not remove it. */
    val passwordKept: Boolean get() = hasStoredPassword && !form.passwordRemoved
}

/** State and the save for the account editor. */
class AccountEditorViewModel(
    private val accountId: String,
    private val database: NeriboDatabase,
    private val accounts: AccountsRepository,
    private val fields: AccountFieldsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountEditorUiState())
    val state: StateFlow<AccountEditorUiState> = _state.asStateFlow()

    /** The custom fields of this account, edited in memory until Save. */
    val fieldsState = FieldsEditorState()

    init {
        viewModelScope.launch {
            val account = accounts.getById(accountId)
            if (account == null || account.isDeleted) {
                _state.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            val preset = PlatformPresets.find(account.platform)
            val isPreset = preset != null && preset.id != PlatformPresets.CUSTOM_ID
            val form = AccountForm(
                platformId = if (isPreset && preset != null) preset.id else PlatformPresets.CUSTOM_ID,
                customName = if (isPreset) "" else account.platform,
                name = account.name,
                signInMethod = account.signInMethod,
                loginId = account.loginId,
                url = account.url,
                twoFactor = account.twoFactor,
                recovery = account.recovery,
                notes = account.notes,
                status = account.status,
                tags = account.tags,
                decoyText = account.passwordDecoy.orEmpty(),
            )
            fieldsState.load(fields.observeForOwner(OWNER_ACCOUNT, accountId).first())
            _state.update {
                it.copy(
                    isLoading = false,
                    form = form,
                    initial = form,
                    hasStoredPassword = account.passwordCipher != null && account.passwordIv != null,
                )
            }
        }
    }

    private fun edit(change: (AccountForm) -> AccountForm) {
        _state.update { it.copy(form = change(it.form)) }
    }

    /** Picks a platform from the picker. A blank link is filled from the preset. */
    fun setPlatform(preset: PlatformPreset, customName: String) {
        edit { form ->
            val isCustom = preset.id == PlatformPresets.CUSTOM_ID
            form.copy(
                platformId = preset.id,
                customName = if (isCustom) customName.take(MAX_PLATFORM_LENGTH) else "",
                name = form.name.ifBlank { if (isCustom) customName else preset.name },
                url = form.url.ifBlank { preset.url },
            )
        }
    }

    fun setCustomName(value: String) = edit { it.copy(customName = value.take(MAX_PLATFORM_LENGTH)) }

    fun setName(value: String) = edit { it.copy(name = value.take(MAX_NAME_LENGTH)) }

    fun setSignInMethod(value: String) = edit { it.copy(signInMethod = value) }

    fun setLogin(value: String) = edit { it.copy(loginId = value) }

    fun setUrl(value: String) = edit { it.copy(url = value) }

    fun setTwoFactor(value: String) = edit { it.copy(twoFactor = value) }

    fun setRecovery(value: String) = edit { it.copy(recovery = value) }

    fun setNotes(value: String) = edit { it.copy(notes = value) }

    fun setStatus(value: String) = edit { it.copy(status = value) }

    fun setDecoyText(value: String) = edit { it.copy(decoyText = value) }

    /** Adds a tag. Returns false when it is empty, a duplicate or the tenth tag has been used. */
    fun addTag(raw: String): Boolean {
        val tag = normalizeTag(raw)
        val current = _state.value.form.tags
        if (tag.isEmpty() || tag in current || current.size >= MAX_TAGS) return false
        edit { it.copy(tags = it.tags + tag) }
        return true
    }

    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }

    fun startChangingPassword() = edit { it.copy(changingPassword = true) }

    fun cancelChangingPassword() = edit { it.copy(changingPassword = false, newPassword = "") }

    fun setNewPassword(value: String) = edit { it.copy(newPassword = value) }

    fun removePassword() = edit {
        it.copy(passwordRemoved = true, changingPassword = false, newPassword = "", decoyText = "")
    }

    /** The first thing wrong with the form, or null. */
    fun validate(): String? {
        val form = _state.value.form
        if (form.platformId == PlatformPresets.CUSTOM_ID && form.customName.isBlank()) {
            return "Enter the platform name"
        }
        if (form.name.isBlank()) return "Give this account a name"
        if (!isValidLink(form.url)) return LINK_ERROR_TEXT
        return fieldsState.validate()
    }

    /** True when saving has to encrypt or remove something, so it needs the real session. */
    fun needsSession(): Boolean {
        val s = _state.value
        val form = s.form
        val hasDecoy = AccountsVault.hasDecoyPin.value
        if (form.passwordRemoved) return true
        if (form.changingPassword && form.newPassword.isNotEmpty()) return true
        if (hasDecoy && s.passwordKept && form.decoyText.trim() != s.initial.decoyText.trim()) return true
        return fieldsState.needsSession(hasDecoy)
    }

    /** Saves everything or nothing, then reports through [onResult]. */
    fun save(onResult: (EditorSaveResult) -> Unit) {
        if (_state.value.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val result = performSave()
            _state.update { it.copy(isSaving = false) }
            onResult(result)
        }
    }

    private suspend fun performSave(): EditorSaveResult {
        val form = _state.value.form
        val latest = accounts.getById(accountId)
            ?: return EditorSaveResult.Failed("This account no longer exists")
        val hasDecoy = AccountsVault.hasDecoyPin.value

        var cipher = latest.passwordCipher
        var iv = latest.passwordIv
        var decoy = latest.passwordDecoy
        var secretChanged = false
        if (form.passwordRemoved) {
            cipher = null
            iv = null
            decoy = null
            secretChanged = true
        }
        if (form.changingPassword && form.newPassword.isNotEmpty()) {
            val sealed = AccountsVault.encrypt(form.newPassword)
                ?: return EditorSaveResult.Failed(ENCRYPT_FAILED_MESSAGE)
            cipher = sealed.ciphertext
            iv = sealed.iv
            secretChanged = true
        }
        if (cipher != null && hasDecoy) {
            val text = form.decoyText.trim().ifEmpty { null }
            if (text != decoy) secretChanged = true
            decoy = text
        }
        if (cipher == null) decoy = null

        val built = fieldsState.build(OWNER_ACCOUNT, accountId, hasDecoy) { AccountsVault.encrypt(it) }
            ?: return EditorSaveResult.Failed(ENCRYPT_FAILED_MESSAGE)

        val updated = latest.copy(
            platform = form.storedPlatform,
            name = form.name.trim(),
            signInMethod = form.signInMethod,
            loginId = form.loginId.trim(),
            url = form.url.trim(),
            twoFactor = form.twoFactor,
            recovery = form.recovery.trim(),
            notes = form.notes.trim(),
            status = form.status,
            tags = form.tags,
            passwordCipher = cipher,
            passwordIv = iv,
            passwordDecoy = decoy,
        )
        try {
            database.withTransaction {
                accounts.upsert(updated)
                built.removedIds.forEach { fields.deletePermanently(it) }
                built.fields.forEach { fields.upsert(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return EditorSaveResult.Failed("Couldn't save. Nothing was changed.")
        }
        if (secretChanged || built.secretsChanged) AccountsVault.logEvent("secret_saved", accountId)
        AccountsVault.touch()
        return EditorSaveResult.Saved
    }

    override fun onCleared() {
        // The typed password must not outlive the screen.
        _state.update { it.copy(form = it.form.copy(newPassword = "")) }
        fieldsState.rows.forEach {
            it.value = ""
            it.decoy = ""
        }
        super.onCleared()
    }
}
