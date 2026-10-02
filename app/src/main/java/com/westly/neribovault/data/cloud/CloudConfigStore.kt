package com.westly.neribovault.data.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A signed-in Supabase session. Never log or display the tokens. */
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String,
)

/** What the UI needs to know about the cloud setup. Contains no secrets. */
data class CloudConfigState(
    val loaded: Boolean = false,
    val hasProject: Boolean = false,
    val signedIn: Boolean = false,
    val email: String = "",
    val vaultEnabled: Map<String, Boolean> = emptyMap(),
    val lastSyncAt: Long = 0L,
)

/**
 * Keeps the Supabase URL and anon key, the session tokens, the account email and id, the
 * per-vault switches and the sync cursors in EncryptedSharedPreferences (AES256_GCM).
 *
 * Every function that reads or writes preferences is blocking: call it off the main thread.
 * Nothing stored here is ever logged.
 */
class CloudConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private val _state = MutableStateFlow(CloudConfigState())

    /** Observable, secret-free summary of the configuration. */
    val state: StateFlow<CloudConfigState> = _state.asStateFlow()

    private fun prefs(): SharedPreferences =
        cachedPrefs ?: synchronized(lock) {
            cachedPrefs ?: createPrefs().also { cachedPrefs = it }
        }

    private fun createPrefs(): SharedPreferences = try {
        openPrefs()
    } catch (e: GeneralSecurityException) {
        resetPrefsFile()
        openPrefs()
    } catch (e: IOException) {
        resetPrefsFile()
        openPrefs()
    }

    private fun openPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Only the cloud settings file is dropped. Vault data is never touched. */
    private fun resetPrefsFile() {
        appContext.deleteSharedPreferences(PREFS_NAME)
    }

    /** Re-reads everything and publishes a fresh [state]. Safe to call repeatedly. */
    fun refresh() {
        _state.value = try {
            readState()
        } catch (e: GeneralSecurityException) {
            CloudConfigState(loaded = true)
        } catch (e: IOException) {
            CloudConfigState(loaded = true)
        } catch (e: IllegalStateException) {
            CloudConfigState(loaded = true)
        }
    }

    private fun readState(): CloudConfigState {
        val p = prefs()
        val signedIn = !p.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty() &&
            !p.getString(KEY_USER_ID, null).isNullOrEmpty()
        return CloudConfigState(
            loaded = true,
            hasProject = !p.getString(KEY_URL, null).isNullOrEmpty() &&
                !p.getString(KEY_ANON_KEY, null).isNullOrEmpty(),
            signedIn = signedIn,
            email = if (signedIn) p.getString(KEY_EMAIL, "").orEmpty() else "",
            vaultEnabled = SyncTables.VAULTS.associate { it.id to vaultEnabled(it.id) },
            lastSyncAt = p.getLong(KEY_LAST_SYNC_AT, 0L),
        )
    }

    // ---- Project -------------------------------------------------------------------------

    val projectUrl: String? get() = prefs().getString(KEY_URL, null)?.takeIf { it.isNotEmpty() }

    val anonKey: String? get() = prefs().getString(KEY_ANON_KEY, null)?.takeIf { it.isNotEmpty() }

    fun saveProject(url: String, anonKey: String) {
        prefs().edit().putString(KEY_URL, url).putString(KEY_ANON_KEY, anonKey).commit()
        refresh()
    }

    fun clearProject() {
        prefs().edit().remove(KEY_URL).remove(KEY_ANON_KEY).commit()
        refresh()
    }

    // ---- Session -------------------------------------------------------------------------

    fun isSignedIn(): Boolean = try {
        val p = prefs()
        !p.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty() &&
            !p.getString(KEY_USER_ID, null).isNullOrEmpty()
    } catch (e: GeneralSecurityException) {
        false
    } catch (e: IOException) {
        false
    }

    val accessToken: String? get() = prefs().getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotEmpty() }

    val refreshToken: String? get() = prefs().getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotEmpty() }

    val expiresAtMillis: Long get() = prefs().getLong(KEY_EXPIRES_AT, 0L)

    val userId: String? get() = prefs().getString(KEY_USER_ID, null)?.takeIf { it.isNotEmpty() }

    fun saveSession(session: CloudSession) {
        prefs().edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMillis)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .commit()
        refresh()
    }

    /** Stores refreshed tokens while keeping the account details. */
    fun updateTokens(accessToken: String, refreshToken: String, expiresAtMillis: Long) {
        prefs().edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .commit()
        refresh()
    }

    /** Forgets the session. Cursors and vault data are left alone. */
    fun clearSession() {
        prefs().edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .commit()
        refresh()
    }

    /** The account the stored cursors belong to, so a different account starts from zero. */
    var cursorUserId: String?
        get() = prefs().getString(KEY_CURSOR_USER_ID, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            val editor = prefs().edit()
            if (value == null) editor.remove(KEY_CURSOR_USER_ID) else editor.putString(KEY_CURSOR_USER_ID, value)
            editor.commit()
        }

    // ---- Per-vault switches --------------------------------------------------------------

    fun vaultEnabled(vaultId: String): Boolean =
        prefs().getBoolean(KEY_VAULT_PREFIX + vaultId, SyncTables.isVaultEnabledByDefault(vaultId))

    fun setVaultEnabled(vaultId: String, enabled: Boolean) {
        prefs().edit().putBoolean(KEY_VAULT_PREFIX + vaultId, enabled).commit()
        refresh()
    }

    // ---- Cursors -------------------------------------------------------------------------

    fun pullCursor(table: String): Long = prefs().getLong(KEY_PULL_PREFIX + table, 0L)

    fun pushCursor(table: String): Long = prefs().getLong(KEY_PUSH_PREFIX + table, 0L)

    /** Moves the pull cursor forward only; it never goes back. */
    fun advancePullCursor(table: String, value: Long) {
        if (value > pullCursor(table)) prefs().edit().putLong(KEY_PULL_PREFIX + table, value).commit()
    }

    /** Moves the push cursor forward only; it never goes back. */
    fun advancePushCursor(table: String, value: Long) {
        if (value > pushCursor(table)) prefs().edit().putLong(KEY_PUSH_PREFIX + table, value).commit()
    }

    fun resetCursors() {
        val editor = prefs().edit()
        SyncTables.ALL.forEach { table ->
            editor.remove(KEY_PULL_PREFIX + table.name)
            editor.remove(KEY_PUSH_PREFIX + table.name)
        }
        editor.remove(KEY_LAST_SYNC_AT)
        editor.commit()
        refresh()
    }

    // ---- Status --------------------------------------------------------------------------

    val lastSyncAt: Long get() = prefs().getLong(KEY_LAST_SYNC_AT, 0L)

    fun setLastSyncAt(millis: Long) {
        prefs().edit().putLong(KEY_LAST_SYNC_AT, millis).commit()
        refresh()
    }

    private companion object {
        const val PREFS_NAME = "neribo_cloud_prefs"
        const val MASTER_KEY_ALIAS = "neribo_cloud_master_key"
        const val KEY_URL = "project_url"
        const val KEY_ANON_KEY = "anon_key"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_USER_ID = "user_id"
        const val KEY_EMAIL = "email"
        const val KEY_CURSOR_USER_ID = "cursor_user_id"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_VAULT_PREFIX = "vault_"
        const val KEY_PULL_PREFIX = "pull_"
        const val KEY_PUSH_PREFIX = "push_"
    }
}
