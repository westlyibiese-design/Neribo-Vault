package com.westly.neribovault.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What went wrong, in terms the UI and the sync engine care about. */
enum class CloudErrorKind {
    NotConfigured,
    Offline,
    Unreachable,
    BadCredentials,
    EmailNotConfirmed,
    EmailTaken,
    WeakPassword,
    RateLimited,
    Unauthorized,
    SessionExpired,
    SetupMissing,
    Server,
    SafetyGuard,
    Storage,
    Other,
}

/** A failure with a friendly message. The message never contains tokens or row contents. */
class CloudException(
    val kind: CloudErrorKind,
    message: String,
    val retryable: Boolean = false,
) : Exception(message)

/** The raw outcome of an HTTP call. Never log [body]. */
class ApiResponse(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/** True when the device has a network that claims internet access. */
fun isOnline(context: Context): Boolean {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: return true
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Reads a string, treating a missing key and an explicit JSON null the same way. */
internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/**
 * Plain HTTPS calls to Supabase's Auth and REST (PostgREST) APIs through OkHttp and org.json.
 * Every function blocks: call it from a background dispatcher.
 *
 * Requests only happen when a project URL and anon key are saved. Nothing here logs URLs,
 * keys, tokens, or response bodies.
 */
class SupabaseApi(context: Context, private val config: CloudConfigStore) {
    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** An Auth API call (`/auth/v1/...`). [bearer] is only sent when the call needs a session. */
    fun authPost(
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        bearer: String?,
    ): ApiResponse {
        val url = baseUrl().newBuilder().addPathSegments(path.trim('/')).apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = baseRequest(url, bearer)
            .post((body?.toString() ?: "{}").toRequestBody(JSON_TYPE))
            .build()
        return execute(request)
    }

    /**
     * One page of `vault_items` rows of [kind] newer than [cursor], oldest first.
     * @throws CloudException on any failure.
     */
    fun fetchRows(token: String, kind: String, cursor: Long, offset: Int, limit: Int): JSONArray {
        val url = restUrl().newBuilder()
            .addQueryParameter("kind", "eq.$kind")
            .addQueryParameter("updated_at", "gt.$cursor")
            .addQueryParameter("order", "updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val response = execute(baseRequest(url, token).get().build())
        if (!response.isSuccess) throw restError(response)
        return try {
            JSONArray(response.body)
        } catch (e: JSONException) {
            throw CloudException(CloudErrorKind.Server, SERVER_PROBLEM, retryable = true)
        }
    }

    /**
     * Inserts or updates [rows] (merge on user_id, kind, id).
     * @throws CloudException on any failure.
     */
    fun upsertRows(token: String, rows: JSONArray) {
        val url = restUrl().newBuilder()
            .addQueryParameter("on_conflict", "user_id,kind,id")
            .build()
        val request = baseRequest(url, token)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(JSON_TYPE))
            .build()
        val response = execute(request)
        if (!response.isSuccess) throw restError(response)
    }

    /**
     * Deletes the signed-in user's rows and account through the `delete_my_account` function that
     * supabase/neribo_vault_account_deletion.sql installs. The function only ever acts on the
     * caller (it reads auth.uid()), so no user id is sent.
     * @throws CloudException on any failure.
     */
    fun deleteAccount(token: String) {
        val url = baseUrl().newBuilder().addPathSegments("rest/v1/rpc/delete_my_account").build()
        val request = baseRequest(url, token)
            .post("{}".toRequestBody(JSON_TYPE))
            .build()
        val response = execute(request)
        if (response.isSuccess) return
        throw when {
            response.code == 401 -> CloudException(CloudErrorKind.Unauthorized, "Your session has expired. Sign in again.")
            response.code == 404 || errorCodeOf(response.body) == "PGRST202" -> CloudException(
                CloudErrorKind.SetupMissing,
                DELETE_SCRIPT_MISSING,
            )
            response.code == 429 || response.code >= 500 -> restError(response)
            else -> CloudException(CloudErrorKind.Other, "Supabase couldn't delete the account. Try again later.")
        }
    }

    private fun restUrl(): HttpUrl =
        baseUrl().newBuilder().addPathSegments("rest/v1/vault_items").build()

    private fun baseUrl(): HttpUrl {
        val raw = config.projectUrl
            ?: throw CloudException(CloudErrorKind.NotConfigured, "Add your Supabase project first.")
        return raw.toHttpUrlOrNull()
            ?: throw CloudException(CloudErrorKind.NotConfigured, "The saved project URL is not valid.")
    }

    private fun baseRequest(url: HttpUrl, bearer: String?): Request.Builder {
        val key = config.anonKey
            ?: throw CloudException(CloudErrorKind.NotConfigured, "Add your Supabase project first.")
        val builder = Request.Builder().url(url)
            .header("apikey", key)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        return builder
    }

    private fun execute(request: Request): ApiResponse = try {
        client.newCall(request).execute().use { response ->
            ApiResponse(response.code, response.body?.string().orEmpty())
        }
    } catch (e: IOException) {
        throw if (isOnline(appContext)) {
            CloudException(
                CloudErrorKind.Unreachable,
                "Couldn't reach Supabase. Check the project URL and your connection, then try again.",
                retryable = true,
            )
        } else {
            CloudException(CloudErrorKind.Offline, OFFLINE_MESSAGE, retryable = true)
        }
    }

    /** Turns a failed REST response into a friendly [CloudException]. */
    private fun restError(response: ApiResponse): CloudException {
        val code = response.code
        val errorCode = errorCodeOf(response.body)
        return when {
            code == 401 -> CloudException(CloudErrorKind.Unauthorized, "Your session has expired. Sign in again.")
            // The only foreign key on vault_items points at the account, so this means the account
            // was deleted (for example from another phone). Treat it like an ended session.
            errorCode == "23503" -> CloudException(CloudErrorKind.Unauthorized, "Your session has expired. Sign in again.")
            errorCode == "PGRST205" || errorCode == "42P01" || code == 404 -> CloudException(
                CloudErrorKind.SetupMissing,
                "The cloud table isn't set up yet. Run the SQL script in your Supabase project first.",
            )
            code == 403 -> CloudException(
                CloudErrorKind.Other,
                "Supabase refused the request. Check that the SQL script ran and you are signed in.",
            )
            code == 429 -> CloudException(
                CloudErrorKind.RateLimited,
                "Supabase is asking us to slow down. Try again in a minute.",
                retryable = true,
            )
            code >= 500 -> CloudException(CloudErrorKind.Server, SERVER_PROBLEM, retryable = true)
            else -> CloudException(CloudErrorKind.Other, "Supabase couldn't accept the sync request. Try again later.")
        }
    }

    private fun errorCodeOf(body: String): String? = try {
        JSONObject(body).stringOrNull("code")
    } catch (e: JSONException) {
        null
    }

    companion object {
        const val OFFLINE_MESSAGE =
            "You're offline. Your changes are safe on this phone and will sync when you're back online."
        const val SERVER_PROBLEM = "Supabase had a problem. Try again in a little while."
        const val DELETE_SCRIPT_MISSING =
            "Your cloud project is missing the account deletion script. Run it in the Supabase SQL editor, then try again."
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
