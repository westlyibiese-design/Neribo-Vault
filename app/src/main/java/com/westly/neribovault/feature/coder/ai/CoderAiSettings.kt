package com.westly.neribovault.feature.coder.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/** The one place this DataStore name is declared. It is not the app's settings file and it is not backed up. */
private val Context.coderAiDataStore: DataStore<Preferences> by preferencesDataStore(name = "neribo_coder_ai")

private const val MAX_KEY_LENGTH = 512
private const val MAX_MODEL_LENGTH = 120

/**
 * Makes a pasted key ready to store: every whitespace character is removed (a pasted key often has a
 * trailing newline). Returns null for an empty key, a key over 512 characters, or a key with any
 * character outside printable ASCII (codes 33 to 126).
 */
internal fun cleanKey(raw: String): String? {
    val stripped = raw.filterNot { it.isWhitespace() }
    if (stripped.isEmpty() || stripped.length > MAX_KEY_LENGTH) return null
    if (stripped.any { it.code < 33 || it.code > 126 }) return null
    return stripped
}

/**
 * A model name that is safe to keep and to use later inside a web address: letters, digits and the
 * characters . - _ : only, up to 120 of them. Returns null for anything else.
 */
internal fun cleanModelName(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > MAX_MODEL_LENGTH) return null
    val allowed = name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' || it == ':' }
    return if (allowed) name else null
}

/**
 * The person's own AI accounts: one key per provider, the chosen model, and which provider is active.
 * Keys are encrypted with [CoderKeyCrypto]; the stored text is cipher, IV and the last 4 characters
 * (for display). A key is never written anywhere else and never leaves this class except through
 * [activeConfig] and the internal [readKey]. The file is not part of the Vault backup, and the app
 * does not allow Android backups.
 */
class CoderAiSettings(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.coderAiDataStore
    private val activeKey = stringPreferencesKey("active_provider")

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private fun cipherKey(provider: CoderAiProvider) = stringPreferencesKey(provider.id + "_cipher")
    private fun ivKey(provider: CoderAiProvider) = stringPreferencesKey(provider.id + "_iv")
    private fun last4Key(provider: CoderAiProvider) = stringPreferencesKey(provider.id + "_last4")
    private fun modelKey(provider: CoderAiProvider) = stringPreferencesKey(provider.id + "_model")

    private fun hasStoredKey(prefs: Preferences, provider: CoderAiProvider): Boolean =
        !prefs[cipherKey(provider)].isNullOrEmpty() && !prefs[ivKey(provider)].isNullOrEmpty()

    /** The provider chosen for AI help, or null when none is. */
    val activeProvider: Flow<CoderAiProvider?> = data
        .map { prefs -> CoderAiProvider.fromId(prefs[activeKey]) }
        .distinctUntilChanged()

    /** Whether a key is saved for [provider]. */
    fun hasKey(provider: CoderAiProvider): Flow<Boolean> = data
        .map { prefs -> hasStoredKey(prefs, provider) }
        .distinctUntilChanged()

    /** The last 4 characters of the saved key, for display, or null when there is no key. */
    fun keyLast4(provider: CoderAiProvider): Flow<String?> = data
        .map { prefs -> prefs[last4Key(provider)] }
        .distinctUntilChanged()

    /** The model chosen for [provider], or null when none is chosen. */
    fun model(provider: CoderAiProvider): Flow<String?> = data
        .map { prefs -> prefs[modelKey(provider)] }
        .distinctUntilChanged()

    /**
     * Cleans, encrypts and saves [key]. Returns false when the key is not acceptable (see [cleanKey]),
     * when the phone cannot encrypt it, or when the write fails. The first key saved also becomes the
     * active provider. The plain key is not kept in any field.
     */
    suspend fun saveKey(provider: CoderAiProvider, key: String): Boolean {
        val cleaned = cleanKey(key) ?: return false
        val bytes = cleaned.toByteArray(Charsets.UTF_8)
        val sealed = try {
            withContext(Dispatchers.IO) { CoderKeyCrypto.encrypt(bytes) }
        } finally {
            bytes.fill(0)
        }
        if (sealed == null) return false
        val last4 = cleaned.takeLast(4)
        return try {
            store.edit { prefs ->
                prefs[cipherKey(provider)] = sealed.ciphertext
                prefs[ivKey(provider)] = sealed.iv
                prefs[last4Key(provider)] = last4
                val current = CoderAiProvider.fromId(prefs[activeKey])
                if (current == null || !hasStoredKey(prefs, current)) {
                    prefs[activeKey] = provider.id
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Deletes the key, its last 4 characters and the model of [provider]. If it was active, nothing is active afterwards. */
    suspend fun removeKey(provider: CoderAiProvider) {
        try {
            store.edit { prefs ->
                prefs.remove(cipherKey(provider))
                prefs.remove(ivKey(provider))
                prefs.remove(last4Key(provider))
                prefs.remove(modelKey(provider))
                if (prefs[activeKey] == provider.id) {
                    prefs.remove(activeKey)
                }
            }
        } catch (e: IOException) {
            // The stored values stay as they were; the screen shows what is really saved.
        }
    }

    /** Saves the chosen model for [provider]. A name that fails [cleanModelName] is ignored. */
    suspend fun setModel(provider: CoderAiProvider, model: String) {
        val cleaned = cleanModelName(model) ?: return
        try {
            store.edit { prefs -> prefs[modelKey(provider)] = cleaned }
        } catch (e: IOException) {
            // The earlier model stays.
        }
    }

    /** Makes [provider] the active one. Ignored when it has no key. */
    suspend fun setActiveProvider(provider: CoderAiProvider) {
        try {
            store.edit { prefs ->
                if (hasStoredKey(prefs, provider)) {
                    prefs[activeKey] = provider.id
                }
            }
        } catch (e: IOException) {
            // The earlier choice stays.
        }
    }

    /**
     * Everything a request needs, or null unless the active provider has a key that decrypts and a
     * model. The key is in memory only for as long as the caller holds the result.
     */
    suspend fun activeConfig(): CoderAiConfig? {
        val prefs = data.first()
        val provider = CoderAiProvider.fromId(prefs[activeKey]) ?: return null
        val model = prefs[modelKey(provider)]?.trim().orEmpty()
        if (model.isEmpty()) return null
        val key = decryptKey(prefs, provider) ?: return null
        return CoderAiConfig(provider, key, model)
    }

    /** The decrypted key of [provider], or null. Used only by the AI settings screen to test the key it just saved. */
    internal suspend fun readKey(provider: CoderAiProvider): String? = decryptKey(data.first(), provider)

    private suspend fun decryptKey(prefs: Preferences, provider: CoderAiProvider): String? {
        val cipher = prefs[cipherKey(provider)] ?: return null
        val iv = prefs[ivKey(provider)] ?: return null
        val bytes = withContext(Dispatchers.IO) { CoderKeyCrypto.decrypt(cipher, iv) } ?: return null
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
