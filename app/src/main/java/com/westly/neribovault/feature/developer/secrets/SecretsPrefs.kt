package com.westly.neribovault.feature.developer.secrets

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** One stored PIN check: the PBKDF2 hash, its own random salt and the iteration count. */
class PinRecord(val hash: ByteArray, val salt: ByteArray, val iterations: Int)

/**
 * The small encrypted store behind the secrets vault (file `neribo_secrets_prefs`).
 * It holds the vault KDF salt, the PIN A and PIN B verification hashes and the failed-attempt
 * counters. It never holds a PIN or a secret value, and it is separate from the app lock store.
 *
 * Unlike the app lock store this one is never reset automatically: a failure to open it can be
 * temporary, and resetting would permanently orphan every encrypted secret.
 */
class SecretsPrefs private constructor(private val prefs: SharedPreferences) {

    fun vaultSalt(): ByteArray? = getBytes(KEY_VAULT_SALT)

    fun pinA(): PinRecord? = getPin(PREFIX_A)

    fun pinB(): PinRecord? = getPin(PREFIX_B)

    val failedAttempts: Int get() = getInt(KEY_FAILED_ATTEMPTS)

    val lockoutLevel: Int get() = getInt(KEY_LOCKOUT_LEVEL)

    val lockoutUntil: Long
        get() = try {
            prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        } catch (e: Exception) {
            0L
        }

    /** Stores the throttling counters in one write. */
    fun saveThrottle(attempts: Int, level: Int, until: Long) {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, attempts)
            .putInt(KEY_LOCKOUT_LEVEL, level)
            .putLong(KEY_LOCKOUT_UNTIL, until)
            .apply()
    }

    /**
     * Writes a complete secrets setup (vault salt, PIN A and optional PIN B) in a single commit
     * and clears the throttling counters. Returns false if the write did not succeed.
     */
    fun writeSetup(vaultSalt: ByteArray, pinA: PinRecord, pinB: PinRecord?): Boolean {
        val editor = prefs.edit()
        editor.putString(KEY_VAULT_SALT, encode(vaultSalt))
        putPin(editor, PREFIX_A, pinA)
        if (pinB != null) putPin(editor, PREFIX_B, pinB) else removePin(editor, PREFIX_B)
        editor.putInt(KEY_FAILED_ATTEMPTS, 0)
        editor.putInt(KEY_LOCKOUT_LEVEL, 0)
        editor.putLong(KEY_LOCKOUT_UNTIL, 0L)
        return editor.commit()
    }

    /** Replaces the vault salt and PIN A in a single commit, leaving PIN B untouched. */
    fun replacePinA(vaultSalt: ByteArray, pinA: PinRecord): Boolean {
        val editor = prefs.edit()
        editor.putString(KEY_VAULT_SALT, encode(vaultSalt))
        putPin(editor, PREFIX_A, pinA)
        editor.putInt(KEY_FAILED_ATTEMPTS, 0)
        editor.putInt(KEY_LOCKOUT_LEVEL, 0)
        editor.putLong(KEY_LOCKOUT_UNTIL, 0L)
        return editor.commit()
    }

    /** Sets, changes (non-null) or removes (null) PIN B. Returns false if the write failed. */
    fun savePinB(pinB: PinRecord?): Boolean {
        val editor = prefs.edit()
        if (pinB != null) putPin(editor, PREFIX_B, pinB) else removePin(editor, PREFIX_B)
        return editor.commit()
    }

    private fun putPin(editor: SharedPreferences.Editor, prefix: String, record: PinRecord) {
        editor.putString("${prefix}_hash", encode(record.hash))
        editor.putString("${prefix}_salt", encode(record.salt))
        editor.putInt("${prefix}_iter", record.iterations)
    }

    private fun removePin(editor: SharedPreferences.Editor, prefix: String) {
        editor.remove("${prefix}_hash")
        editor.remove("${prefix}_salt")
        editor.remove("${prefix}_iter")
    }

    private fun getPin(prefix: String): PinRecord? {
        val hash = getBytes("${prefix}_hash") ?: return null
        val salt = getBytes("${prefix}_salt") ?: return null
        val iterations = getInt("${prefix}_iter")
        if (iterations <= 0) return null
        return PinRecord(hash = hash, salt = salt, iterations = iterations)
    }

    private fun getBytes(key: String): ByteArray? = try {
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (e: Exception) {
        null
    }

    private fun getInt(key: String): Int = try {
        prefs.getInt(key, 0)
    } catch (e: Exception) {
        0
    }

    companion object {
        private const val FILE_NAME = "neribo_secrets_prefs"

        // A key alias of its own, so the app lock store resetting its keystore key can never
        // take this store's key with it.
        private const val MASTER_KEY_ALIAS = "_neribo_secrets_master_key_"

        private const val PREFIX_A = "pin_a"
        private const val PREFIX_B = "pin_b"
        private const val KEY_VAULT_SALT = "vault_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_LEVEL = "lockout_level"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"

        @Volatile
        private var instance: SecretsPrefs? = null

        /** The shared store, or null if the Android keystore cannot open it right now. */
        fun get(context: Context): SecretsPrefs? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = open(context.applicationContext)
                instance = created
                return created
            }
        }

        private fun open(appContext: Context): SecretsPrefs? = try {
            val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            SecretsPrefs(prefs)
        } catch (e: Exception) {
            null
        }

        internal fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        internal fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
