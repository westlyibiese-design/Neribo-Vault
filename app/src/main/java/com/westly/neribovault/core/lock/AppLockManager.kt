package com.westly.neribovault.core.lock

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Where the app lock currently stands. */
enum class LockState { NotSetUp, Locked, Unlocked }

/** Result of checking a PIN. */
sealed interface PinResult {
    object Success : PinResult

    /** Wrong PIN. [attemptsLeft] is how many tries remain before a wait, when known. */
    class Wrong(val attemptsLeft: Int? = null) : PinResult

    /** Too many wrong tries; try again after [secondsLeft]. */
    class LockedOut(val secondsLeft: Int) : PinResult
}

/**
 * Single source of truth for the app lock and the per-vault locks.
 * Call [ensureInitialized] once (the composable [rememberLockManager] does it for you).
 */
object AppLockManager {
    private const val MAX_ATTEMPTS = 5

    private val initLock = Any()
    private var initialized = false
    private lateinit var store: LockStore
    private var backgroundedAt = -1L

    private val _state = MutableStateFlow(LockState.Locked)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val _biometricsEnabled = MutableStateFlow(false)
    val biometricsEnabled: StateFlow<Boolean> = _biometricsEnabled.asStateFlow()

    private val _autoLockSeconds = MutableStateFlow(30)
    val autoLockSeconds: StateFlow<Int> = _autoLockSeconds.asStateFlow()

    private val _blockScreenshots = MutableStateFlow(false)
    val blockScreenshots: StateFlow<Boolean> = _blockScreenshots.asStateFlow()

    private val _lockoutUntil = MutableStateFlow(0L)
    /** Wall-clock millis until which PIN entry is blocked; 0 when not locked out. */
    val lockoutUntil: StateFlow<Long> = _lockoutUntil.asStateFlow()

    private val _vaultEnabledIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of vaults that have their own PIN turned on. */
    val vaultEnabledIds: StateFlow<Set<String>> = _vaultEnabledIds.asStateFlow()

    private val _unlockedVaults = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of vaults unlocked in this session. */
    val unlockedVaults: StateFlow<Set<String>> = _unlockedVaults.asStateFlow()

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            handleBackground()
        }
    }

    /** Loads stored settings and starts watching the app lifecycle. Safe to call many times. */
    fun ensureInitialized(context: Context) {
        synchronized(initLock) {
            if (initialized) return
            store = LockStore(context.applicationContext)
            _biometricsEnabled.value = store.biometricsEnabled
            _autoLockSeconds.value = store.autoLockSeconds
            _blockScreenshots.value = store.blockScreenshots
            _lockoutUntil.value = store.lockoutUntil
            _vaultEnabledIds.value = store.vaultEnabledIds()
            _state.value = if (store.hasAppPin()) LockState.Locked else LockState.NotSetUp
            ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
            initialized = true
        }
    }

    // App PIN ---------------------------------------------------------------------------------

    /** Stores a new app PIN. Does not unlock; call [completeOnboarding] or [markUnlocked]. */
    suspend fun savePin(pin: String) {
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt, PinHasher.ITERATIONS) }
        store.saveAppPin(hash, salt, PinHasher.ITERATIONS)
        resetFailures()
    }

    /** Checks the app PIN and applies the wrong-attempt lockout rules. */
    suspend fun checkPin(pin: String): PinResult {
        val remaining = lockoutSecondsRemaining()
        if (remaining > 0) return PinResult.LockedOut(remaining)
        val correct = withContext(Dispatchers.Default) { store.verifyAppPin(pin) }
        if (correct) {
            resetFailures()
            return PinResult.Success
        }
        val attempts = store.failedAttempts + 1
        if (attempts < MAX_ATTEMPTS) {
            store.failedAttempts = attempts
            return PinResult.Wrong(MAX_ATTEMPTS - attempts)
        }
        val level = store.lockoutLevel + 1
        val seconds = when (level) {
            1 -> 30
            2 -> 60
            else -> 300
        }
        val until = System.currentTimeMillis() + seconds * 1000L
        store.failedAttempts = 0
        store.lockoutLevel = level
        store.lockoutUntil = until
        _lockoutUntil.value = until
        return PinResult.LockedOut(seconds)
    }

    /** Whole seconds left in the current lockout, or 0. */
    fun lockoutSecondsRemaining(): Int {
        val left = _lockoutUntil.value - System.currentTimeMillis()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    private fun resetFailures() {
        store.failedAttempts = 0
        store.lockoutLevel = 0
        store.lockoutUntil = 0L
        _lockoutUntil.value = 0L
    }

    // Lock state ------------------------------------------------------------------------------

    /** Opens the app after a correct PIN or a successful biometric check. */
    fun markUnlocked() {
        _state.value = LockState.Unlocked
    }

    /** Finishes first-run setup and opens the app. */
    fun completeOnboarding() {
        _state.value = LockState.Unlocked
    }

    /** Locks the app and every vault. */
    fun lockNow() {
        _unlockedVaults.value = emptySet()
        if (_state.value == LockState.Unlocked) _state.value = LockState.Locked
    }

    /** Wipes the lock store and returns to first-run setup. The caller erases the database first. */
    fun resetAll() {
        store.clearAll()
        _biometricsEnabled.value = false
        _autoLockSeconds.value = 30
        _blockScreenshots.value = false
        _lockoutUntil.value = 0L
        _vaultEnabledIds.value = emptySet()
        _unlockedVaults.value = emptySet()
        _state.value = LockState.NotSetUp
    }

    private fun handleBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
        // Vaults re-lock as soon as the app leaves the foreground.
        _unlockedVaults.value = emptySet()
    }

    private fun handleForeground() {
        val since = backgroundedAt
        backgroundedAt = -1L
        if (since < 0 || _state.value != LockState.Unlocked) return
        val elapsed = SystemClock.elapsedRealtime() - since
        if (elapsed >= _autoLockSeconds.value * 1000L) lockNow()
    }

    // Settings --------------------------------------------------------------------------------

    fun setBiometricsEnabled(enabled: Boolean) {
        store.biometricsEnabled = enabled
        _biometricsEnabled.value = enabled
    }

    /** Allowed values: 0 (immediately), 30, 60, 300. */
    fun setAutoLockSeconds(seconds: Int) {
        store.autoLockSeconds = seconds
        _autoLockSeconds.value = seconds
    }

    fun setBlockScreenshots(block: Boolean) {
        store.blockScreenshots = block
        _blockScreenshots.value = block
    }

    // Vault locks -----------------------------------------------------------------------------

    /** Turns the vault lock on, or changes its PIN. The vault stays unlocked for this session. */
    suspend fun setVaultPin(vaultId: String, pin: String) {
        val salt = PinHasher.newSalt()
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin, salt, PinHasher.ITERATIONS) }
        store.saveVaultPin(vaultId, hash, salt, PinHasher.ITERATIONS)
        _vaultEnabledIds.value = store.vaultEnabledIds()
        _unlockedVaults.value = _unlockedVaults.value + vaultId
    }

    suspend fun verifyVaultPin(vaultId: String, pin: String): PinResult {
        val correct = withContext(Dispatchers.Default) { store.verifyVaultPin(vaultId, pin) }
        return if (correct) PinResult.Success else PinResult.Wrong()
    }

    fun unlockVault(vaultId: String) {
        _unlockedVaults.value = _unlockedVaults.value + vaultId
    }

    fun disableVaultLock(vaultId: String) {
        store.clearVaultPin(vaultId)
        _vaultEnabledIds.value = store.vaultEnabledIds()
        _unlockedVaults.value = _unlockedVaults.value - vaultId
    }
}

/** Gives composables the initialized [AppLockManager]. */
@Composable
fun rememberLockManager(): AppLockManager {
    val appContext = LocalContext.current.applicationContext
    return remember {
        AppLockManager.ensureInitialized(appContext)
        AppLockManager
    }
}
