package com.westly.neribovault.feature.coder.github

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
private val Context.coderGitHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "neribo_coder_github")

private const val MAX_TOKEN_LENGTH = 512
private const val MAX_LOGIN_LENGTH = 100

/**
 * Makes a pasted GitHub token ready to store: every whitespace character is removed (a pasted token
 * often has a trailing newline). Returns null for an empty token, a token over 512 characters, or a
 * token with any character outside printable ASCII (codes 33 to 126).
 */
fun cleanGitHubToken(raw: String): String? {
    val stripped = raw.filterNot { it.isWhitespace() }
    if (stripped.isEmpty() || stripped.length > MAX_TOKEN_LENGTH) return null
    if (stripped.any { it.code < 33 || it.code > 126 }) return null
    return stripped
}

/**
 * The person's own GitHub connection: one token (encrypted with [CoderGitHubKeyCrypto]), the last 4
 * characters of it (for display) and the account name GitHub reported for it. The token is never
 * written anywhere else and leaves this class only through [token]. The file is not part of the Vault
 * backup, and the app does not allow Android backups.
 */
class CoderGitHubSettings(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.coderGitHubDataStore
    private val cipherKey = stringPreferencesKey("token_cipher")
    private val ivKey = stringPreferencesKey("token_iv")
    private val last4Key = stringPreferencesKey("token_last4")
    private val loginKey = stringPreferencesKey("login")

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** Whether a token is saved. */
    val connected: Flow<Boolean> = data
        .map { prefs -> !prefs[cipherKey].isNullOrEmpty() && !prefs[ivKey].isNullOrEmpty() }
        .distinctUntilChanged()

    /** The GitHub account name the saved token belongs to, or null when nothing is saved. */
    val login: Flow<String?> = data
        .map { prefs -> prefs[loginKey] }
        .distinctUntilChanged()

    /** The last 4 characters of the saved token, for display, or null when nothing is saved. */
    val tokenLast4: Flow<String?> = data
        .map { prefs -> prefs[last4Key] }
        .distinctUntilChanged()

    /**
     * Cleans, encrypts and saves [token] together with the account name [login]. Returns false when the
     * token is not acceptable (see [cleanGitHubToken]), when [login] is blank, when the phone cannot
     * encrypt the token, or when the write fails. The plain token is not kept in any field.
     */
    suspend fun saveToken(token: String, login: String): Boolean {
        val cleaned = cleanGitHubToken(token) ?: return false
        val name = login.trim().take(MAX_LOGIN_LENGTH)
        if (name.isEmpty()) return false
        val bytes = cleaned.toByteArray(Charsets.UTF_8)
        val sealed = try {
            withContext(Dispatchers.IO) { CoderGitHubKeyCrypto.encrypt(bytes) }
        } finally {
            bytes.fill(0)
        }
        if (sealed == null) return false
        val last4 = cleaned.takeLast(4)
        return try {
            store.edit { prefs ->
                prefs[cipherKey] = sealed.ciphertext
                prefs[ivKey] = sealed.iv
                prefs[last4Key] = last4
                prefs[loginKey] = name
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Removes the token, its last 4 characters and the account name. */
    suspend fun disconnect() {
        try {
            store.edit { prefs ->
                prefs.remove(cipherKey)
                prefs.remove(ivKey)
                prefs.remove(last4Key)
                prefs.remove(loginKey)
            }
        } catch (e: IOException) {
            // The stored values stay as they were; the screen shows what is really saved.
        }
    }

    /** The decrypted token, or null when nothing is saved or it cannot be decrypted. Only the GitHub screen and its push code call this. */
    internal suspend fun token(): String? {
        val prefs = data.first()
        val cipher = prefs[cipherKey] ?: return null
        val iv = prefs[ivKey] ?: return null
        val bytes = withContext(Dispatchers.IO) { CoderGitHubKeyCrypto.decrypt(cipher, iv) } ?: return null
        return try {
            String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
