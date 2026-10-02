package com.westly.neribovault.feature.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.data.cloud.AuthOutcome
import com.westly.neribovault.data.cloud.CloudAuth
import com.westly.neribovault.data.cloud.CloudConfigState
import com.westly.neribovault.data.cloud.SyncEngine
import com.westly.neribovault.data.cloud.SyncResult
import com.westly.neribovault.data.cloud.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Cloud sync screen shows. Holds no tokens or keys. */
data class CloudUiState(
    val loading: Boolean = true,
    val hasProject: Boolean = false,
    val signedIn: Boolean = false,
    val email: String = "",
    val usingOwnProject: Boolean = false,
    val sharedAvailable: Boolean = false,
    val vaultEnabled: Map<String, Boolean> = emptyMap(),
    val statusText: String = "",
    val statusIsError: Boolean = false,
    val syncing: Boolean = false,
    val urlInput: String = "",
    val keyInput: String = "",
    val urlError: String? = null,
    val keyError: String? = null,
    val emailInput: String = "",
    val passwordInput: String = "",
    val passwordVisible: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val showSignOutConfirm: Boolean = false,
)

/** Typed-in values and transient messages, kept apart from the stored configuration. */
internal data class CloudForm(
    val urlInput: String = "",
    val keyInput: String = "",
    val urlError: String? = null,
    val keyError: String? = null,
    val emailInput: String = "",
    val passwordInput: String = "",
    val passwordVisible: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val showSignOutConfirm: Boolean = false,
)

/** Drives the Cloud sync screen: project setup, account, per-vault switches and Sync now. */
class CloudViewModel(
    private val auth: CloudAuth,
    private val engine: SyncEngine,
) : ViewModel() {
    private val form = MutableStateFlow(CloudForm())

    val state: StateFlow<CloudUiState> = combine(auth.state, engine.state, form) { config, sync, f ->
        buildState(config, sync, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CloudUiState())

    init {
        viewModelScope.launch { auth.load() }
    }

    private fun buildState(config: CloudConfigState, sync: SyncState, f: CloudForm): CloudUiState {
        val status = statusFor(config, sync)
        return CloudUiState(
            loading = !config.loaded,
            hasProject = config.hasProject,
            signedIn = config.signedIn,
            email = config.email,
            usingOwnProject = config.usingOwnProject,
            sharedAvailable = config.sharedAvailable,
            vaultEnabled = config.vaultEnabled,
            statusText = status.first,
            statusIsError = status.second,
            syncing = sync is SyncState.Syncing,
            urlInput = f.urlInput,
            keyInput = f.keyInput,
            urlError = f.urlError,
            keyError = f.keyError,
            emailInput = f.emailInput,
            passwordInput = f.passwordInput,
            passwordVisible = f.passwordVisible,
            working = f.working,
            error = f.error,
            info = f.info,
            showSignOutConfirm = f.showSignOutConfirm,
        )
    }

    /** Text and error flag for the status line. */
    private fun statusFor(config: CloudConfigState, sync: SyncState): Pair<String, Boolean> = when (sync) {
        is SyncState.Syncing -> sync.progress to false
        is SyncState.Error -> sync.message to true
        is SyncState.Success -> lastSyncedText(sync.at) to false
        SyncState.Idle ->
            if (config.lastSyncAt > 0L) lastSyncedText(config.lastSyncAt) to false else "Not synced yet" to false
    }

    private fun lastSyncedText(at: Long): String {
        val relative = formatRelative(at)
        val text = if (relative == "Just now" || relative == "Yesterday") relative.lowercase() else relative
        return "Last synced $text"
    }

    // ---- Project setup -------------------------------------------------------------------

    fun onUrlChange(value: String) = form.update { it.copy(urlInput = value, urlError = null) }

    fun onKeyChange(value: String) = form.update { it.copy(keyInput = value, keyError = null) }

    fun saveProject() {
        val current = form.value
        if (current.working) return
        val check = auth.validateProject(current.urlInput, current.keyInput)
        if (check.urlError != null || check.keyError != null) {
            form.update { it.copy(urlError = check.urlError, keyError = check.keyError) }
            return
        }
        form.update { it.copy(working = true, error = null, info = null) }
        viewModelScope.launch {
            val error = auth.saveProject(current.urlInput, current.keyInput)
            form.update {
                if (error == null) {
                    it.copy(working = false, urlInput = "", keyInput = "", urlError = null, keyError = null)
                } else {
                    it.copy(working = false, error = error)
                }
            }
        }
    }

    fun changeProject() {
        if (form.value.working) return
        viewModelScope.launch {
            auth.changeProject()
            form.update { CloudForm() }
            engine.clearStatus()
        }
    }

    /** Switches to the shared Neribo cloud. Only offered while signed out. */
    fun useSharedProject() {
        if (form.value.working) return
        viewModelScope.launch {
            auth.useSharedProject()
            form.update { CloudForm() }
            engine.clearStatus()
        }
    }

    /** Switches to the owner's own Supabase project. Only offered while signed out. */
    fun useOwnProject() {
        if (form.value.working) return
        viewModelScope.launch {
            auth.useOwnProject()
            form.update { CloudForm() }
            engine.clearStatus()
        }
    }

    // ---- Account -------------------------------------------------------------------------

    fun onEmailChange(value: String) = form.update { it.copy(emailInput = value, error = null, info = null) }

    fun onPasswordChange(value: String) = form.update { it.copy(passwordInput = value, error = null, info = null) }

    fun togglePasswordVisible() = form.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun signIn() = authenticate(createAccount = false)

    fun createAccount() = authenticate(createAccount = true)

    private fun authenticate(createAccount: Boolean) {
        val current = form.value
        if (current.working) return
        val email = current.emailInput.trim()
        val problem = when {
            email.isEmpty() || !email.contains('@') -> "Enter a valid email address."
            current.passwordInput.isEmpty() -> "Enter your password."
            createAccount && current.passwordInput.length < MIN_PASSWORD_LENGTH ->
                "Use a password with at least $MIN_PASSWORD_LENGTH characters."
            else -> null
        }
        if (problem != null) {
            form.update { it.copy(error = problem, info = null) }
            return
        }
        form.update { it.copy(working = true, error = null, info = null) }
        viewModelScope.launch {
            val outcome = if (createAccount) {
                auth.signUp(email, current.passwordInput)
            } else {
                auth.signIn(email, current.passwordInput)
            }
            form.update {
                when (outcome) {
                    AuthOutcome.SignedIn -> {
                        engine.clearStatus()
                        CloudForm()
                    }
                    AuthOutcome.ConfirmEmail -> it.copy(
                        working = false,
                        passwordInput = "",
                        info = "Check your email to confirm, then sign in.",
                    )
                    is AuthOutcome.Failed -> it.copy(working = false, error = outcome.message)
                }
            }
        }
    }

    // ---- Signed in -----------------------------------------------------------------------

    fun syncNow() {
        if (engine.isBusy) {
            form.update { it.copy(info = "A sync is already running.") }
            return
        }
        form.update { it.copy(info = null) }
        viewModelScope.launch {
            val result = engine.syncNow()
            if (result == SyncResult.AlreadyRunning) {
                form.update { it.copy(info = "A sync is already running.") }
            }
        }
    }

    fun setVaultEnabled(vaultId: String, enabled: Boolean) {
        viewModelScope.launch { auth.setVaultEnabled(vaultId, enabled) }
    }

    fun askSignOut() = form.update { it.copy(showSignOutConfirm = true) }

    fun dismissSignOut() = form.update { it.copy(showSignOutConfirm = false) }

    fun confirmSignOut() {
        form.update { it.copy(showSignOutConfirm = false) }
        viewModelScope.launch {
            auth.signOut()
            form.update { CloudForm() }
            engine.clearStatus()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val MIN_PASSWORD_LENGTH = 6
    }
}
