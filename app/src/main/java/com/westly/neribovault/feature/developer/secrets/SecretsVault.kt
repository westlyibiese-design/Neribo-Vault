package com.westly.neribovault.feature.developer.secrets

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
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.data.repository.AuditRepository
import com.westly.neribovault.data.repository.SecretsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who is looking at the secrets right now. */
enum class SessionMode { Locked, Real, Decoy }

/** Whether the secrets PIN exists yet. */
enum class SecretsSetup { Loading, Unavailable, NotSetUp, Ready }

/** Result of entering a PIN to open the secrets. Never says which PIN was close. */
sealed interface UnlockResult {
    class Success(val mode: SessionMode) : UnlockResult
    object Wrong : UnlockResult
    class LockedOut(val secondsLeft: Int) : UnlockResult
    object Unavailable : UnlockResult
}

/** Result of changing the main PIN or the second PIN. */
sealed interface PinChangeResult {
    object Success : PinChangeResult
    object WrongPin : PinChangeResult
    class LockedOut(val secondsLeft: Int) : PinChangeResult
    object NotAllowed : PinChangeResult
    object Failed : PinChangeResult
}

/**
 * The one place that knows the secrets key. After a correct main PIN the key lives only in
 * memory in this object, and is zeroed when the session ends (two idle minutes, the Developer
 * vault or the app locking, the app going to the background, or the lock button).
 * The second PIN starts a session without a key, which only ever shows believable fakes and
 * can't change anything.
 */
object SecretsVault {
    /** Shown whenever something is not allowed. Deliberately says nothing about why. */
    const val BLOCKED_MESSAGE = "Can't do that right now"

    private const val IDLE_TIMEOUT_MS = 2 * 60 * 1000L
    private const val MAX_ATTEMPTS = 5
    private const val MAX_LOCKOUT_SECONDS = 300
    private const val DEVELOPER_VAULT_ID = "developer"
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

    private val _mode = MutableStateFlow(SessionMode.Locked)

    /** The current session. */
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _setup = MutableStateFlow(SecretsSetup.Loading)

    /** Whether the main PIN has been created. */
    val setup: StateFlow<SecretsSetup> = _setup.asStateFlow()

    private val _hasDecoyPin = MutableStateFlow(false)

    /** Whether a second PIN exists. Only the real-PIN editor ever reads this. */
    val hasDecoyPin: StateFlow<Boolean> = _hasDecoyPin.asStateFlow()

    private val _lockoutUntil = MutableStateFlow(0L)

    /** Wall-clock millis until which PIN entry is blocked, or 0. */
    val lockoutUntil: StateFlow<Long> = _lockoutUntil.asStateFlow()

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            SecretsClipboard.clearIfExpired()
        }

        override fun onStop(owner: LifecycleOwner) {
            // The clipboard timer keeps running so a copied secret can still be pasted elsewhere.
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
            var developerWasUnlocked = false
            AppLockManager.unlockedVaults.collect { ids ->
                val unlocked = DEVELOPER_VAULT_ID in ids
                if (developerWasUnlocked && !unlocked) endSession(clearClipboard = isForeground())
                developerWasUnlocked = unlocked
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
        val prefs = SecretsPrefs.get(appContext)
        if (prefs == null) {
            _setup.value = SecretsSetup.Unavailable
        } else {
            publishStoredState(prefs)
        }
    }

    private fun publishStoredState(prefs: SecretsPrefs) {
        _hasDecoyPin.value = prefs.pinB() != null
        _lockoutUntil.value = prefs.lockoutUntil
        _setup.value = if (prefs.pinA() != null && prefs.vaultSalt() != null) {
            SecretsSetup.Ready
        } else {
            SecretsSetup.NotSetUp
        }
    }

    /** Whole seconds left in the current lockout, or 0. */
    fun lockoutSecondsRemaining(): Int = secondsUntil(_lockoutUntil.value)

    private fun secondsUntil(until: Long): Int {
        val leftMs = until - System.currentTimeMillis()
        if (leftMs <= 0L) return 0
        return ((leftMs + 999L) / 1000L).toInt().coerceAtMost(MAX_LOCKOUT_SECONDS)
    }

    // Sessions --------------------------------------------------------------------------------

    private fun startSession(newMode: SessionMode, newKey: ByteArray?) {
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
            idleJob = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                endSession(clearClipboard = true)
            }
        }
    }

    /** Call on every secrets action; it keeps the session alive for another two minutes. */
    fun touch() {
        if (_mode.value != SessionMode.Locked) scheduleIdleEnd()
    }

    /** Ends the session and zeroes the key. */
    fun endSession(clearClipboard: Boolean = true) {
        synchronized(keyLock) {
            key?.fill(0)
            key = null
            idleJob?.cancel()
            idleJob = null
        }
        _mode.value = SessionMode.Locked
        if (clearClipboard) SecretsClipboard.clearNow()
    }

    // Values ----------------------------------------------------------------------------------

    /** Encrypts [plaintext] with the session key, or returns null without a real session. */
    fun encrypt(plaintext: String): SecretsCrypto.Sealed? {
        // The work happens under the lock so the key can't be zeroed halfway through.
        return synchronized(keyLock) {
            val current = key
            if (current == null) {
                null
            } else {
                try {
                    SecretsCrypto.encrypt(current, plaintext)
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
            if (current == null) null else SecretsCrypto.decrypt(current, ciphertext, iv)
        }
    }

    /**
     * The value to show or copy for [secret] in the current session: the real value in a real
     * session, a fake in the second kind of session, and null while locked.
     */
    fun displayValue(secret: SecretEntity): String? {
        if (!secret.isSecret) return secret.publicValue
        return when (_mode.value) {
            SessionMode.Real -> {
                val cipher = secret.ciphertext
                val iv = secret.iv
                if (cipher == null || iv == null) null else decrypt(cipher, iv)
            }
            SessionMode.Decoy ->
                secret.decoyValue?.takeIf { it.isNotBlank() }
                    ?: SecretsDecoys.fake(secret.category, secret.id)
            SessionMode.Locked -> null
        }
    }

    /** Writes an audit entry. Never pass a label or a value. */
    suspend fun logEvent(action: String, secretId: String?) {
        val repository = audit ?: return
        try {
            repository.log(action, "secret", secretId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The audit log must never break the secrets.
        }
    }

    // PINs ------------------------------------------------------------------------------------

    /** Creates the main PIN (and optionally the second PIN) and opens a real session. */
    suspend fun setUp(pinA: String, pinB: String?): Boolean {
        if (pinB != null && pinB == pinA) return false
        // Not cancellable: once the PIN is written the session must start too.
        return withContext(NonCancellable + Dispatchers.Default) {
            val prefs = SecretsPrefs.get(appContext) ?: return@withContext false
            // Never overwrite a setup that already exists.
            if (prefs.pinA() != null) return@withContext false
            val vaultSalt = PinHasher.newSalt()
            val recordA = SecretsCrypto.newPinRecord(pinA)
            val recordB = if (pinB != null) SecretsCrypto.newPinRecord(pinB) else null
            val derived = SecretsCrypto.deriveKey(pinA, vaultSalt)
            if (!prefs.writeSetup(vaultSalt, recordA, recordB)) {
                derived.fill(0)
                return@withContext false
            }
            publishStoredState(prefs)
            startSession(SessionMode.Real, derived)
            logEvent("pin_changed", null)
            if (pinB != null) logEvent("decoy_pin_set", null)
            true
        }
    }

    private class Attempt(val result: UnlockResult, val key: ByteArray?)

    /** Checks [pin] against both PINs at once and starts the matching kind of session. */
    suspend fun unlock(pin: String): UnlockResult {
        val attempt = withContext(Dispatchers.Default) { checkPin(pin) }
        val result = attempt.result
        if (result is UnlockResult.Success) startSession(result.mode, attempt.key)
        return result
    }

    private suspend fun checkPin(pin: String): Attempt {
        val prefs = SecretsPrefs.get(appContext) ?: return Attempt(UnlockResult.Unavailable, null)
        val remaining = secondsUntil(prefs.lockoutUntil)
        if (remaining > 0) return Attempt(UnlockResult.LockedOut(remaining), null)
        val recordA = prefs.pinA()
        val vaultSalt = prefs.vaultSalt()
        if (recordA == null || vaultSalt == null) return Attempt(UnlockResult.Unavailable, null)
        val aMatches = SecretsCrypto.matches(pin, recordA)
        val recordB = prefs.pinB()
        val bMatches = recordB != null && SecretsCrypto.matches(pin, recordB)
        // Always derived, so a right and a wrong PIN cost the same.
        val derived = SecretsCrypto.deriveKey(pin, vaultSalt)
        return when {
            aMatches -> {
                resetThrottle(prefs)
                Attempt(UnlockResult.Success(SessionMode.Real), derived)
            }
            bMatches -> {
                derived.fill(0)
                resetThrottle(prefs)
                Attempt(UnlockResult.Success(SessionMode.Decoy), null)
            }
            else -> {
                derived.fill(0)
                Attempt(registerFailure(prefs), null)
            }
        }
    }

    private fun resetThrottle(prefs: SecretsPrefs) {
        prefs.saveThrottle(attempts = 0, level = 0, until = 0L)
        _lockoutUntil.value = 0L
    }

    /** Counts a wrong PIN: five in a row lock access for 30 seconds, then 1 minute, then 5. */
    private suspend fun registerFailure(prefs: SecretsPrefs): UnlockResult {
        logEvent("secrets_unlock_failed", null)
        val attempts = prefs.failedAttempts + 1
        if (attempts < MAX_ATTEMPTS) {
            prefs.saveThrottle(attempts = attempts, level = prefs.lockoutLevel, until = 0L)
            return UnlockResult.Wrong
        }
        val level = prefs.lockoutLevel + 1
        val seconds = LOCKOUT_SECONDS[(level - 1).coerceIn(0, LOCKOUT_SECONDS.lastIndex)]
        val until = System.currentTimeMillis() + seconds * 1000L
        prefs.saveThrottle(attempts = 0, level = level, until = until)
        _lockoutUntil.value = until
        return UnlockResult.LockedOut(seconds)
    }

    /**
     * Changes the main PIN. Every secret (including the ones in the trash) is decrypted with the
     * old key and encrypted with a key from the new PIN and a new vault salt. All rows are
     * written in one database transaction, and only then are the stored hash and salt replaced.
     * If anything fails, nothing changes.
     */
    suspend fun changePinA(
        currentPin: String,
        newPin: String,
        database: NeriboDatabase,
        secrets: SecretsRepository,
    ): PinChangeResult {
        if (_mode.value != SessionMode.Real) return PinChangeResult.NotAllowed
        // Not cancellable: leaving the screen halfway must never split the database and the PIN.
        return withContext(NonCancellable + Dispatchers.Default) {
            rekey(currentPin, newPin, database, secrets)
        }
    }

    private suspend fun rekey(
        currentPin: String,
        newPin: String,
        database: NeriboDatabase,
        secrets: SecretsRepository,
    ): PinChangeResult {
        val prefs = SecretsPrefs.get(appContext) ?: return PinChangeResult.Failed
        val remaining = secondsUntil(prefs.lockoutUntil)
        if (remaining > 0) return PinChangeResult.LockedOut(remaining)
        val recordA = prefs.pinA()
        val oldSalt = prefs.vaultSalt()
        if (recordA == null || oldSalt == null) return PinChangeResult.Failed
        if (!SecretsCrypto.matches(currentPin, recordA)) {
            val failure = registerFailure(prefs)
            return if (failure is UnlockResult.LockedOut) {
                PinChangeResult.LockedOut(failure.secondsLeft)
            } else {
                PinChangeResult.WrongPin
            }
        }
        resetThrottle(prefs)
        if (newPin == currentPin) return PinChangeResult.NotAllowed
        val recordB = prefs.pinB()
        if (recordB != null && SecretsCrypto.matches(newPin, recordB)) {
            return PinChangeResult.NotAllowed
        }

        val oldKey = SecretsCrypto.deriveKey(currentPin, oldSalt)
        val newSalt = PinHasher.newSalt()
        val newKey = SecretsCrypto.deriveKey(newPin, newSalt)
        val newRecord = SecretsCrypto.newPinRecord(newPin)
        var succeeded = false
        try {
            val originals = (secrets.observeAll().first() + secrets.observeTrashed().first())
                .filter { it.isSecret && it.ciphertext != null && it.iv != null }
            val rekeyed = ArrayList<SecretEntity>(originals.size)
            for (row in originals) {
                val cipher = row.ciphertext ?: continue
                val iv = row.iv ?: continue
                val plain = SecretsCrypto.decrypt(oldKey, cipher, iv)
                    ?: return PinChangeResult.Failed
                val sealed = SecretsCrypto.encrypt(newKey, plain)
                rekeyed.add(row.copy(ciphertext = sealed.ciphertext, iv = sealed.iv))
            }
            try {
                database.withTransaction {
                    rekeyed.forEach { secrets.upsert(it) }
                }
            } catch (e: Exception) {
                // The transaction rolled back, so every row still has its old ciphertext.
                return PinChangeResult.Failed
            }
            if (!prefs.replacePinA(newSalt, newRecord)) {
                // The new hash could not be stored, so put the old ciphertexts back.
                try {
                    database.withTransaction {
                        originals.forEach { secrets.upsert(it) }
                    }
                } catch (e: Exception) {
                    // Nothing more can be done here.
                }
                return PinChangeResult.Failed
            }
            publishStoredState(prefs)
            startSession(SessionMode.Real, newKey)
            succeeded = true
            logEvent("pin_changed", null)
            return PinChangeResult.Success
        } catch (e: GeneralSecurityException) {
            return PinChangeResult.Failed
        } finally {
            oldKey.fill(0)
            if (!succeeded) newKey.fill(0)
        }
    }

    /** Sets, changes (non-null) or removes (null) the second PIN. Needs a real session. */
    suspend fun setDecoyPin(newPin: String?): PinChangeResult {
        if (_mode.value != SessionMode.Real) return PinChangeResult.NotAllowed
        return withContext(NonCancellable + Dispatchers.Default) {
            val prefs = SecretsPrefs.get(appContext) ?: return@withContext PinChangeResult.Failed
            val recordA = prefs.pinA() ?: return@withContext PinChangeResult.Failed
            val record: PinRecord? = if (newPin == null) {
                null
            } else {
                if (SecretsCrypto.matches(newPin, recordA)) {
                    return@withContext PinChangeResult.NotAllowed
                }
                SecretsCrypto.newPinRecord(newPin)
            }
            if (!prefs.savePinB(record)) return@withContext PinChangeResult.Failed
            publishStoredState(prefs)
            logEvent(if (newPin == null) "pin_changed" else "decoy_pin_set", null)
            PinChangeResult.Success
        }
    }

    /** Called by [SecretsBackup] after it replaced the stored setup. */
    internal fun onStorageReplaced(context: Context) {
        ensureContext(context)
        endSession(clearClipboard = true)
        val prefs = SecretsPrefs.get(appContext)
        if (prefs == null) {
            _setup.value = SecretsSetup.Unavailable
        } else {
            publishStoredState(prefs)
        }
    }
}
