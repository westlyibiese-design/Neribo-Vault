package com.westly.neribovault.feature.accounts.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.lock.AppLockManager
import com.westly.neribovault.core.lock.LockState
import com.westly.neribovault.core.lock.PinHasher
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.repository.AccountFieldsRepository
import com.westly.neribovault.data.repository.AccountsRepository
import com.westly.neribovault.data.repository.AuditRepository
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who is looking at the Accounts passwords right now. */
enum class AccountsSessionMode { Locked, Real, Decoy }

/** Whether the Accounts PIN exists yet. */
enum class AccountsSetup { Loading, Unavailable, NotSetUp, Ready }

/** Result of entering a PIN to open Accounts. Never says which PIN was close. */
sealed interface AccountsUnlockResult {
    class Success(val mode: AccountsSessionMode) : AccountsUnlockResult
    object Wrong : AccountsUnlockResult
    class LockedOut(val secondsLeft: Int) : AccountsUnlockResult
    object Unavailable : AccountsUnlockResult
}

/** Result of changing the main PIN or the second PIN. */
sealed interface AccountsPinChangeResult {
    object Success : AccountsPinChangeResult
    object WrongPin : AccountsPinChangeResult
    class LockedOut(val secondsLeft: Int) : AccountsPinChangeResult
    object NotAllowed : AccountsPinChangeResult
    object Failed : AccountsPinChangeResult
}

/**
 * The one place that knows the Accounts key. This is a renamed copy of the Developer secrets
 * engine with its own PINs, its own preferences file and its own audit entries.
 *
 * After a correct main PIN the key lives only in memory in this object, and is zeroed when the
 * session ends (two idle minutes, the Accounts vault lock or the app locking, the app going to
 * the background, or the lock button). The second PIN starts a session without a key, which only
 * ever shows believable fakes and can't change anything.
 */
object AccountsVault {
    /** Shown whenever something is not allowed. Deliberately says nothing about why. */
    const val BLOCKED_MESSAGE = "Can't do that right now"

    private const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L
    private const val MAX_ATTEMPTS = 5
    private const val MAX_LOCKOUT_SECONDS = 300
    private const val ACCOUNTS_VAULT_ID = "accounts"
    private val LOCKOUT_SECONDS = intArrayOf(30, 60, 300)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyLock = Any()
    private val attachLock = Any()
    private var attached = false
    private lateinit var appContext: Context

    @Volatile
    private var audit: AuditRepository? = null

    // Guarded by keyLock.
    private var key: ByteArray? = null
    private var idleJob: Job? = null

    @Volatile
    private var idleDeadline = 0L

    private val _mode = MutableStateFlow(AccountsSessionMode.Locked)

    /** The current session. */
    val mode: StateFlow<AccountsSessionMode> = _mode.asStateFlow()

    private val _setup = MutableStateFlow(AccountsSetup.Loading)

    /** Whether the main PIN has been created. */
    val setup: StateFlow<AccountsSetup> = _setup.asStateFlow()

    private val _hasDecoyPin = MutableStateFlow(false)

    /** Whether a second PIN exists. Only the real-PIN settings ever read this. */
    val hasDecoyPin: StateFlow<Boolean> = _hasDecoyPin.asStateFlow()

    private val _lockoutUntil = MutableStateFlow(0L)

    /** Wall-clock millis until which PIN entry is blocked, or 0. */
    val lockoutUntil: StateFlow<Long> = _lockoutUntil.asStateFlow()

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            AccountsClipboard.clearIfExpired()
        }

        override fun onStop(owner: LifecycleOwner) {
            // The clipboard timer keeps running so a copied password can still be pasted elsewhere.
            endSession(clearClipboard = false)
        }
    }

    /** Starts watching the app lifecycle and loads the stored setup. Safe to call many times. */
    fun attach(context: Context) {
        synchronized(attachLock) {
            if (attached) return
            ensureContext(context)
            attached = true
        }
        AppLockManager.ensureInitialized(appContext)
        audit = (appContext as? NeriboApp)?.container?.auditRepository
        scope.launch { ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver) }
        scope.launch {
            AppLockManager.state.collect { state ->
                if (state != LockState.Unlocked) endSession(clearClipboard = isForeground())
            }
        }
        scope.launch {
            var accountsWasUnlocked = false
            AppLockManager.unlockedVaults.collect { ids ->
                val unlocked = ACCOUNTS_VAULT_ID in ids
                if (accountsWasUnlocked && !unlocked) endSession(clearClipboard = isForeground())
                accountsWasUnlocked = unlocked
            }
        }
        scope.launch(Dispatchers.IO) { loadStoredState() }
    }

    private fun ensureContext(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun loadStoredState() {
        val prefs = AccountsPrefs.get(appContext)
        if (prefs == null) {
            _setup.value = AccountsSetup.Unavailable
        } else {
            publishStoredState(prefs)
        }
    }

    private fun publishStoredState(prefs: AccountsPrefs) {
        _hasDecoyPin.value = prefs.pinB() != null
        _lockoutUntil.value = prefs.lockoutUntil
        _setup.value = if (prefs.pinA() != null && prefs.vaultSalt() != null) {
            AccountsSetup.Ready
        } else {
            AccountsSetup.NotSetUp
        }
    }

    /** Whole seconds left in the current lockout, or 0. */
    fun lockoutSecondsRemaining(): Int = secondsUntil(_lockoutUntil.value)

    /** Whole seconds left on the 2-minute idle timer, or 0 while locked. Drives the PIN screen. */
    fun idleSecondsRemaining(): Int {
        if (_mode.value == AccountsSessionMode.Locked) return 0
        val leftMs = idleDeadline - System.currentTimeMillis()
        if (leftMs <= 0L) return 0
        return ((leftMs + 999L) / 1000L).toInt().coerceAtMost((IDLE_TIMEOUT_MS / 1000L).toInt())
    }

    private fun secondsUntil(until: Long): Int {
        val leftMs = until - System.currentTimeMillis()
        if (leftMs <= 0L) return 0
        return ((leftMs + 999L) / 1000L).toInt().coerceAtMost(MAX_LOCKOUT_SECONDS)
    }

    // Sessions --------------------------------------------------------------------------------

    private fun startSession(newMode: AccountsSessionMode, newKey: ByteArray?) {
        synchronized(keyLock) {
            key?.fill(0)
            key = newKey
        }
        _mode.value = newMode
        scheduleIdleEnd()
    }

    private fun scheduleIdleEnd() {
        synchronized(keyLock) {
            idleJob?.cancel()
            idleDeadline = System.currentTimeMillis() + IDLE_TIMEOUT_MS
            idleJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                endSession(clearClipboard = true)
            }
        }
    }

    /** Call on every Accounts action; it keeps the session alive for another two minutes. */
    fun touch() {
        if (_mode.value != AccountsSessionMode.Locked) scheduleIdleEnd()
    }

    /** Ends the session and zeroes the key. */
    fun endSession(clearClipboard: Boolean = true) {
        synchronized(keyLock) {
            key?.fill(0)
            key = null
            idleJob?.cancel()
            idleJob = null
            idleDeadline = 0L
        }
        _mode.value = AccountsSessionMode.Locked
        if (clearClipboard) AccountsClipboard.clearNow()
    }

    // Values ----------------------------------------------------------------------------------

    /** Encrypts [plaintext] with the session key, or returns null without a real session. */
    fun encrypt(plaintext: String): AccountsCrypto.Sealed? {
        // The work happens under the lock so the key can't be zeroed halfway through.
        return synchronized(keyLock) {
            val current = key
            if (current == null) {
                null
            } else {
                try {
                    AccountsCrypto.encrypt(current, plaintext)
                } catch (e: GeneralSecurityException) {
                    null
                }
            }
        }
    }

    /** Decrypts a stored value with the session key, or returns null. */
    fun decrypt(ciphertext: String, iv: String): String? {
        return synchronized(keyLock) {
            val current = key
            if (current == null) null else AccountsCrypto.decrypt(current, ciphertext, iv)
        }
    }

    /**
     * The text to show or copy for a secret in the current session: the real value in a real
     * session; the stored decoy text (or a fake from [AccountsCrypto.fake]) in the other kind of
     * session; null when locked. [category] is "password", "api_key" or "text"; [seed] is the id
     * of the row, so the same fake is shown every time.
     */
    fun displayValue(cipher: String?, iv: String?, decoy: String?, category: String, seed: String): String? {
        return when (_mode.value) {
            AccountsSessionMode.Real ->
                if (cipher == null || iv == null) null else decrypt(cipher, iv)
            AccountsSessionMode.Decoy ->
                decoy?.takeIf { it.isNotBlank() } ?: AccountsCrypto.fake(category, seed)
            AccountsSessionMode.Locked -> null
        }
    }

    /** Writes an audit entry. Never pass a label or a value. */
    suspend fun logEvent(action: String, entityId: String?) {
        val repository = audit ?: return
        try {
            repository.log(action, "account", entityId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The audit log must never break the passwords.
        }
    }

    // PINs ------------------------------------------------------------------------------------

    /** Creates the main PIN (and optionally the second PIN) and opens a real session. */
    suspend fun setUp(pinA: String, pinB: String?): Boolean {
        if (pinB != null && pinB == pinA) return false
        // Not cancellable: once the PIN is written the session must start too.
        return withContext(NonCancellable + Dispatchers.Default) {
            val prefs = AccountsPrefs.get(appContext) ?: return@withContext false
            // Never overwrite a setup that already exists.
            if (prefs.pinA() != null) return@withContext false
            val vaultSalt = PinHasher.newSalt()
            val recordA = AccountsCrypto.newPinRecord(pinA)
            val recordB = if (pinB != null) AccountsCrypto.newPinRecord(pinB) else null
            val derived = AccountsCrypto.deriveKey(pinA, vaultSalt)
            if (!prefs.writeSetup(vaultSalt, recordA, recordB)) {
                derived.fill(0)
                return@withContext false
            }
            publishStoredState(prefs)
            startSession(AccountsSessionMode.Real, derived)
            logEvent("pin_set", null)
            if (pinB != null) logEvent("decoy_pin_set", null)
            true
        }
    }

    private class Attempt(val result: AccountsUnlockResult, val key: ByteArray?)

    /** Checks [pin] against both PINs at once and starts the matching kind of session. */
    suspend fun unlock(pin: String): AccountsUnlockResult {
        val attempt = withContext(Dispatchers.Default) { checkPin(pin) }
        val result = attempt.result
        if (result is AccountsUnlockResult.Success) startSession(result.mode, attempt.key)
        return result
    }

    private suspend fun checkPin(pin: String): Attempt {
        val prefs = AccountsPrefs.get(appContext) ?: return Attempt(AccountsUnlockResult.Unavailable, null)
        val remaining = secondsUntil(prefs.lockoutUntil)
        if (remaining > 0) return Attempt(AccountsUnlockResult.LockedOut(remaining), null)
        val recordA = prefs.pinA()
        val vaultSalt = prefs.vaultSalt()
        if (recordA == null || vaultSalt == null) return Attempt(AccountsUnlockResult.Unavailable, null)
        val aMatches = AccountsCrypto.matches(pin, recordA)
        val recordB = prefs.pinB()
        val bMatches = recordB != null && AccountsCrypto.matches(pin, recordB)
        // Always derived, so a right and a wrong PIN cost the same.
        val derived = AccountsCrypto.deriveKey(pin, vaultSalt)
        return when {
            aMatches -> {
                resetThrottle(prefs)
                Attempt(AccountsUnlockResult.Success(AccountsSessionMode.Real), derived)
            }
            bMatches -> {
                derived.fill(0)
                resetThrottle(prefs)
                Attempt(AccountsUnlockResult.Success(AccountsSessionMode.Decoy), null)
            }
            else -> {
                derived.fill(0)
                Attempt(registerFailure(prefs), null)
            }
        }
    }

    private fun resetThrottle(prefs: AccountsPrefs) {
        prefs.saveThrottle(attempts = 0, level = 0, until = 0L)
        _lockoutUntil.value = 0L
    }

    /** Counts a wrong PIN: five in a row lock access for 30 seconds, then 1 minute, then 5. */
    private suspend fun registerFailure(prefs: AccountsPrefs): AccountsUnlockResult {
        logEvent("unlock_failed", null)
        val attempts = prefs.failedAttempts + 1
        if (attempts < MAX_ATTEMPTS) {
            prefs.saveThrottle(attempts = attempts, level = prefs.lockoutLevel, until = 0L)
            return AccountsUnlockResult.Wrong
        }
        val level = prefs.lockoutLevel + 1
        val seconds = LOCKOUT_SECONDS[(level - 1).coerceIn(0, LOCKOUT_SECONDS.lastIndex)]
        val until = System.currentTimeMillis() + seconds * 1000L
        prefs.saveThrottle(attempts = 0, level = level, until = until)
        _lockoutUntil.value = until
        return AccountsUnlockResult.LockedOut(seconds)
    }

    /**
     * Changes the main PIN. Every account password and every secret field (including the ones in
     * the trash) is decrypted with the old key and encrypted with a key from the new PIN and a
     * new vault salt. All rows are written in one database transaction, and only then are the
     * stored hash and salt replaced. If anything fails, nothing changes. Row timestamps are kept,
     * so a password's age does not reset when the PIN changes.
     */
    suspend fun changePinA(
        currentPin: String,
        newPin: String,
        database: NeriboDatabase,
        accounts: AccountsRepository,
        fields: AccountFieldsRepository,
    ): AccountsPinChangeResult {
        if (_mode.value != AccountsSessionMode.Real) return AccountsPinChangeResult.NotAllowed
        // Not cancellable: leaving the screen halfway must never split the database and the PIN.
        return withContext(NonCancellable + Dispatchers.Default) {
            rekey(currentPin, newPin, database, accounts, fields)
        }
    }

    private suspend fun rekey(
        currentPin: String,
        newPin: String,
        database: NeriboDatabase,
        accounts: AccountsRepository,
        fields: AccountFieldsRepository,
    ): AccountsPinChangeResult {
        val prefs = AccountsPrefs.get(appContext) ?: return AccountsPinChangeResult.Failed
        val remaining = secondsUntil(prefs.lockoutUntil)
        if (remaining > 0) return AccountsPinChangeResult.LockedOut(remaining)
        val recordA = prefs.pinA()
        val oldSalt = prefs.vaultSalt()
        if (recordA == null || oldSalt == null) return AccountsPinChangeResult.Failed
        if (!AccountsCrypto.matches(currentPin, recordA)) {
            val failure = registerFailure(prefs)
            return if (failure is AccountsUnlockResult.LockedOut) {
                AccountsPinChangeResult.LockedOut(failure.secondsLeft)
            } else {
                AccountsPinChangeResult.WrongPin
            }
        }
        resetThrottle(prefs)
        if (newPin == currentPin) return AccountsPinChangeResult.NotAllowed
        val recordB = prefs.pinB()
        if (recordB != null && AccountsCrypto.matches(newPin, recordB)) {
            return AccountsPinChangeResult.NotAllowed
        }

        val oldKey = AccountsCrypto.deriveKey(currentPin, oldSalt)
        val newSalt = PinHasher.newSalt()
        val newKey = AccountsCrypto.deriveKey(newPin, newSalt)
        val newRecord = AccountsCrypto.newPinRecord(newPin)
        var succeeded = false
        try {
            val originalAccounts = accounts.getAllIncludingTrashed()
                .filter { it.passwordCipher != null && it.passwordIv != null }
            val originalFields = fields.getAllIncludingTrashed()
                .filter { it.isSecret && it.valueCipher != null && it.valueIv != null }

            val rekeyedAccounts = ArrayList<AccountEntity>(originalAccounts.size)
            for (row in originalAccounts) {
                val cipher = row.passwordCipher ?: continue
                val iv = row.passwordIv ?: continue
                val plain = AccountsCrypto.decrypt(oldKey, cipher, iv)
                    ?: return AccountsPinChangeResult.Failed
                val sealed = AccountsCrypto.encrypt(newKey, plain)
                rekeyedAccounts.add(row.copy(passwordCipher = sealed.ciphertext, passwordIv = sealed.iv))
            }
            val rekeyedFields = ArrayList<AccountFieldEntity>(originalFields.size)
            for (row in originalFields) {
                val cipher = row.valueCipher ?: continue
                val iv = row.valueIv ?: continue
                val plain = AccountsCrypto.decrypt(oldKey, cipher, iv)
                    ?: return AccountsPinChangeResult.Failed
                val sealed = AccountsCrypto.encrypt(newKey, plain)
                rekeyedFields.add(row.copy(valueCipher = sealed.ciphertext, valueIv = sealed.iv))
            }

            try {
                database.withTransaction {
                    rekeyedAccounts.forEach { accounts.upsertExact(it) }
                    rekeyedFields.forEach { fields.upsertExact(it) }
                }
            } catch (e: Exception) {
                // The transaction rolled back, so every row still has its old ciphertext.
                return AccountsPinChangeResult.Failed
            }
            if (!prefs.replacePinA(newSalt, newRecord)) {
                // The new hash could not be stored, so put the old ciphertexts back.
                try {
                    database.withTransaction {
                        originalAccounts.forEach { accounts.upsertExact(it) }
                        originalFields.forEach { fields.upsertExact(it) }
                    }
                } catch (e: Exception) {
                    // Nothing more can be done here.
                }
                return AccountsPinChangeResult.Failed
            }
            publishStoredState(prefs)
            startSession(AccountsSessionMode.Real, newKey)
            succeeded = true
            logEvent("pin_changed", null)
            return AccountsPinChangeResult.Success
        } catch (e: GeneralSecurityException) {
            return AccountsPinChangeResult.Failed
        } finally {
            oldKey.fill(0)
            if (!succeeded) newKey.fill(0)
        }
    }

    /** Sets, changes (non-null) or removes (null) the second PIN. Needs a real session. */
    suspend fun setDecoyPin(newPin: String?): AccountsPinChangeResult {
        if (_mode.value != AccountsSessionMode.Real) return AccountsPinChangeResult.NotAllowed
        return withContext(NonCancellable + Dispatchers.Default) {
            val prefs = AccountsPrefs.get(appContext) ?: return@withContext AccountsPinChangeResult.Failed
            val recordA = prefs.pinA() ?: return@withContext AccountsPinChangeResult.Failed
            val record: PinRecord? = if (newPin == null) {
                null
            } else {
                if (AccountsCrypto.matches(newPin, recordA)) {
                    return@withContext AccountsPinChangeResult.NotAllowed
                }
                AccountsCrypto.newPinRecord(newPin)
            }
            if (!prefs.savePinB(record)) return@withContext AccountsPinChangeResult.Failed
            publishStoredState(prefs)
            logEvent(if (newPin == null) "pin_changed" else "decoy_pin_set", null)
            AccountsPinChangeResult.Success
        }
    }

    /** Called by the backup restore hook (part A3) after it replaced the stored setup. */
    fun onStorageReplaced(context: Context) {
        ensureContext(context)
        endSession(clearClipboard = true)
        val prefs = AccountsPrefs.get(appContext)
        if (prefs == null) {
            _setup.value = AccountsSetup.Unavailable
        } else {
            publishStoredState(prefs)
        }
    }
}
