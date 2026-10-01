package com.westly.neribovault.feature.developer.secrets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.data.repository.SecretsRepository
import com.westly.neribovault.feature.developer.DeveloperRoutes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The secret form. [value] and [decoyValue] only ever hold text while a real session is open:
 * they are wiped the moment the session ends and filled again after the PIN is entered.
 */
data class SecretEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val label: String = "",
    val category: String = "api_key",
    val isSecret: Boolean = true,
    val value: String = "",
    val decoyValue: String = "",
    val valueLoaded: Boolean = false,
    val labelError: Boolean = false,
    val valueError: Boolean = false,
    val message: String? = null,
    val hasChanges: Boolean = false,
    val isSaving: Boolean = false,
) {
    /** True when nothing has been filled in (a new secret like this is simply discarded). */
    val isBlank: Boolean
        get() = label.isBlank() && value.isBlank() && decoyValue.isBlank()
}

/** State and actions for the secret editor. Saving is explicit. */
class SecretEditorViewModel(
    private val projectId: String?,
    private val secretId: String,
    private val repository: SecretsRepository,
) : ViewModel() {

    private val isNew = secretId == DeveloperRoutes.NEW
    private var existing: SecretEntity? = null

    private val _state = MutableStateFlow(SecretEditorUiState(isLoading = !isNew, isNew = isNew))
    val state: StateFlow<SecretEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (!isNew) {
                val secret = repository.getById(secretId)
                if (secret == null || secret.isDeleted) {
                    _state.update { it.copy(isLoading = false, notFound = true) }
                } else {
                    existing = secret
                    _state.update {
                        it.copy(
                            isLoading = false,
                            label = secret.label,
                            category = secret.category,
                            isSecret = secret.isSecret,
                        )
                    }
                }
            }
            SecretsVault.mode.collect { mode -> onModeChanged(mode) }
        }
    }

    private fun onModeChanged(mode: SessionMode) {
        if (mode == SessionMode.Real) {
            val secret = existing
            if (secret == null) {
                _state.update { it.copy(valueLoaded = true) }
            } else if (!_state.value.valueLoaded) {
                loadValue(secret)
            }
        } else {
            // The session ended or is not a real one: nothing readable stays in memory.
            _state.update { it.copy(value = "", decoyValue = "", valueLoaded = false) }
        }
    }

    private fun loadValue(secret: SecretEntity) {
        val plain: String? = if (secret.isSecret) {
            val cipher = secret.ciphertext
            val iv = secret.iv
            if (cipher == null || iv == null) "" else SecretsVault.decrypt(cipher, iv)
        } else {
            secret.publicValue.orEmpty()
        }
        if (plain == null) {
            _state.update { it.copy(message = "Couldn't read this secret.") }
        } else {
            _state.update {
                it.copy(value = plain, decoyValue = secret.decoyValue.orEmpty(), valueLoaded = true)
            }
        }
    }

    fun onLabelChange(text: String) {
        SecretsVault.touch()
        _state.update { it.copy(label = text, labelError = false, hasChanges = true) }
    }

    fun onCategoryChange(value: String) {
        SecretsVault.touch()
        _state.update { it.copy(category = value, hasChanges = true) }
    }

    fun onIsSecretChange(value: Boolean) {
        SecretsVault.touch()
        _state.update { it.copy(isSecret = value, hasChanges = true) }
    }

    fun onValueChange(text: String) {
        SecretsVault.touch()
        _state.update { it.copy(value = text, valueError = false, message = null, hasChanges = true) }
    }

    fun onDecoyChange(text: String) {
        SecretsVault.touch()
        _state.update { it.copy(decoyValue = text, hasChanges = true) }
    }

    /** Encrypts and saves, then calls [onSaved]. A new secret with nothing in it is discarded. */
    fun save(onSaved: () -> Unit) {
        val form = _state.value
        if (form.isSaving) return
        if (SecretsVault.mode.value != SessionMode.Real || !form.valueLoaded) {
            _state.update { it.copy(message = SecretsVault.BLOCKED_MESSAGE) }
            return
        }
        if (form.isNew && form.isBlank) {
            onSaved()
            return
        }
        val labelBad = form.label.isBlank()
        val valueBad = form.value.isBlank()
        if (labelBad || valueBad) {
            _state.update { it.copy(labelError = labelBad, valueError = valueBad) }
            return
        }
        SecretsVault.touch()
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val sealed = if (form.isSecret) SecretsVault.encrypt(form.value) else null
            if (form.isSecret && sealed == null) {
                _state.update { it.copy(isSaving = false, message = "Couldn't save. Please try again.") }
                return@launch
            }
            val now = System.currentTimeMillis()
            val base = existing
            val decoy = form.decoyValue.trim().ifEmpty { null }
            val secret = (base ?: SecretEntity(
                id = newId(),
                createdAt = now,
                updatedAt = now,
                projectId = projectId,
                label = form.label,
                category = form.category,
                isSecret = form.isSecret,
            )).copy(
                label = form.label.trim(),
                category = form.category,
                isSecret = form.isSecret,
                publicValue = if (form.isSecret) null else form.value,
                ciphertext = sealed?.ciphertext,
                iv = sealed?.iv,
                decoyValue = if (form.isSecret) decoy else null,
            )
            repository.upsert(secret)
            SecretsVault.logEvent(if (base == null) "secret_created" else "secret_updated", secret.id)
            _state.update { it.copy(value = "", decoyValue = "") }
            onSaved()
        }
    }
}
