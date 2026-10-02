package com.westly.neribovault.data.cloud

import android.content.Context
import com.westly.neribovault.data.local.NeriboDatabase
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject

/** The result of a sign-in or sign-up attempt. */
sealed interface AuthOutcome {
    /** A session was stored; the user is signed in. */
    object SignedIn : AuthOutcome

    /** The account was created but the email must be confirmed first. */
    object ConfirmEmail : AuthOutcome

    data class Failed(val message: String) : AuthOutcome
}

/**
 * Supabase account handling through the Auth REST API: sign up, sign in, token refresh and
 * sign out. Signing out only forgets the session; local vault data is never touched.
 */
class CloudAuth(context: Context, private val database: NeriboDatabase) {
    private val appContext = context.applicationContext

    /** The encrypted configuration. Shared with [SyncEngine]. */
    val config = CloudConfigStore(appContext)

    internal val api = SupabaseApi(appContext, config)

    private val refreshMutex = Mutex()

    /** Observable, secret-free summary for the UI. */
    val state: StateFlow<CloudConfigState> get() = config.state

    /** Loads the stored configuration into [state]. */
    suspend fun load() {
        withContext(Dispatchers.IO) { config.refresh() }
    }

    /** Blocking. Call off the main thread. */
    fun isSignedIn(): Boolean = config.isSignedIn()

    /** Turns syncing of one vault on or off. */
    suspend fun setVaultEnabled(vaultId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) { config.setVaultEnabled(vaultId, enabled) }
    }

    // ---- Project -------------------------------------------------------------------------

    /** Checks the URL and key the owner typed. Returns an error message, or null when fine. */
    fun validateProject(url: String, anonKey: String): ProjectValidation {
        val cleanUrl = normalizeUrl(url)
        val urlError = when {
            url.isBlank() -> "Enter your project URL."
            cleanUrl == null -> "The URL must start with https:// and look like https://abcd.supabase.co"
            else -> null
        }
        val key = anonKey.trim()
        val keyError = when {
            key.isEmpty() -> "Enter your anon (public) key."
            key.length < MIN_KEY_LENGTH || key.any { it.isWhitespace() } ->
                "That doesn't look like a Supabase anon key. Copy it again from the API settings."
            else -> null
        }
        return ProjectValidation(cleanUrl, key, urlError, keyError)
    }

    /** Saves the project after validation. Returns an error message, or null on success. */
    suspend fun saveProject(url: String, anonKey: String): String? = withContext(Dispatchers.IO) {
        val check = validateProject(url, anonKey)
        if (check.cleanUrl == null || check.urlError != null || check.keyError != null) {
            return@withContext check.urlError ?: check.keyError
        }
        try {
            config.saveProject(check.cleanUrl, check.key)
            null
        } catch (e: GeneralSecurityException) {
            STORAGE_ERROR
        } catch (e: IOException) {
            STORAGE_ERROR
        }
    }

    /**
     * Forgets the owner's own project and starts the cursors from zero for the next one. Only
     * valid while signed out. Local vault data is untouched.
     */
    suspend fun changeProject() {
        withContext(Dispatchers.IO) {
            if (config.isSignedIn()) return@withContext
            config.clearProject()
            resetForBackendChange()
        }
    }

    /** Switches to the shared Neribo cloud. Only valid while signed out. Vault data is untouched. */
    suspend fun useSharedProject() {
        withContext(Dispatchers.IO) {
            if (config.isSignedIn() || !config.sharedAvailable) return@withContext
            config.setUseOwnProject(false)
            resetForBackendChange()
        }
    }

    /** Switches to the owner's own Supabase project. Only valid while signed out. */
    suspend fun useOwnProject() {
        withContext(Dispatchers.IO) {
            if (config.isSignedIn()) return@withContext
            config.setUseOwnProject(true)
            resetForBackendChange()
        }
    }

    /** Cursors and pending delete markers describe one cloud copy, so a new backend starts clean. */
    private fun resetForBackendChange() {
        config.clearSession()
        resetSyncProgress()
        config.cursorUserId = null
        SyncScheduler.cancel(appContext)
    }

    // ---- Account -------------------------------------------------------------------------

    suspend fun signIn(email: String, password: String): AuthOutcome = withContext(Dispatchers.IO) {
        try {
            val body = credentials(email, password)
            val response = api.authPost("auth/v1/token", mapOf("grant_type" to "password"), body, null)
            if (!response.isSuccess) return@withContext AuthOutcome.Failed(authMessage(response))
            val session = parseSession(response.body, email)
                ?: return@withContext AuthOutcome.Failed(UNEXPECTED_REPLY)
            startSession(session)
            AuthOutcome.SignedIn
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            AuthOutcome.Failed(e.message.orEmpty())
        } catch (e: GeneralSecurityException) {
            AuthOutcome.Failed(STORAGE_ERROR)
        } catch (e: IOException) {
            AuthOutcome.Failed(STORAGE_ERROR)
        }
    }

    suspend fun signUp(email: String, password: String): AuthOutcome = withContext(Dispatchers.IO) {
        try {
            val body = credentials(email, password)
            val response = api.authPost("auth/v1/signup", emptyMap(), body, null)
            if (!response.isSuccess) return@withContext AuthOutcome.Failed(authMessage(response))
            val session = parseSession(response.body, email)
            if (session != null) {
                startSession(session)
                AuthOutcome.SignedIn
            } else {
                AuthOutcome.ConfirmEmail
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            AuthOutcome.Failed(e.message.orEmpty())
        } catch (e: GeneralSecurityException) {
            AuthOutcome.Failed(STORAGE_ERROR)
        } catch (e: IOException) {
            AuthOutcome.Failed(STORAGE_ERROR)
        }
    }

    /** Clears the tokens even if the request to Supabase fails. Never deletes vault data. */
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                sendLogout()
            } finally {
                SyncScheduler.cancel(appContext)
                config.clearSession()
            }
        }
    }

    private fun sendLogout() {
        val token = config.accessToken
        if (token == null || config.projectUrl == null) return
        try {
            api.authPost("auth/v1/logout", emptyMap(), null, token)
        } catch (e: CloudException) {
            // The server could not be reached; the local sign-out still happens.
        }
    }

    // ---- Tokens --------------------------------------------------------------------------

    /**
     * Runs [block] (a blocking network call) with a valid access token. If the server answers 401
     * the token is refreshed and the call is retried once.
     */
    suspend fun <T> withToken(block: (String) -> T): T = withContext(Dispatchers.IO) {
        val first = accessToken(forceRefresh = false)
        try {
            block(first)
        } catch (e: CloudException) {
            if (e.kind != CloudErrorKind.Unauthorized) throw e
            block(accessToken(forceRefresh = true))
        }
    }

    private suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
        val token = config.accessToken
        val stillFresh = config.expiresAtMillis - System.currentTimeMillis() > REFRESH_MARGIN_MILLIS
        if (!forceRefresh && token != null && stillFresh) {
            token
        } else {
            refreshLocked()
        }
    }

    private fun refreshLocked(): String {
        val refresh = config.refreshToken
            ?: throw CloudException(CloudErrorKind.SessionExpired, SESSION_EXPIRED)
        val body = JSONObject().put("refresh_token", refresh)
        val response = api.authPost("auth/v1/token", mapOf("grant_type" to "refresh_token"), body, null)
        if (!response.isSuccess) {
            if (response.code in 400..403) {
                config.clearSession()
                SyncScheduler.cancel(appContext)
                throw CloudException(CloudErrorKind.SessionExpired, SESSION_EXPIRED)
            }
            throw CloudException(CloudErrorKind.Server, SupabaseApi.SERVER_PROBLEM, retryable = true)
        }
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw CloudException(CloudErrorKind.Server, SupabaseApi.SERVER_PROBLEM, retryable = true)
        }
        val access = json.stringOrNull("access_token")
        val newRefresh = json.stringOrNull("refresh_token")
        if (access == null || newRefresh == null) {
            throw CloudException(CloudErrorKind.Server, SupabaseApi.SERVER_PROBLEM, retryable = true)
        }
        config.updateTokens(access, newRefresh, expiryFrom(json))
        return access
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun startSession(session: CloudSession) {
        val previous = config.cursorUserId
        if (previous != null && previous != session.userId) {
            // A different account: its cloud copy is unrelated, so start from zero.
            resetSyncProgress()
        }
        config.cursorUserId = session.userId
        config.saveSession(session)
        SyncScheduler.schedulePeriodic(appContext)
    }

    /** Cursors and pending delete markers describe one cloud copy; drop them with it. */
    private fun resetSyncProgress() {
        config.resetCursors()
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_tombstones")
    }

    private fun credentials(email: String, password: String): JSONObject =
        JSONObject().put("email", email.trim()).put("password", password)

    private fun parseSession(body: String, fallbackEmail: String): CloudSession? {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        val access = json.stringOrNull("access_token") ?: return null
        val refresh = json.stringOrNull("refresh_token") ?: return null
        val user = json.optJSONObject("user") ?: return null
        val userId = user.stringOrNull("id") ?: return null
        val email = user.stringOrNull("email") ?: fallbackEmail.trim()
        return CloudSession(access, refresh, expiryFrom(json), userId, email)
    }

    private fun expiryFrom(json: JSONObject): Long {
        val seconds = json.optLong("expires_in", DEFAULT_EXPIRY_SECONDS)
        return System.currentTimeMillis() + seconds * 1000L
    }

    /** Maps a failed Auth response to a friendly message. */
    private fun authMessage(response: ApiResponse): String {
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            JSONObject()
        }
        val code = (json.stringOrNull("error_code") ?: json.stringOrNull("error")).orEmpty().lowercase()
        val text = (
            json.stringOrNull("msg") ?: json.stringOrNull("message") ?: json.stringOrNull("error_description")
            ).orEmpty().lowercase()
        return when {
            code == "invalid_credentials" || text.contains("invalid login credentials") ->
                "Wrong email or password."
            code == "email_not_confirmed" || text.contains("email not confirmed") ->
                "Your email isn't confirmed yet. Check your inbox, then sign in."
            code == "user_already_exists" || text.contains("already registered") ->
                "An account with this email already exists. Try signing in."
            code == "weak_password" || text.contains("password should be") ->
                "That password is too weak. Use at least 6 characters."
            code == "validation_failed" || text.contains("unable to validate email") ->
                "That email address doesn't look right."
            code == "signup_disabled" || text.contains("signups not allowed") ->
                "New sign-ups are turned off in this Supabase project."
            code.contains("rate_limit") || response.code == 429 ->
                "Too many attempts. Wait a minute and try again."
            response.code == 401 || response.code == 403 ->
                "Supabase rejected the project key. Check the URL and anon key under Change project."
            response.code == 404 ->
                "That project URL doesn't look like a Supabase project. Check it under Change project."
            response.code >= 500 -> SupabaseApi.SERVER_PROBLEM
            else -> "Couldn't complete that. Check your details and try again."
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps || parsed.host.isEmpty()) return null
        return parsed.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 60_000L
        const val DEFAULT_EXPIRY_SECONDS = 3600L
        const val MIN_KEY_LENGTH = 20
        const val STORAGE_ERROR = "Secure storage isn't available on this device, so the account can't be saved."
        const val UNEXPECTED_REPLY = "Supabase sent an unexpected reply. Check the project URL and key."
        const val SESSION_EXPIRED = "Your session expired. Sign in again."
    }
}

/** The outcome of checking a typed project URL and key. */
data class ProjectValidation(
    val cleanUrl: String?,
    val key: String,
    val urlError: String?,
    val keyError: String?,
)
