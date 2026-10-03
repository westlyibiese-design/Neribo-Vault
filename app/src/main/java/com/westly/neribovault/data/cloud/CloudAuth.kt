package com.westly.neribovault.data.cloud

import android.app.Activity
import android.content.Context
import com.westly.neribovault.BuildConfig
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

/** The result of a sign-in attempt. */
sealed interface AuthOutcome {
    /** A session was stored; the user is signed in. */
    object SignedIn : AuthOutcome

    /** The person closed the Google sheet without choosing. Not an error. */
    object Cancelled : AuthOutcome

    data class Failed(val message: String) : AuthOutcome
}

/** The result of deleting the cloud data and account. Anything but [Deleted] leaves the phone signed in as before. */
sealed interface DeleteOutcome {
    /** The server confirmed. The account is gone and this phone is signed out. */
    object Deleted : DeleteOutcome

    object Offline : DeleteOutcome

    /** The project does not have the delete_my_account function yet. */
    object NeedsScript : DeleteOutcome

    /** The sign-in had already ended, so nothing was deleted and the phone is signed out. */
    object SessionEnded : DeleteOutcome

    data class Failed(val message: String) : DeleteOutcome
}

/**
 * Supabase account handling through the Auth REST API: Google sign-in, token refresh and
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

    /**
     * True when Google sign-in can be offered: the shared Neribo project is active, this build
     * carries it, and the Web client ID was baked in. Blocking (reads preferences): call it off
     * the main thread.
     */
    fun googleSignInAvailable(): Boolean =
        !config.usingOwnProject() && config.sharedAvailable && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /**
     * Signs in with Google through Credential Manager, then trades the Google ID token for a
     * Supabase session. [activity] is only used during this call and is never kept.
     */
    suspend fun signInWithGoogle(activity: Activity): AuthOutcome {
        val available = withContext(Dispatchers.IO) { googleSignInAvailable() }
        if (!available) return AuthOutcome.Failed(GOOGLE_UNAVAILABLE)
        // Credential Manager shows UI, so it runs on the calling (main) thread, not on IO.
        val token = when (val result = requestGoogleIdToken(activity, BuildConfig.GOOGLE_WEB_CLIENT_ID.trim())) {
            is GoogleTokenResult.Token -> result
            GoogleTokenResult.Cancelled -> return AuthOutcome.Cancelled
            is GoogleTokenResult.Failed -> return AuthOutcome.Failed(result.message)
        }
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("provider", "google")
                    .put("id_token", token.idToken)
                    .put("nonce", token.rawNonce)
                val response = api.authPost("auth/v1/token", mapOf("grant_type" to "id_token"), body, null)
                if (!response.isSuccess) return@withContext AuthOutcome.Failed(googleMessage(response))
                val session = parseSession(response.body, token.email.orEmpty())
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

    /**
     * Deletes every synced row and the account itself on the server, then forgets the session and
     * the sync progress on this phone. Vault data on the phone is never touched. Local state is
     * only cleared after the server confirmed, so a failed attempt can simply be retried.
     * Callers should hold the sync engine paused while this runs.
     */
    suspend fun deleteAccount(): DeleteOutcome = withContext(Dispatchers.IO) {
        if (!config.isSignedIn()) return@withContext DeleteOutcome.Failed("You're not signed in.")
        if (!isOnline(appContext)) return@withContext DeleteOutcome.Offline
        try {
            withToken { token -> api.deleteAccount(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            return@withContext when (e.kind) {
                CloudErrorKind.SetupMissing -> DeleteOutcome.NeedsScript
                CloudErrorKind.Offline -> DeleteOutcome.Offline
                CloudErrorKind.SessionExpired -> {
                    // The refresh failed and the session was already cleared.
                    SyncScheduler.cancel(appContext)
                    DeleteOutcome.SessionEnded
                }
                CloudErrorKind.Unauthorized -> DeleteOutcome.Failed(
                    "Supabase didn't accept your session. Sign out, sign in again, then try again.",
                )
                else -> DeleteOutcome.Failed(e.message.orEmpty())
            }
        }
        SyncScheduler.cancel(appContext)
        config.clearSession()
        resetSyncProgress()
        config.cursorUserId = null
        DeleteOutcome.Deleted
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

    /** Maps a failed Google token exchange to a friendly message. Never includes the response. */
    private fun googleMessage(response: ApiResponse): String {
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            JSONObject()
        }
        val code = (json.stringOrNull("error_code") ?: json.stringOrNull("error")).orEmpty().lowercase()
        return when {
            code.contains("rate_limit") || response.code == 429 ->
                "Too many attempts. Wait a minute and try again."
            code == "signup_disabled" -> "New sign-ups are turned off in this Supabase project."
            response.code >= 500 -> SupabaseApi.SERVER_PROBLEM
            else -> "Supabase didn't accept the Google sign-in. Check the Google settings in the Supabase project."
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
        const val GOOGLE_UNAVAILABLE = "Google sign-in works with the Neribo cloud only."
    }
}

/** The outcome of checking a typed project URL and key. */
data class ProjectValidation(
    val cleanUrl: String?,
    val key: String,
    val urlError: String?,
    val keyError: String?,
)
