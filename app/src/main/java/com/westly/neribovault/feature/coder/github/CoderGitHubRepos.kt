package com.westly.neribovault.feature.coder.github

import com.westly.neribovault.feature.coder.ai.CoderHttp
import com.westly.neribovault.feature.coder.ai.awaitResponse
import com.westly.neribovault.feature.coder.ai.describeFailure
import com.westly.neribovault.feature.coder.files.CoderLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One repository in the person's list. [defaultBranch] is empty and [sizeKb] 0 when GitHub did not say. */
data class GitHubRepoSummary(
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    val sizeKb: Long,
) {
    /** `owner/name`. */
    val fullName: String get() = "$owner/$name"
}

/**
 * What an import needs to know about one repository. [defaultBranch] is empty when GitHub did not say. [canPush] is
 * true only when GitHub reported that the token can push (a request without a token never says so).
 */
data class GitHubRepoDetails(
    val owner: String,
    val name: String,
    val defaultBranch: String,
    val isPrivate: Boolean,
    val canPush: Boolean,
)

/** How a capped copy ended. */
sealed interface CoderLimitedCopyResult {
    /** Everything was copied: [bytes] bytes. */
    data class Done(val bytes: Long) : CoderLimitedCopyResult

    /** The input has more bytes than the limit; the copy stopped at once. */
    object TooBig : CoderLimitedCopyResult

    /** The caller asked to stop. */
    object Stopped : CoderLimitedCopyResult
}

/** How downloading a repository ended. [Failed.message] is plain English and never holds the token or an address. */
sealed interface CoderZipballResult {
    data class Downloaded(val bytes: Long) : CoderZipballResult
    object TooBig : CoderZipballResult
    data class Failed(val message: String) : CoderZipballResult
}

/**
 * The GitHub calls of "Import from GitHub": the person's repository list, the facts about one repository and the
 * download of its code as a ZIP (GitHub's "zipball"). Requests are built by pure functions so the self-test can check
 * them without a network. Everything goes to https://api.github.com (GitHub then points the ZIP to its own download
 * host, which only an https address is allowed to be). The token is only ever in the `Authorization` header, and a call
 * without a token sends no such header. No message holds the token, a header or an address.
 */
object CoderGitHubRepos {
    /** The biggest repository download that is accepted: 50 MB. */
    const val MAX_ZIPBALL_BYTES = 50L * 1024 * 1024

    /** The most repositories that are listed. */
    const val MAX_LISTED = 100

    /** What GitHub accepts instead of a branch name: the repository's default branch. */
    const val DEFAULT_REF = "HEAD"

    /** The name of a project made from a repository that has no usable name. */
    const val FALLBACK_PROJECT_NAME = "GitHub project"

    /** The `templateId` of every project made from a GitHub repository. */
    const val TEMPLATE_ID = "github"

    const val MSG_NOT_FOUND = "Couldn't find that repository, or it is private. Connect GitHub if it is yours."
    const val MSG_RATE_LIMITED = "GitHub says too many requests. Try again later."
    const val MSG_TOO_BIG = "This repository is bigger than 50 MB, so it can't be imported."
    const val MSG_BAD_REFERENCE = "That isn't a repository name. Type it like owner/name, or paste its GitHub address."

    private const val HOST = "api.github.com"
    private const val MAX_BODY_BYTES = 5 * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val MAX_REF_LENGTH = 255
    private const val DOWNLOAD_CALL_TIMEOUT_MINUTES = 10L
    private const val MSG_UNREADABLE = "GitHub sent an answer this app couldn't read."
    private const val MSG_NOT_ALLOWED = "GitHub didn't allow this. Connect GitHub and try again."
    private const val MSG_INSECURE = "GitHub sent the download to an address that isn't secure, so it was stopped."

    /** Only for the download: the shared client, with a longer total time (a big repository on a slow connection) and no switching between https and http. */
    private val downloadClient: OkHttpClient by lazy {
        CoderHttp.client.newBuilder()
            .followSslRedirects(false)
            .callTimeout(DOWNLOAD_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    // ---- Requests (pure) -------------------------------------------------------------------

    private fun api(): HttpUrl.Builder = HttpUrl.Builder().scheme("https").host(HOST)

    /** A GET with GitHub's headers. [token] null means no `Authorization` header at all. */
    private fun buildGet(token: String?, url: HttpUrl): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NeriboVault-Coder")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder.get().build()
    }

    /** `GET /user/repos?per_page=100&sort=updated`: the repositories the token can see, most recently changed first. */
    fun userRepos(token: String): Request {
        val url = api().addPathSegments("user/repos")
            .addQueryParameter("per_page", MAX_LISTED.toString())
            .addQueryParameter("sort", "updated")
            .build()
        return buildGet(token, url)
    }

    /** `GET /repos/{owner}/{name}`, with the token when there is one. */
    fun repoDetails(token: String?, owner: String, name: String): Request =
        buildGet(token, api().addPathSegment("repos").addPathSegment(owner).addPathSegment(name).build())

    /** `GET /repos/{owner}/{name}/zipball/{ref}`. A "/" inside [ref] stays a "/". GitHub answers with a redirect to the real download. */
    fun zipball(token: String?, owner: String, name: String, ref: String): Request {
        val url = api().addPathSegment("repos").addPathSegment(owner).addPathSegment(name)
            .addPathSegment("zipball").addPathSegments(ref).build()
        return buildGet(token, url)
    }

    // ---- Reading answers (pure) ------------------------------------------------------------

    /** A text value, or "" when the key is missing or null (org.json would turn null into the text "null"). */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    /**
     * The repositories in the list answer [json], at most [MAX_LISTED]. An entry without a valid `full_name` is left out; a
     * missing `private`, `default_branch` or `size` becomes false, "" and 0. Null when [json] is not a JSON array.
     */
    fun parseRepoList(json: String): List<GitHubRepoSummary>? {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return null
        }
        val out = ArrayList<GitHubRepoSummary>()
        for (index in 0 until array.length()) {
            if (out.size >= MAX_LISTED) break
            val item = array.optJSONObject(index) ?: continue
            val parsed = parseRepoRef(item.text("full_name")) ?: continue
            out.add(
                GitHubRepoSummary(
                    owner = parsed.first,
                    name = parsed.second,
                    isPrivate = item.optBoolean("private", false),
                    defaultBranch = item.text("default_branch"),
                    sizeKb = item.optLong("size", 0L).coerceAtLeast(0L),
                ),
            )
        }
        return out
    }

    /** The repositories of [all] whose `owner/name` contains [query] (ignoring case); an empty query keeps all. At most [MAX_LISTED]. */
    fun filterRepos(all: List<GitHubRepoSummary>, query: String): List<GitHubRepoSummary> {
        val wanted = query.trim()
        val matching = if (wanted.isEmpty()) all else all.filter { it.fullName.contains(wanted, ignoreCase = true) }
        return matching.take(MAX_LISTED)
    }

    /** The facts in the repository answer [json]; [owner] and [name] stand in when GitHub leaves them out. Null when it is not a JSON object. */
    fun parseRepoDetails(json: String, owner: String, name: String): GitHubRepoDetails? {
        val obj = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return null
        }
        val ownerLogin = obj.optJSONObject("owner")?.text("login").orEmpty().ifEmpty { owner }
        val repoName = obj.text("name").ifEmpty { name }
        val canPush = obj.optJSONObject("permissions")?.optBoolean("push", false) ?: false
        return GitHubRepoDetails(ownerLogin, repoName, obj.text("default_branch"), obj.optBoolean("private", false), canPush)
    }

    /**
     * The plain-English reason for a failed answer with status [code]. [rateRemaining] is the `X-RateLimit-Remaining`
     * header text. [hadToken] says whether the request carried the person's token.
     */
    fun failureFor(code: Int, rateRemaining: String?, hadToken: Boolean): CoderGitHubResult.Err = when {
        code == 404 -> CoderGitHubResult.Err(CoderGitHubErrorKind.NotFound, MSG_NOT_FOUND)
        code == 429 || (code == 403 && rateRemaining?.trim() == "0") ->
            CoderGitHubResult.Err(CoderGitHubErrorKind.RateLimited, MSG_RATE_LIMITED)
        code == 403 && !hadToken -> CoderGitHubResult.Err(CoderGitHubErrorKind.NoAccess, MSG_NOT_ALLOWED)
        else -> CoderGitHubErrors.map(code, rateRemaining, null)
    }

    /** The project name for a repository called [repoName]: no control characters, at most 60 characters, "GitHub project" when nothing is left. */
    fun projectNameFor(repoName: String): String {
        val clean = repoName.filter { it.code >= 32 && it.code != 127 }.trim().take(CoderLimits.MAX_PROJECT_NAME_LENGTH).trim()
        return clean.ifEmpty { FALLBACK_PROJECT_NAME }
    }

    /** True when GitHub said, before sending anything, that the download is bigger than the limit. A length of -1 (not announced) is not too big. */
    fun exceedsLimit(declaredBytes: Long): Boolean = declaredBytes > MAX_ZIPBALL_BYTES

    /** A branch name that cannot change the meaning of the address it is put into. */
    fun isSafeRef(ref: String): Boolean {
        if (ref.isEmpty() || ref.length > MAX_REF_LENGTH) return false
        if (ref.startsWith("/") || ref.endsWith("/") || ref.contains("//") || ref.contains("..")) return false
        if (ref.split('/').any { it == "." }) return false
        return ref.none { it.code < 33 || it.code == 127 || it in "~^:?*[\\" }
    }

    /**
     * Copies [input] to [output] and stops as soon as more than [maxBytes] have been read: the rest is never read.
     * [shouldContinue] is asked before every chunk; [onProgress] gets the bytes copied so far.
     */
    fun copyLimited(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {},
    ): CoderLimitedCopyResult {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            if (!shouldContinue()) return CoderLimitedCopyResult.Stopped
            val read = input.read(buffer)
            if (read < 0) return CoderLimitedCopyResult.Done(total)
            total += read
            if (total > maxBytes) return CoderLimitedCopyResult.TooBig
            output.write(buffer, 0, read)
            onProgress(total)
        }
    }

    // ---- Network calls ---------------------------------------------------------------------

    /** The repositories [token] can see, newest change first, at most 100. */
    suspend fun listMyRepos(token: String): CoderGitHubResult<List<GitHubRepoSummary>> = guarded {
        val raw = rawCall(userRepos(token))
        if (raw.code in 200..299) {
            val list = raw.body?.let { parseRepoList(it) }
            if (list == null) unreadable() else CoderGitHubResult.Ok(list)
        } else {
            failureFor(raw.code, raw.rateRemaining, hadToken = true)
        }
    }

    /** The facts about [owner]/[name]. With [token] null the request is made without signing in, which works for public repositories. */
    suspend fun getRepoDetails(token: String?, owner: String, name: String): CoderGitHubResult<GitHubRepoDetails> {
        if (parseRepoRef("$owner/$name") != Pair(owner, name)) {
            return CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, MSG_BAD_REFERENCE)
        }
        return guarded {
            val raw = rawCall(repoDetails(token, owner, name))
            if (raw.code in 200..299) {
                val details = raw.body?.let { parseRepoDetails(it, owner, name) }
                if (details == null) unreadable() else CoderGitHubResult.Ok(details)
            } else {
                failureFor(raw.code, raw.rateRemaining, hadToken = token != null)
            }
        }
    }

    /**
     * Downloads the code of [owner]/[name] at [ref] into the file [target] and reports the bytes so far through [onProgress]
     * (the second number is the full size, or -1 when GitHub did not announce it). The download stops at 50 MB, and any
     * answer that is not an https download is refused. A failed or stopped download removes [target]; cancelling the
     * coroutine stops the connection at once.
     */
    suspend fun downloadZipball(
        token: String?,
        owner: String,
        name: String,
        ref: String,
        target: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ): CoderZipballResult {
        if (parseRepoRef("$owner/$name") != Pair(owner, name)) return CoderZipballResult.Failed(MSG_BAD_REFERENCE)
        val request = zipball(token, owner, name, if (isSafeRef(ref)) ref else DEFAULT_REF)
        return try {
            coroutineScope {
                val call = downloadClient.newCall(request)
                // A read that is waiting for data does not notice cancellation, so this stops the connection itself.
                val guard = launch(Dispatchers.Default) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) { fetch(call, token != null, target, onProgress) }
                } finally {
                    guard.cancel()
                }
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            // A stopped connection throws an ordinary error; when the stop came from cancelling, that is what must propagate.
            currentCoroutineContext().ensureActive()
            CoderZipballResult.Failed(describeFailure(e))
        }
    }

    private suspend fun fetch(call: Call, hadToken: Boolean, target: File, onProgress: (Long, Long) -> Unit): CoderZipballResult {
        val job = currentCoroutineContext()[Job]
        return call.awaitResponse().use { response -> readZipball(response, hadToken, target, onProgress, job) }
    }

    /** Checks the answer, then streams its body into [target] with the 50 MB limit. Plain (not suspending): [job] tells when to stop. */
    private fun readZipball(
        response: Response,
        hadToken: Boolean,
        target: File,
        onProgress: (Long, Long) -> Unit,
        job: Job?,
    ): CoderZipballResult {
        if (!response.isSuccessful) {
            return CoderZipballResult.Failed(failureFor(response.code, response.header("X-RateLimit-Remaining"), hadToken).message)
        }
        if (!response.request.url.isHttps) return CoderZipballResult.Failed(MSG_INSECURE)
        val body = response.body ?: return CoderZipballResult.Failed(MSG_UNREADABLE)
        val declared = body.contentLength()
        if (exceedsLimit(declared)) return CoderZipballResult.TooBig
        onProgress(0L, declared)
        val result = FileOutputStream(target).use { out ->
            body.byteStream().use { input ->
                copyLimited(input, out, MAX_ZIPBALL_BYTES, shouldContinue = { job?.isActive != false }) { done ->
                    onProgress(done, declared)
                }
            }
        }
        return when (result) {
            is CoderLimitedCopyResult.Done -> CoderZipballResult.Downloaded(result.bytes)
            CoderLimitedCopyResult.TooBig -> {
                target.delete()
                CoderZipballResult.TooBig
            }
            CoderLimitedCopyResult.Stopped -> {
                target.delete()
                job?.ensureActive()
                CoderZipballResult.Failed(MSG_UNREADABLE)
            }
        }
    }

    private class Raw(val code: Int, val body: String?, val rateRemaining: String?)

    /** Runs [block] on a background thread and turns any thrown error (except cancellation) into a result that holds only a plain message. */
    private suspend fun <T> guarded(block: suspend () -> CoderGitHubResult<T>): CoderGitHubResult<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CoderGitHubErrors.fromException(e)
            }
        }

    private suspend fun rawCall(request: Request): Raw =
        CoderHttp.client.newCall(request).awaitResponse().use { response ->
            Raw(response.code, readBody(response), response.header("X-RateLimit-Remaining"))
        }

    private fun unreadable(): CoderGitHubResult.Err = CoderGitHubResult.Err(CoderGitHubErrorKind.Other, MSG_UNREADABLE)

    /** Reads the answer, at most 5 MB of it. Null when it is bigger. */
    private fun readBody(response: Response): String? {
        val stream = response.body?.byteStream() ?: return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BODY_BYTES) return null
            out.write(buffer, 0, count)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
