package com.westly.neribovault.feature.accounts.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [report] is null until a check has run. [unreadable] counts passwords that could not be decrypted. */
data class PasswordHealthUiState(
    val isChecking: Boolean = false,
    val report: HealthReport? = null,
    val unreadable: Int = 0,
)

/** Runs the password health check. Only the finished [HealthReport] is ever kept, never a password. */
class PasswordHealthViewModel(
    private val accounts: AccountsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PasswordHealthUiState())
    val state: StateFlow<PasswordHealthUiState> = _state.asStateFlow()

    private class Outcome(val report: HealthReport, val unreadable: Int)

    /** Checks every stored password. Does nothing outside a real session. */
    fun check() {
        if (_state.value.isChecking) return
        if (AccountsVault.mode.value != AccountsSessionMode.Real) return
        _state.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            AccountsVault.touch()
            val rows = accounts.observeAll().first()
            val outcome = withContext(Dispatchers.Default) {
                val entries = ArrayList<HealthEntry>()
                var unreadable = 0
                for (row in rows) {
                    val cipher = row.passwordCipher
                    val iv = row.passwordIv
                    if (cipher == null || iv == null) continue
                    val plain = AccountsVault.decrypt(cipher, iv)
                    if (plain == null) {
                        unreadable += 1
                    } else {
                        entries.add(HealthEntry(row.id, row.name.trim().ifEmpty { "Untitled account" }, plain))
                    }
                }
                val report = PasswordHealthEngine.analyze(entries)
                // The plain passwords are not needed any more.
                entries.clear()
                Outcome(report, unreadable)
            }
            // The session may have ended while the check ran; then nothing is shown.
            if (AccountsVault.mode.value == AccountsSessionMode.Real) {
                _state.value = PasswordHealthUiState(report = outcome.report, unreadable = outcome.unreadable)
                AccountsVault.logEvent("password_health_checked", null)
            } else {
                _state.value = PasswordHealthUiState()
            }
        }
    }

    /** Forgets the last report. */
    fun clear() {
        _state.value = PasswordHealthUiState()
    }
}
