package com.westly.neribovault.feature.coder.github

import android.util.Base64
import com.westly.neribovault.feature.coder.ai.CoderHttp
import com.westly.neribovault.feature.coder.ai.awaitResponse
import com.westly.neribovault.feature.coder.ai.describeFailure
import com.westly.neribovault.feature.coder.files.CoderPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/** A JSON text value, or "" when the key is missing or null (org.json would otherwise turn null into the text "null"). */
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

/**
 * A request body of the form prefix + base64(data) + suffix, written straight to the connection. A 20 MB
 * file would otherwise be copied into several very large strings; this keeps one encoded copy in memory.
 */
private class Base64JsonBody(prefix: String, data: ByteArray, suffix: String) : RequestBody() {
    private val head: ByteArray = prefix.toByteArray(Charsets.UTF_8)
    private val encoded: ByteArray = Base64.encode(data, Base64.NO_WRAP)
    private val tail: ByteArray = suffix.toByteArray(Charsets.UTF_8)

    override fun contentType(): MediaType = JSON_MEDIA_TYPE

    override fun contentLength(): Long = (head.size + encoded.size + tail.size).toLong()

    override fun writeTo(sink: BufferedSink) {
        sink.write(head)
        sink.write(encoded)
        sink.write(tail)
    }
}

/**
 * Builds every request the GitHub client sends. The functions are pure, so the self-test can check them
 * without a network. The address is always https://api.github.com. The token is only ever in the
 * `Authorization` header, never in an address or a body. Callers must pass an owner, name, branch and
 * identifier that have already been checked; the client does that.
 */
object CoderGitHubRequests {
    private const val HOST = "api.github.com"

    private fun api(): HttpUrl.Builder = HttpUrl.Builder().scheme("https").host(HOST)

    private fun repoUrl(owner: String, name: String): HttpUrl.Builder =
        api().addPathSegment("repos").addPathSegment(owner).addPathSegment(name)

    private fun base(token: String, url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "NeriboVault-Coder")

    private fun json(body: JSONObject): RequestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

    /** `GET /user`. */
    fun user(token: String): Request = base(token, api().addPathSegment("user").build()).get().build()

    /** `GET /repos/{owner}/{name}`. */
    fun repo(token: String, owner: String, name: String): Request =
        base(token, repoUrl(owner, name).build()).get().build()

    /** `POST /user/repos`: a new empty repository (no README), so the first push can fill it. */
    fun createRepo(token: String, name: String, isPrivate: Boolean): Request {
        val body = JSONObject().put("name", name).put("private", isPrivate).put("auto_init", false)
        return base(token, api().addPathSegments("user/repos").build()).post(json(body)).build()
    }

    /** `GET .../git/ref/heads/{branch}`. A "/" inside the branch name stays a "/". */
    fun branchHead(token: String, owner: String, name: String, branch: String): Request {
        val url = repoUrl(owner, name).addPathSegments("git/ref/heads").addPathSegments(branch).build()
        return base(token, url).get().build()
    }

    /** `GET .../commits?per_page=1`, used only to find out whether the repository has any commit. */
    fun latestCommit(token: String, owner: String, name: String): Request {
        val url = repoUrl(owner, name).addPathSegment("commits").addQueryParameter("per_page", "1").build()
        return base(token, url).get().build()
    }

    /** `GET .../git/commits/{sha}`. */
    fun commit(token: String, owner: String, name: String, commitSha: String): Request {
        val url = repoUrl(owner, name).addPathSegments("git/commits").addPathSegment(commitSha).build()
        return base(token, url).get().build()
    }

    /** `GET .../git/trees/{sha}?recursive=1`. */
    fun tree(token: String, owner: String, name: String, treeSha: String): Request {
        val url = repoUrl(owner, name).addPathSegments("git/trees").addPathSegment(treeSha)
            .addQueryParameter("recursive", "1").build()
        return base(token, url).get().build()
    }

    /** `POST .../git/blobs` with the file as base64. */
    fun createBlob(token: String, owner: String, name: String, bytes: ByteArray): Request {
        val url = repoUrl(owner, name).addPathSegments("git/blobs").build()
        val body = Base64JsonBody("{\"content\":\"", bytes, "\",\"encoding\":\"base64\"}")
        return base(token, url).post(body).build()
    }

    /** `POST .../git/trees`. File names go into the JSON exactly as they are. Every mode other than "100755" is sent as "100644". */
    fun createTree(token: String, owner: String, name: String, baseTree: String?, entries: List<TreeEntryInput>): Request {
        val url = repoUrl(owner, name).addPathSegments("git/trees").build()
        val list = JSONArray()
        for (entry in entries) {
            list.put(
                JSONObject()
                    .put("path", entry.path)
                    .put("mode", if (entry.mode == "100755") "100755" else "100644")
                    .put("type", "blob")
                    .put("sha", entry.sha),
            )
        }
        val body = JSONObject()
        if (baseTree != null) body.put("base_tree", baseTree)
        body.put("tree", list)
        return base(token, url).post(json(body)).build()
    }

    /** `POST .../git/commits`. */
    fun createCommit(token: String, owner: String, name: String, message: String, treeSha: String, parentSha: String): Request {
        val url = repoUrl(owner, name).addPathSegments("git/commits").build()
        val body = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray().put(parentSha))
        return base(token, url).post(json(body)).build()
    }

    /** `PATCH .../git/refs/heads/{branch}` with `force` false, so a branch that moved is never overwritten. */
    fun updateRef(token: String, owner: String, name: String, branch: String, commitSha: String): Request {
        val url = repoUrl(owner, name).addPathSegments("git/refs/heads").addPathSegments(branch).build()
        val body = JSONObject().put("sha", commitSha).put("force", false)
        return base(token, url).patch(json(body)).build()
    }

    /**
     * `PUT .../contents/{path}`: makes the first commit of an empty repository. There is no `branch`
     * field, so GitHub creates the default branch. Each part of the path is encoded on its own.
     */
    fun putFile(token: String, owner: String, name: String, path: String, bytes: ByteArray, message: String): Request {
        val builder = repoUrl(owner, name).addPathSegment("contents")
        for (segment in path.split('/')) builder.addPathSegment(segment)
        val body = Base64JsonBody("{\"message\":" + JSONObject.quote(message) + ",\"content\":\"", bytes, "\"}")
        return base(token, builder.build()).put(body).build()
    }
}

/** Turns a failed GitHub answer or a thrown error into a plain-English [CoderGitHubResult.Err]. No message holds the token, a header, an address or file content. */
object CoderGitHubErrors {
    private const val MAX_DETAIL_CHARS = 200
    private val whitespace = Regex("\\s+")

    private fun err(kind: CoderGitHubErrorKind, message: String): CoderGitHubResult.Err = CoderGitHubResult.Err(kind, message)

    /**
     * The result for HTTP status [code]. [rateLimitRemaining] is the `X-RateLimit-Remaining` header text and
     * [githubMessage] is GitHub's own `message` field, if any. With [forRefUpdate] a 422 means the branch
     * moved, which is a [CoderGitHubErrorKind.Conflict].
     */
    fun map(code: Int, rateLimitRemaining: String?, githubMessage: String?, forRefUpdate: Boolean = false): CoderGitHubResult.Err =
        when {
            code == 401 -> err(CoderGitHubErrorKind.InvalidToken, "GitHub didn't accept this token. It may be wrong or expired.")
            code == 403 && rateLimitRemaining?.trim() == "0" ->
                err(CoderGitHubErrorKind.RateLimited, "GitHub says you've made too many requests. Try again later.")
            code == 403 ->
                err(CoderGitHubErrorKind.NoAccess, "This token doesn't have permission for that. For a classic token, tick the 'repo' box.")
            code == 404 ->
                err(CoderGitHubErrorKind.NotFound, "GitHub couldn't find that. Check the name, and that your token can see this repository.")
            code == 409 -> err(CoderGitHubErrorKind.Conflict, "GitHub reported a conflict with the repository. Try again.")
            code == 422 && forRefUpdate ->
                err(CoderGitHubErrorKind.Conflict, "GitHub has newer changes than this phone. Try again.")
            code == 422 -> err(CoderGitHubErrorKind.Invalid, rejectedText(githubMessage))
            code in 500..599 -> err(CoderGitHubErrorKind.Busy, "GitHub is busy. Try again in a moment.")
            else -> err(CoderGitHubErrorKind.Other, "GitHub returned an error ($code).")
        }

    /** A connection problem or any other thrown error, described by [describeFailure] only. */
    fun fromException(e: Throwable): CoderGitHubResult.Err = err(CoderGitHubErrorKind.Network, describeFailure(e))

    private fun rejectedText(githubMessage: String?): String {
        val detail = (githubMessage ?: "").replace(whitespace, " ").trim().take(MAX_DETAIL_CHARS)
        return if (detail.isEmpty()) "GitHub rejected the request." else "GitHub rejected the request. $detail"
    }
}

/**
 * Talks to GitHub with the person's own token. Every call runs on a background thread, uses HTTPS to
 * api.github.com only, and returns a [CoderGitHubResult] instead of throwing. At most 5 MB of any answer
 * is read. The token is only used in a header and is never put in a result or a message.
 */
class CoderGitHubClient {
    /** The account [token] belongs to, and the scopes GitHub says it has. */
    suspend fun getUser(token: String): CoderGitHubResult<GitHubUser> =
        send(CoderGitHubRequests.user(token)) { raw ->
            parseJson<GitHubUser>(raw) { obj ->
                val login = obj.text("login")
                if (login.isEmpty()) unreadable() else CoderGitHubResult.Ok(GitHubUser(login, raw.scopes))
            }
        }

    /** A repository, if the token can see it. A repository the token cannot push to is a [CoderGitHubErrorKind.NoAccess] error. */
    suspend fun getRepo(token: String, owner: String, name: String): CoderGitHubResult<GitHubRepo> {
        if (!isValidRepo(owner, name)) return badRepoName()
        return send(CoderGitHubRequests.repo(token, owner, name)) { raw ->
            parseJson<GitHubRepo>(raw) { obj ->
                val permissions = obj.optJSONObject("permissions")
                if (permissions != null && permissions.has("push") && !permissions.optBoolean("push", true)) {
                    CoderGitHubResult.Err(CoderGitHubErrorKind.NoAccess, "This token can't save changes to this repository.")
                } else {
                    val ownerLogin = obj.optJSONObject("owner")?.text("login").orEmpty().ifEmpty { owner }
                    val repoName = obj.text("name").ifEmpty { name }
                    val branch = obj.text("default_branch").ifEmpty { DEFAULT_BRANCH }
                    CoderGitHubResult.Ok(GitHubRepo(ownerLogin, repoName, branch, obj.optBoolean("private", false)))
                }
            }
        }
    }

    /** Creates a new, empty repository under the token's own account. */
    suspend fun createRepo(token: String, name: String, isPrivate: Boolean): CoderGitHubResult<GitHubRepo> {
        if (!isValidRepoName(name)) return badRepoName()
        return send(CoderGitHubRequests.createRepo(token, name, isPrivate)) { raw ->
            parseJson<GitHubRepo>(raw) { obj ->
                val ownerLogin = obj.optJSONObject("owner")?.text("login").orEmpty()
                if (ownerLogin.isEmpty()) {
                    unreadable()
                } else {
                    val repoName = obj.text("name").ifEmpty { name }
                    val branch = obj.text("default_branch").ifEmpty { DEFAULT_BRANCH }
                    CoderGitHubResult.Ok(GitHubRepo(ownerLogin, repoName, branch, obj.optBoolean("private", isPrivate)))
                }
            }
        }
    }

    /** The commit the branch points to. */
    suspend fun getBranchHead(token: String, owner: String, name: String, branch: String): CoderGitHubResult<String> {
        if (!isValidRepo(owner, name)) return badRepoName()
        if (!isSafeBranch(branch)) return badBranchName()
        return send(CoderGitHubRequests.branchHead(token, owner, name, branch)) { raw ->
            parseJson<String>(raw) { obj ->
                val sha = obj.optJSONObject("object")?.text("sha").orEmpty()
                if (sha.isEmpty()) unreadable() else CoderGitHubResult.Ok(sha)
            }
        }
    }

    /** Whether the repository has no commit yet. GitHub answers 409 for an empty repository. */
    suspend fun isRepoEmpty(token: String, owner: String, name: String): CoderGitHubResult<Boolean> {
        if (!isValidRepo(owner, name)) return badRepoName()
        return guarded {
            val raw = rawCall(CoderGitHubRequests.latestCommit(token, owner, name))
            when {
                raw.code == 409 -> CoderGitHubResult.Ok(true)
                raw.code in 200..299 -> CoderGitHubResult.Ok(false)
                else -> errorOf(raw, false)
            }
        }
    }

    /** The tree hash of a commit. */
    suspend fun getCommitTree(token: String, owner: String, name: String, commitSha: String): CoderGitHubResult<String> {
        if (!isValidRepo(owner, name)) return badRepoName()
        if (!isSha(commitSha)) return badIdentifier()
        return send(CoderGitHubRequests.commit(token, owner, name, commitSha)) { raw ->
            parseJson<String>(raw) { obj ->
                val sha = obj.optJSONObject("tree")?.text("sha").orEmpty()
                if (sha.isEmpty()) unreadable() else CoderGitHubResult.Ok(sha)
            }
        }
    }

    /** Every file and folder of a tree. A repository too big for one answer is an error. */
    suspend fun getTree(token: String, owner: String, name: String, treeSha: String): CoderGitHubResult<List<RemoteEntry>> {
        if (!isValidRepo(owner, name)) return badRepoName()
        if (!isSha(treeSha)) return badIdentifier()
        return send(CoderGitHubRequests.tree(token, owner, name, treeSha)) { raw ->
            parseJson<List<RemoteEntry>>(raw, TREE_TOO_LARGE) { obj ->
                if (obj.optBoolean("truncated", false)) {
                    CoderGitHubResult.Err(CoderGitHubErrorKind.Other, TREE_TOO_LARGE)
                } else {
                    val items = obj.optJSONArray("tree") ?: JSONArray()
                    val entries = ArrayList<RemoteEntry>(items.length())
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        entries.add(RemoteEntry(item.getString("path"), item.getString("type"), item.getString("sha"), item.text("mode")))
                    }
                    CoderGitHubResult.Ok(entries)
                }
            }
        }
    }

    /** Uploads one file's bytes and returns its blob hash. */
    suspend fun createBlob(token: String, owner: String, name: String, bytes: ByteArray): CoderGitHubResult<String> {
        if (!isValidRepo(owner, name)) return badRepoName()
        return send(CoderGitHubRequests.createBlob(token, owner, name, bytes)) { raw -> parseSha(raw) }
    }

    /** Makes a tree out of [entries] on top of [baseTree] (null for a tree with only [entries]) and returns its hash. */
    suspend fun createTree(
        token: String,
        owner: String,
        name: String,
        baseTree: String?,
        entries: List<TreeEntryInput>,
    ): CoderGitHubResult<String> {
        if (!isValidRepo(owner, name)) return badRepoName()
        return send(CoderGitHubRequests.createTree(token, owner, name, baseTree, entries)) { raw -> parseSha(raw) }
    }

    /** Makes a commit of [treeSha] on top of [parentSha] and returns its hash. */
    suspend fun createCommit(
        token: String,
        owner: String,
        name: String,
        message: String,
        treeSha: String,
        parentSha: String,
    ): CoderGitHubResult<String> {
        if (!isValidRepo(owner, name)) return badRepoName()
        return send(CoderGitHubRequests.createCommit(token, owner, name, message, treeSha, parentSha)) { raw -> parseSha(raw) }
    }

    /** Moves the branch to [commitSha], never by force. A branch that moved meanwhile is a [CoderGitHubErrorKind.Conflict]. */
    suspend fun updateRef(token: String, owner: String, name: String, branch: String, commitSha: String): CoderGitHubResult<Unit> {
        if (!isValidRepo(owner, name)) return badRepoName()
        if (!isSafeBranch(branch)) return badBranchName()
        return send(CoderGitHubRequests.updateRef(token, owner, name, branch, commitSha), forRefUpdate = true) {
            CoderGitHubResult.Ok(Unit)
        }
    }

    /** Makes the first commit of an empty repository by creating one file. GitHub creates the default branch. */
    suspend fun createFileInEmptyRepo(
        token: String,
        owner: String,
        name: String,
        path: String,
        bytes: ByteArray,
        message: String,
    ): CoderGitHubResult<Unit> {
        if (!isValidRepo(owner, name)) return badRepoName()
        if (!CoderPaths.isValidPath(path)) {
            return CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, "That file name can't be uploaded.")
        }
        return send(CoderGitHubRequests.putFile(token, owner, name, path, bytes, message)) { CoderGitHubResult.Ok(Unit) }
    }

    private class Raw(val code: Int, val body: String?, val rateRemaining: String?, val scopes: String?)

    /** Runs [block] on a background thread and turns any thrown error (except cancellation) into a result. */
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

    private suspend fun <T> send(
        request: Request,
        forRefUpdate: Boolean = false,
        onOk: (Raw) -> CoderGitHubResult<T>,
    ): CoderGitHubResult<T> = guarded {
        val raw = rawCall(request)
        if (raw.code in 200..299) onOk(raw) else errorOf(raw, forRefUpdate)
    }

    private suspend fun rawCall(request: Request): Raw =
        CoderHttp.client.newCall(request).awaitResponse().use { response ->
            Raw(response.code, readBody(response), response.header("X-RateLimit-Remaining"), response.header("X-OAuth-Scopes"))
        }

    private fun errorOf(raw: Raw, forRefUpdate: Boolean): CoderGitHubResult.Err =
        CoderGitHubErrors.map(raw.code, raw.rateRemaining, messageOf(raw.body), forRefUpdate)

    /** GitHub's own one-line explanation in an error answer, or null. */
    private fun messageOf(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).text("message").ifEmpty { null }
        } catch (e: JSONException) {
            null
        }
    }

    private fun <T> parseJson(
        raw: Raw,
        tooLargeMessage: String = ANSWER_TOO_LARGE,
        block: (JSONObject) -> CoderGitHubResult<T>,
    ): CoderGitHubResult<T> {
        val text = raw.body ?: return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, tooLargeMessage)
        return try {
            block(JSONObject(text))
        } catch (e: JSONException) {
            unreadable()
        }
    }

    private fun parseSha(raw: Raw): CoderGitHubResult<String> = parseJson<String>(raw) { obj ->
        val sha = obj.text("sha")
        if (sha.isEmpty()) unreadable() else CoderGitHubResult.Ok(sha)
    }

    private fun unreadable(): CoderGitHubResult.Err =
        CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub sent an answer this app couldn't read.")

    private fun badRepoName(): CoderGitHubResult.Err =
        CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, "That repository name isn't valid.")

    private fun badBranchName(): CoderGitHubResult.Err =
        CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, "That branch name isn't valid.")

    private fun badIdentifier(): CoderGitHubResult.Err =
        CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, "GitHub sent an identifier this app doesn't understand.")

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

    /** True only when [owner]/[name] survives the same parsing a typed repository reference gets. It keeps odd characters out of the address. */
    private fun isValidRepo(owner: String, name: String): Boolean = parseRepoRef("$owner/$name") == Pair(owner, name)

    /** True for a hexadecimal object hash of 40 to 64 characters. */
    private fun isSha(value: String): Boolean =
        value.length in 40..64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** A branch name that cannot change the meaning of the address it is put into. */
    private fun isSafeBranch(branch: String): Boolean {
        if (branch.isEmpty() || branch.length > MAX_BRANCH_LENGTH) return false
        if (branch.startsWith("/") || branch.endsWith("/") || branch.contains("//") || branch.contains("..")) return false
        if (branch.split('/').any { it == "." }) return false
        return branch.none { it.code < 33 || it.code == 127 || it in "~^:?*[\\" }
    }

    private companion object {
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_BRANCH_LENGTH = 255
        const val DEFAULT_BRANCH = "main"
        const val ANSWER_TOO_LARGE = "GitHub's answer was too large to read."
        const val TREE_TOO_LARGE = "This repository is too large to compare here."
    }
}
