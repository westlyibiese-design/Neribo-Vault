package com.westly.neribovault.feature.authenticator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.lock.AppLockManager
import com.westly.neribovault.core.lock.LockState
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.data.repository.TotpAccountsRepository
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a tapped code stays visible when "Hide codes until tapped" is on. */
const val REVEAL_MILLIS = 10_000L

/** What the list knows about one account's secret key right now. */
sealed interface SecretState {
    /** Still being decrypted; it appears within a moment. */
    object Pending : SecretState

    /** The key is missing or the data was changed; the code cannot be made on this phone. */
    object Unreadable : SecretState

    /** The decrypted secret. Never copy it; it is zero-filled when the screen stops. */
    class Ready(val secret: ByteArray) : SecretState
}

/** Everything the Authenticator list draws. [secretVersion] changes when a secret finishes decrypting. */
data class AuthenticatorUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val items: List<TotpAccountEntity> = emptyList(),
    val hasAny: Boolean = false,
    val revealedUntil: Map<String, Long> = emptyMap(),
    val secretVersion: Int = 0,
    val selfTest: List<CheckResult>? = null,
    val isLoading: Boolean = true,
)

/** State and actions for the Authenticator list. */
class AuthenticatorViewModel(
    private val accounts: TotpAccountsRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val revealedFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val versionFlow = MutableStateFlow(0)
    private val selfTestFlow = MutableStateFlow<List<CheckResult>?>(null)

    private class CachedSecret(val cipher: String, val secret: ByteArray?)

    private val cacheLock = Any()
    private val cache = HashMap<String, CachedSecret>()
    private val pending = HashSet<String>()
    private var generation = 0

    val state: StateFlow<AuthenticatorUiState> = combine(
        accounts.observeAll(),
        queryFlow,
        searchOpenFlow,
        revealedFlow,
        versionFlow,
    ) { all, query, searchOpen, revealed, version ->
        val needle = query.trim()
        val filtered = if (needle.isEmpty()) {
            all
        } else {
            all.filter {
                it.issuer.contains(needle, ignoreCase = true) ||
                    it.accountName.contains(needle, ignoreCase = true)
            }
        }
        AuthenticatorUiState(
            query = query,
            isSearchOpen = searchOpen,
            items = filtered,
            hasAny = all.isNotEmpty(),
            revealedUntil = revealed,
            secretVersion = version,
            isLoading = false,
        )
    }.combine(selfTestFlow) { base, selfTest ->
        base.copy(selfTest = selfTest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthenticatorUiState())

    init {
        // Wipe the decrypted secrets the moment the app or this vault locks.
        viewModelScope.launch {
            combine(
                AppLockManager.state,
                AppLockManager.vaultEnabledIds,
                AppLockManager.unlockedVaults,
            ) { lockState, enabled, unlocked ->
                lockState != LockState.Unlocked || (VAULT_ID in enabled && VAULT_ID !in unlocked)
            }.collect { locked -> if (locked) clearSecrets() }
        }
    }

    /**
     * The secret of [account]. Starts decrypting off the main thread when it is not cached yet and
     * returns [SecretState.Pending] until it is done.
     */
    fun secretFor(account: TotpAccountEntity): SecretState {
        synchronized(cacheLock) {
            val cached = cache[account.id]
            if (cached != null && cached.cipher == account.secretCipher) {
                val secret = cached.secret
                return if (secret == null) SecretState.Unreadable else SecretState.Ready(secret)
            }
            if (!pending.add(account.id)) return SecretState.Pending
        }
        val startedIn = synchronized(cacheLock) { generation }
        val id = account.id
        val cipher = account.secretCipher
        val iv = account.secretIv
        viewModelScope.launch {
            val plain = withContext(Dispatchers.Default) { AuthenticatorCrypto.decrypt(cipher, iv) }
            synchronized(cacheLock) {
                if (generation == startedIn) {
                    cache.remove(id)?.secret?.fill(0)
                    cache[id] = CachedSecret(cipher, plain)
                    pending.remove(id)
                } else {
                    // The screen stopped or the vault locked while this was decrypting: drop it.
                    plain?.fill(0)
                }
            }
            versionFlow.update { it + 1 }
        }
        return SecretState.Pending
    }

    /** Zero-fills and forgets every decrypted secret and every revealed code. */
    fun clearSecrets() {
        synchronized(cacheLock) {
            cache.values.forEach { it.secret?.fill(0) }
            cache.clear()
            pending.clear()
            generation++
        }
        revealedFlow.value = emptyMap()
        versionFlow.update { it + 1 }
    }

    /** Called when the screen stops. */
    fun onStop() = clearSecrets()

    override fun onCleared() {
        synchronized(cacheLock) {
            cache.values.forEach { it.secret?.fill(0) }
            cache.clear()
        }
        super.onCleared()
    }

    /** Shows the code of [id] for [REVEAL_MILLIS] (only used when codes are hidden until tapped). */
    fun reveal(id: String, nowMillis: Long) {
        revealedFlow.update { it + (id to nowMillis + REVEAL_MILLIS) }
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { accounts.setPinned(id, pinned) }
    }

    fun delete(id: String) {
        viewModelScope.launch { accounts.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { accounts.restore(id) }
    }

    fun runSelfTest() {
        viewModelScope.launch {
            selfTestFlow.value = withContext(Dispatchers.Default) { runAuthenticatorSelfTest() }
        }
    }

    fun dismissSelfTest() {
        selfTestFlow.value = null
    }
}
