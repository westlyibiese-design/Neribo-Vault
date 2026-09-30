package com.westly.neribovault.core.lock

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Encrypted storage for everything the lock needs: the app PIN hash, per-vault PIN hashes and
 * the lock settings. If the Android keystore misbehaves, only this store is reset and the app is
 * treated as not yet set up. It never crashes. If encryption is impossible even after a reset,
 * values live in memory only for this run, so nothing sensitive is written unencrypted.
 */
class LockStore(context: Context) {
    private val appContext = context.applicationContext
    private val memory = HashMap<String, Any>()
    private val prefs: SharedPreferences? = openEncryptedPrefs()

    // App PIN ---------------------------------------------------------------------------------

    fun hasAppPin(): Boolean = getString(KEY_APP_HASH) != null

    fun saveAppPin(hash: ByteArray, salt: ByteArray, iterations: Int) {
        savePin(APP_PREFIX, hash, salt, iterations)
    }

    fun verifyAppPin(pin: String): Boolean = verifyPin(APP_PREFIX, pin)

    // Vault PINs ------------------------------------------------------------------------------

    fun vaultEnabledIds(): Set<String> {
        val joined = getString(KEY_VAULT_IDS).orEmpty()
        return if (joined.isEmpty()) emptySet() else joined.split(ID_SEPARATOR).toSet()
    }

    fun saveVaultPin(vaultId: String, hash: ByteArray, salt: ByteArray, iterations: Int) {
        savePin(vaultPrefix(vaultId), hash, salt, iterations)
        put(KEY_VAULT_IDS, (vaultEnabledIds() + vaultId).joinToString(ID_SEPARATOR))
    }

    fun verifyVaultPin(vaultId: String, pin: String): Boolean = verifyPin(vaultPrefix(vaultId), pin)

    fun clearVaultPin(vaultId: String) {
        val prefix = vaultPrefix(vaultId)
        remove("${prefix}_hash")
        remove("${prefix}_salt")
        remove("${prefix}_iter")
        put(KEY_VAULT_IDS, (vaultEnabledIds() - vaultId).joinToString(ID_SEPARATOR))
    }

    // Settings --------------------------------------------------------------------------------

    var biometricsEnabled: Boolean
        get() = getBoolean(KEY_BIOMETRICS, false)
        set(value) = put(KEY_BIOMETRICS, value)

    var autoLockSeconds: Int
        get() = getInt(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK_SECONDS)
        set(value) = put(KEY_AUTO_LOCK, value)

    var blockScreenshots: Boolean
        get() = getBoolean(KEY_BLOCK_SCREENSHOTS, false)
        set(value) = put(KEY_BLOCK_SCREENSHOTS, value)

    var failedAttempts: Int
        get() = getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = put(KEY_FAILED_ATTEMPTS, value)

    var lockoutLevel: Int
        get() = getInt(KEY_LOCKOUT_LEVEL, 0)
        set(value) = put(KEY_LOCKOUT_LEVEL, value)

    var lockoutUntil: Long
        get() = getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) = put(KEY_LOCKOUT_UNTIL, value)

    /** Removes everything, including the app PIN. */
    fun clearAll() {
        memory.clear()
        prefs?.edit()?.clear()?.apply()
    }

    // Internals -------------------------------------------------------------------------------

    private fun vaultPrefix(vaultId: String) = "vault_$vaultId"

    private fun savePin(prefix: String, hash: ByteArray, salt: ByteArray, iterations: Int) {
        put("${prefix}_hash", encode(hash))
        put("${prefix}_salt", encode(salt))
        put("${prefix}_iter", iterations)
    }

    private fun verifyPin(prefix: String, pin: String): Boolean {
        val hash = getString("${prefix}_hash") ?: return false
        val salt = getString("${prefix}_salt") ?: return false
        val iterations = getInt("${prefix}_iter", 0)
        if (iterations <= 0) return false
        return PinHasher.verify(pin, decode(salt), iterations, decode(hash))
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private fun getString(key: String): String? {
        val p = prefs
        return if (p != null) p.getString(key, null) else memory[key] as? String
    }

    private fun getInt(key: String, default: Int): Int {
        val p = prefs
        return if (p != null) p.getInt(key, default) else (memory[key] as? Int) ?: default
    }

    private fun getLong(key: String, default: Long): Long {
        val p = prefs
        return if (p != null) p.getLong(key, default) else (memory[key] as? Long) ?: default
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        val p = prefs
        return if (p != null) p.getBoolean(key, default) else (memory[key] as? Boolean) ?: default
    }

    private fun put(key: String, value: Any) {
        val p = prefs
        if (p == null) {
            memory[key] = value
            return
        }
        val editor = p.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> Unit
        }
        editor.apply()
    }

    private fun remove(key: String) {
        val p = prefs
        if (p == null) {
            memory.remove(key)
        } else {
            p.edit().remove(key).apply()
        }
    }

    private fun openEncryptedPrefs(): SharedPreferences? {
        val first = tryCreatePrefs()
        if (first != null) return first
        resetBrokenStorage()
        return tryCreatePrefs()
    }

    private fun tryCreatePrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    /** Deletes only the lock store file and its keystore key. */
    private fun resetBrokenStorage() {
        try {
            appContext.deleteSharedPreferences(FILE_NAME)
        } catch (e: Exception) {
            // Nothing more can be done; the retry below decides what happens next.
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            // The key may not exist; that is fine.
        }
    }

    private companion object {
        const val FILE_NAME = "neribo_lock_prefs"
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        const val APP_PREFIX = "app_pin"
        const val ID_SEPARATOR = ","
        const val DEFAULT_AUTO_LOCK_SECONDS = 30

        const val KEY_APP_HASH = "app_pin_hash"
        const val KEY_VAULT_IDS = "vault_enabled_ids"
        const val KEY_BIOMETRICS = "biometrics_enabled"
        const val KEY_AUTO_LOCK = "auto_lock_seconds"
        const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKOUT_LEVEL = "lockout_level"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }
}
