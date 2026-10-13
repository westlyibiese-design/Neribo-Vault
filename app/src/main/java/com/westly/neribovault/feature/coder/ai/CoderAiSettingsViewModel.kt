package com.westly.neribovault.feature.coder.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MESSAGE_BAD_KEY = "That doesn't look like a key. Check that you copied all of it."
private const val MESSAGE_NOT_STORED = "This phone couldn't store the key safely, so it wasn't saved."
private const val MESSAGE_UNREADABLE = "The saved key couldn't be read. Add the key again."
private const val MESSAGE_BAD_MODEL = "Model names use only letters, numbers and the characters . - _ :"
private const val MESSAGE_TYPE_NAME = " You can choose a model by typing its name."

/** What one provider card shows. It never holds a key; [last4] is the only part kept for display. */
data class CoderProviderUiState(
    val provider: CoderAiProvider,
    val hasKey: Boolean,
    val last4: String?,
    val model: String?,
    val isActive: Boolean,
)

/** The model chooser sheet. [failure] and [inputError] are plain-English messages, never the key. */
data class CoderModelSheetState(
    val provider: CoderAiProvider,
    val isLoading: Boolean,
    val models: List<String> = emptyList(),
    val notice: String? = null,
    val failure: String? = null,
    val inputError: String? = null,
)

/** Everything the AI settings screen shows. No field ever holds a key. */
data class CoderAiSettingsUiState(
    val providers: List<CoderProviderUiState> = emptyList(),
    val isLoading: Boolean = true,
    val keySheetFor: CoderAiProvider? = null,
    val isTesting: Boolean = false,
    val keyError: String? = null,
    val modelSheet: CoderModelSheetState? = null,
    val message: String? = null,
) {
    val hasAnyKey: Boolean get() = providers.any { it.hasKey }
    val hasActive: Boolean get() = providers.any { it.isActive }
}

/**
 * State and actions for the AI settings screen. The typed key lives only in the screen and is handed to
 * [saveAndTest] once; the testing state lives here so it survives a rotation.
 */
class CoderAiSettingsViewModel(
    private val settings: CoderAiSettings,
) : ViewModel() {

    private data class Local(
        val keySheetFor: CoderAiProvider? = null,
        val isTesting: Boolean = false,
        val keyError: String? = null,
        val modelSheet: CoderModelSheetState? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var modelJob: Job? = null

    private val providerStates: Flow<List<CoderProviderUiState>> = combine(
        CoderAiProvider.values().map { provider ->
            combine(
                settings.hasKey(provider),
                settings.keyLast4(provider),
                settings.model(provider),
                settings.activeProvider,
            ) { hasKey, last4, model, active ->
                CoderProviderUiState(provider, hasKey, last4, model, active == provider)
            }
        },
    ) { states -> states.toList() }

    val state: StateFlow<CoderAiSettingsUiState> = combine(providerStates, local) { providers, extra ->
        CoderAiSettingsUiState(
            providers = providers,
            isLoading = false,
            keySheetFor = extra.keySheetFor,
            isTesting = extra.isTesting,
            keyError = extra.keyError,
            modelSheet = extra.modelSheet,
            message = extra.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoderAiSettingsUiState())

    /** Opens the Add or Replace key sheet. */
    fun openKeySheet(provider: CoderAiProvider) {
        local.update { it.copy(keySheetFor = provider, keyError = null) }
    }

    /** Closes the key sheet. A test that is still running carries on and reports in a message or the chooser. */
    fun closeKeySheet() {
        local.update { it.copy(keySheetFor = null, keyError = null) }
    }

    /**
     * Saves [rawKey] for [provider] and tests it by listing its models. A key the provider refuses is removed
     * again. Any other failure keeps the key and opens the model chooser so a name can be typed. A second
     * call while one is running is ignored.
     */
    fun saveAndTest(provider: CoderAiProvider, rawKey: String) {
        if (local.value.isTesting) return
        if (cleanKey(rawKey) == null) {
            local.update { it.copy(keyError = MESSAGE_BAD_KEY) }
            return
        }
        local.update { it.copy(isTesting = true, keyError = null) }
        viewModelScope.launch {
            try {
                runSaveAndTest(provider, rawKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishWithKeyError(MESSAGE_NOT_STORED)
            }
        }
    }

    private suspend fun runSaveAndTest(provider: CoderAiProvider, rawKey: String) {
        if (!settings.saveKey(provider, rawKey)) {
            finishWithKeyError(MESSAGE_NOT_STORED)
            return
        }
        val key = settings.readKey(provider)
        if (key == null) {
            settings.removeKey(provider)
            finishWithKeyError(MESSAGE_NOT_STORED)
            return
        }
        when (val result = listModels(provider, key)) {
            is CoderModelsResult.Success -> local.update {
                it.copy(
                    isTesting = false,
                    keyError = null,
                    keySheetFor = null,
                    modelSheet = CoderModelSheetState(
                        provider = provider,
                        isLoading = false,
                        models = result.models,
                        notice = workedNotice(result.models.size),
                    ),
                )
            }
            is CoderModelsResult.Failure -> {
                if (result.kind == CoderModelsResult.Kind.InvalidKey) {
                    settings.removeKey(provider)
                    finishWithKeyError(result.message)
                } else {
                    local.update {
                        it.copy(
                            isTesting = false,
                            keyError = null,
                            keySheetFor = null,
                            modelSheet = CoderModelSheetState(
                                provider = provider,
                                isLoading = false,
                                failure = result.message + MESSAGE_TYPE_NAME,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Shows [message] under the key field, or as a message when the sheet was closed in the meantime. */
    private fun finishWithKeyError(message: String) {
        local.update {
            if (it.keySheetFor != null) {
                it.copy(isTesting = false, keyError = message)
            } else {
                it.copy(isTesting = false, message = message)
            }
        }
    }

    private fun workedNotice(count: Int): String =
        if (count == 1) "Key works. 1 model available." else "Key works. $count models available."

    /** Opens the model chooser for [provider] and loads the list with the saved key. */
    fun openModelChooser(provider: CoderAiProvider) {
        modelJob?.cancel()
        local.update { it.copy(modelSheet = CoderModelSheetState(provider = provider, isLoading = true)) }
        modelJob = viewModelScope.launch {
            val key = settings.readKey(provider)
            val sheet = if (key == null) {
                CoderModelSheetState(provider = provider, isLoading = false, failure = MESSAGE_UNREADABLE)
            } else {
                when (val result = listModels(provider, key)) {
                    is CoderModelsResult.Success ->
                        CoderModelSheetState(provider = provider, isLoading = false, models = result.models)
                    is CoderModelsResult.Failure ->
                        CoderModelSheetState(provider = provider, isLoading = false, failure = result.message)
                }
            }
            local.update { current ->
                if (current.modelSheet?.provider == provider) current.copy(modelSheet = sheet) else current
            }
        }
    }

    /** Closes the model chooser and stops any list that is still loading. */
    fun closeModelChooser() {
        modelJob?.cancel()
        modelJob = null
        local.update { it.copy(modelSheet = null) }
    }

    /** Saves [rawName] as the model of [provider] and closes the chooser. A name with other characters stays open with a message. */
    fun chooseModel(provider: CoderAiProvider, rawName: String) {
        val name = cleanModelName(rawName)
        if (name == null) {
            local.update { current -> current.copy(modelSheet = current.modelSheet?.copy(inputError = MESSAGE_BAD_MODEL)) }
            return
        }
        viewModelScope.launch {
            settings.setModel(provider, name)
            modelJob?.cancel()
            modelJob = null
            local.update { it.copy(modelSheet = null, message = "Model saved.") }
        }
    }

    /** Removes the message under the typed model name once the person edits it. */
    fun clearModelInputError() {
        local.update { current ->
            val sheet = current.modelSheet
            if (sheet != null && sheet.inputError != null) current.copy(modelSheet = sheet.copy(inputError = null)) else current
        }
    }

    /** Makes [provider] the active one. */
    fun useProvider(provider: CoderAiProvider) {
        viewModelScope.launch {
            settings.setActiveProvider(provider)
            local.update { it.copy(message = "Now using " + provider.displayName + ".") }
        }
    }

    /** Deletes the saved key of [provider]. The screen asks first. */
    fun removeKey(provider: CoderAiProvider) {
        viewModelScope.launch {
            settings.removeKey(provider)
            local.update { it.copy(message = "Key removed.") }
        }
    }

    fun consumeMessage() {
        local.update { it.copy(message = null) }
    }
}
