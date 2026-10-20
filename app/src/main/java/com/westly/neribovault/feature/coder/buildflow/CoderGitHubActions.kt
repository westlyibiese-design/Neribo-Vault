package com.westly.neribovault.feature.coder.buildflow

import com.westly.neribovault.feature.coder.ai.CoderHttp
import com.westly.neribovault.feature.coder.ai.awaitResponse
import com.westly.neribovault.feature.coder.github.CoderGitHubErrorKind
import com.westly.neribovault.feature.coder.github.CoderGitHubErrors
import com.westly.neribovault.feature.coder.github.CoderGitHubResult
import com.westly.neribovault.feature.coder.github.CoderSecretScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Builds every request of the build feature. Pure, so the self-test checks them without a network. The host is
 * always `api.github.com`; the token is only in the `Authorization` header; owner, repository and branch must be
 * checked by the caller (the client does that).
 */
object CoderActionsRequests {
    private const val HOST = "api.github.com"

    private fun repo(owner: String, name: String): HttpUrl.Builder = HttpUrl.Builder().scheme("https").host(HOST)
        .addPathSegment("repos").addPathSegment(owner).addPathSegment(name)

    private fun base(token: String, url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "NeriboVault-Coder")

    private fun json(body: JSONObject): RequestBody = body.toString().toRequestBody(JSON_TYPE)

    /** `POST .../actions/variables`. */
    fun createVariable(token: String, owner: String, name: String, varName: String, value: String): Request {
        val url = repo(owner, name).addPathSegments("actions/variables").build()
        return base(token, url).post(json(JSONObject().put("name", varName).put("value", value))).build()
    }

    /** `PATCH .../actions/variables/{name}`. */
    fun updateVariable(token: String, owner: String, name: String, varName: String, value: String): Request {
        val url = repo(owner, name).addPathSegments("actions/variables").addPathSegment(varName).build()
        return base(token, url).patch(json(JSONObject().put("name", varName).put("value", value))).build()
    }

    /** `POST .../actions/workflows/coder-build.yml/dispatches` with the branch as `ref`. */
    fun dispatch(token: String, owner: String, name: String, branch: String): Request {
        val url = repo(owner, name).addPathSegments("actions/workflows")
            .addPathSegment(CoderBuildWorkflow.WORKFLOW_FILE).addPathSegment("dispatches").build()
        return base(token, url).post(json(JSONObject().put("ref", branch))).build()
    }

    /** `GET .../actions/workflows/coder-build.yml/runs?event=workflow_dispatch&branch=..&per_page=5`. */
    fun runs(token: String, owner: String, name: String, branch: String): Request {
        val url = repo(owner, name).addPathSegments("actions/workflows")
            .addPathSegment(CoderBuildWorkflow.WORKFLOW_FILE).addPathSegment("runs")
            .addQueryParameter("event", "workflow_dispatch")
            .addQueryParameter("branch", branch)
            .addQueryParameter("per_page", "5").build()
        return base(token, url).get().build()
    }

    /** `GET .../actions/runs/{id}`. */
    fun run(token: String, owner: String, name: String, runId: Long): Request =
        base(token, repo(owner, name).addPathSegments("actions/runs").addPathSegment(runId.toString()).build()).get().build()

    /** `GET .../actions/runs/{id}/jobs`. */
    fun jobs(token: String, owner: String, name: String, runId: Long): Request = base(
        token,
        repo(owner, name).addPathSegments("actions/runs").addPathSegment(runId.toString()).addPathSegment("jobs").build(),
    ).get().build()

    /** `POST .../actions/runs/{id}/cancel`. */
    fun cancel(token: String, owner: String, name: String, runId: Long): Request = base(
        token,
        repo(owner, name).addPathSegments("actions/runs").addPathSegment(runId.toString()).addPathSegment("cancel").build(),
    ).post(ByteArray(0).toRequestBody(null)).build()

    /** `GET .../actions/runs/{id}/artifacts`. */
    fun artifacts(token: String, owner: String, name: String, runId: Long): Request = base(
        token,
        repo(owner, name).addPathSegments("actions/runs").addPathSegment(runId.toString()).addPathSegment("artifacts").build(),
    ).get().build()

    /** `GET .../actions/artifacts/{id}/zip`; GitHub answers with a redirect to a storage address. */
    fun artifactZip(token: String, owner: String, name: String, artifactId: Long): Request = base(
        token,
        repo(owner, name).addPathSegments("actions/artifacts").addPathSegment(artifactId.toString()).addPathSegment("zip").build(),
    ).get().build()

    /** `GET .../actions/jobs/{id}/logs`; GitHub answers with a redirect to a text file. */
    fun jobLogs(token: String, owner: String, name: String, jobId: Long): Request = base(
        token,
        repo(owner, name).addPathSegments("actions/jobs").addPathSegment(jobId.toString()).addPathSegment("logs").build(),
    ).get().build()
}

/** The plain-English messages for the answers GitHub gives to the build calls. No message holds the token, an address or log text. */
object CoderActionsErrors {
    const val MSG_WORKFLOW_SCOPE =
        "Your token needs the 'workflow' permission. Make a new token with both 'repo' and 'workflow' ticked."
    const val MSG_NO_MINUTES =
        "GitHub says this account has no build minutes left. Free accounts get a monthly allowance; try again later."
    const val MSG_ACTIONS_OFF =
        "GitHub Actions isn't available for this repository. Check that Actions is turned on in the repository settings."

    /** The result for HTTP status [code] of a call to an Actions address. [githubMessage] is GitHub's own `message` field, if any. */
    fun map(code: Int, rateLimitRemaining: String?, githubMessage: String?): CoderGitHubResult.Err {
        val text = (githubMessage ?: "").lowercase()
        return when {
            (code == 403 || code == 422) && (text.contains("minutes") || text.contains("billing") || text.contains("spending limit")) ->
                CoderGitHubResult.Err(CoderGitHubErrorKind.Other, MSG_NO_MINUTES)
            code == 403 && rateLimitRemaining?.trim() != "0" && text.contains("resource not accessible") ->
                CoderGitHubResult.Err(CoderGitHubErrorKind.NoAccess, MSG_WORKFLOW_SCOPE)
            text.contains("workflow") && text.contains("scope") ->
                CoderGitHubResult.Err(CoderGitHubErrorKind.NoAccess, MSG_WORKFLOW_SCOPE)
            code == 404 -> CoderGitHubResult.Err(CoderGitHubErrorKind.NotFound, MSG_ACTIONS_OFF)
            else -> CoderGitHubErrors.map(code, rateLimitRemaining, githubMessage)
        }
    }

    /**
     * An error from the push, made friendlier: GitHub's refusal to save a workflow file without the `workflow`
     * permission becomes the same plain message the build calls give. Any other error is returned unchanged.
     */
    fun improvePushError(error: CoderGitHubResult.Err): CoderGitHubResult.Err {
        val text = error.message.lowercase()
        return if (text.contains("workflow") && (text.contains("scope") || text.contains("permission"))) {
            CoderGitHubResult.Err(CoderGitHubErrorKind.NoAccess, MSG_WORKFLOW_SCOPE)
        } else {
            error
        }
    }
}

/** What [CoderActionsLogic.pickArtifact] found: the artifact to download, or [problem] in plain English. */
data class ArtifactChoice(val artifact: ActionArtifact?, val problem: String?)

/** The pure decisions of the build calls. */
object CoderActionsLogic {
    /** The most bytes of an artifact Coder downloads. */
    const val MAX_ARTIFACT_BYTES = 60L * 1024 * 1024

    /** The most bytes of a job log that are read. */
    const val MAX_LOG_BYTES = 1024 * 1024

    /** How many lines of a job log are kept. */
    const val LOG_TAIL_LINES = 60

    private const val RUN_GRACE_MILLIS = 15_000L
    private val TIMESTAMP = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private const val HIDDEN_LINE = "[line hidden: it looked like a secret]"

    /** The newest run created no earlier than 15 seconds before [dispatchedAtMillis], or null. */
    fun pickRun(runs: List<ActionRun>, dispatchedAtMillis: Long): ActionRun? =
        runs.filter { it.createdAtMillis >= dispatchedAtMillis - RUN_GRACE_MILLIS }.maxByOrNull { it.createdAtMillis }

    /** The artifact named `coder-dist` that has not expired and is at most 60 MB, or the reason there is none. */
    fun pickArtifact(list: List<ActionArtifact>): ArtifactChoice {
        val named = list.filter { it.name == CoderBuildWorkflow.ARTIFACT_NAME }
        if (named.isEmpty()) return ArtifactChoice(null, "The build finished but made no download. Check the output folder name.")
        val live = named.filter { !it.expired }
        if (live.isEmpty()) return ArtifactChoice(null, "The build result has expired on GitHub. Build again.")
        val fit = live.firstOrNull { it.sizeBytes <= MAX_ARTIFACT_BYTES }
            ?: return ArtifactChoice(null, "The built site is bigger than 60 MB, which is too large to download here.")
        return ArtifactChoice(fit, null)
    }

    /** True when a refused dispatch should be tried again: the workflow file may not be known to GitHub yet. */
    fun shouldRetryDispatch(code: Int, githubMessage: String?): Boolean =
        code == 404 || (code == 422 && (githubMessage ?: "").contains("workflow_dispatch"))

    /**
     * The log text ready to show: timestamps and colour codes removed, the last 60 lines kept, and every line the
     * secret scanner flags replaced by a note, so a key that a build printed is never shown or sent anywhere.
     */
    fun cleanLogTail(raw: String): String {
        val lines = raw.lineSequence().map { line -> ANSI.replace(TIMESTAMP.replace(line, ""), "").trimEnd() }.toList()
        val tail = lines.dropLastWhile { it.isEmpty() }.takeLast(LOG_TAIL_LINES)
        if (tail.isEmpty()) return ""
        val flagged = CoderSecretScanner.scan(listOf("log" to tail.joinToString("\n"))).map { it.line }.toSet()
        return tail.mapIndexed { index, line -> if ((index + 1) in flagged) HIDDEN_LINE else line }.joinToString("\n")
    }
}

/**
 * Talks to the GitHub Actions part of the API with the person's own token, only to `api.github.com`, only over
 * HTTPS, always through [CoderHttp.client]. Every call runs on a background thread and returns a
 * [CoderGitHubResult]; no result or message holds the token, an address or log text that was not cleaned.
 */
class CoderGitHubActions {
    private class Raw(val code: Int, val body: String?, val rate: String?)

    /** Sets the repository variable [varName]; a variable that exists is updated instead. */
    suspend fun setVariable(token: String, owner: String, repo: String, varName: String, value: String): CoderGitHubResult<Unit> =
        guarded {
            val created = call(CoderActionsRequests.createVariable(token, owner, repo, varName, value))
            val message = messageOf(created.body)
            val exists = created.code == 409 || (created.code == 422 && (message ?: "").lowercase().contains("already exists"))
            when {
                created.code in 200..299 -> CoderGitHubResult.Ok(Unit)
                exists -> {
                    val updated = call(CoderActionsRequests.updateVariable(token, owner, repo, varName, value))
                    if (updated.code in 200..299) CoderGitHubResult.Ok(Unit) else errorOf(updated)
                }
                else -> errorOf(created)
            }
        }

    /** Asks GitHub to start the workflow on [branch]. A fresh workflow file may need a few seconds, so a 404 or a missing-trigger 422 is tried again up to 5 times, 3 seconds apart. */
    suspend fun dispatch(token: String, owner: String, repo: String, branch: String): CoderGitHubResult<Unit> =
        guarded { dispatchWithRetry(token, owner, repo, branch) }

    private suspend fun dispatchWithRetry(token: String, owner: String, repo: String, branch: String): CoderGitHubResult<Unit> {
        var retries = 0
        while (true) {
            val raw = call(CoderActionsRequests.dispatch(token, owner, repo, branch))
            if (raw.code in 200..299) return CoderGitHubResult.Ok(Unit)
            if (retries < DISPATCH_RETRIES && CoderActionsLogic.shouldRetryDispatch(raw.code, messageOf(raw.body))) {
                retries++
                delay(DISPATCH_WAIT_MS)
            } else {
                return errorOf(raw)
            }
        }
    }

    /** The newest runs of the build workflow on [branch] started by hand. */
    suspend fun listRuns(token: String, owner: String, repo: String, branch: String): CoderGitHubResult<List<ActionRun>> =
        fetch(CoderActionsRequests.runs(token, owner, repo, branch)) { obj ->
            val array = obj.getJSONArray("workflow_runs")
            (0 until array.length()).mapNotNull { runOf(array.getJSONObject(it)) }
        }

    /** One run. */
    suspend fun getRun(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<ActionRun> =
        fetch(CoderActionsRequests.run(token, owner, repo, runId)) { obj ->
            runOf(obj) ?: throw JSONException("run")
        }

    /** The jobs of a run with the real names and states of their steps. */
    suspend fun getJobs(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<List<ActionJob>> =
        fetch(CoderActionsRequests.jobs(token, owner, repo, runId)) { obj ->
            val array = obj.getJSONArray("jobs")
            (0 until array.length()).map { jobOf(array.getJSONObject(it)) }
        }

    /** Asks GitHub to stop a run. */
    suspend fun cancel(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<Unit> = guarded {
        val raw = call(CoderActionsRequests.cancel(token, owner, repo, runId))
        if (raw.code in 200..299) CoderGitHubResult.Ok(Unit) else errorOf(raw)
    }

    /** The files a run made available. */
    suspend fun listArtifacts(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<List<ActionArtifact>> =
        fetch(CoderActionsRequests.artifacts(token, owner, repo, runId)) { obj ->
            val array = obj.getJSONArray("artifacts")
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ActionArtifact(item.getLong("id"), item.getString("name"), item.getLong("size_in_bytes"), item.optBoolean("expired", false))
            }
        }

    /**
     * Downloads an artifact into [target], at most [maxBytes]. GitHub redirects to a storage address; the client does
     * not forward the token there, and the final address must be https. A partly written [target] is deleted on any
     * failure. Returns the number of bytes written.
     */
    suspend fun downloadArtifact(
        token: String,
        owner: String,
        repo: String,
        artifactId: Long,
        target: File,
        maxBytes: Long,
    ): CoderGitHubResult<Long> = withContext(Dispatchers.IO) {
        try {
            val client = CoderHttp.client.newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
            client.newCall(CoderActionsRequests.artifactZip(token, owner, repo, artifactId)).awaitResponse().use { response ->
                when {
                    !response.isSuccessful -> errorOf(Raw(response.code, readSmall(response), response.header("X-RateLimit-Remaining")))
                    !response.request.url.isHttps ->
                        CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub sent the download to an address that isn't secure, so it was stopped.")
                    else -> copyLimited(response, target, maxBytes)
                }
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            CoderGitHubErrors.fromException(e)
        }
    }

    /** The cleaned last lines of the log of job [jobId] (see [CoderActionsLogic.cleanLogTail]). At most 1 MB is read. */
    suspend fun fetchLogTail(token: String, owner: String, repo: String, jobId: Long): CoderGitHubResult<String> = guarded {
        CoderHttp.client.newCall(CoderActionsRequests.jobLogs(token, owner, repo, jobId)).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                errorOf(Raw(response.code, readSmall(response), response.header("X-RateLimit-Remaining")))
            } else {
                CoderGitHubResult.Ok(CoderActionsLogic.cleanLogTail(readLimited(response, CoderActionsLogic.MAX_LOG_BYTES)))
            }
        }
    }

    private fun copyLimited(response: Response, target: File, maxBytes: Long): CoderGitHubResult<Long> {
        val stream = response.body?.byteStream()
            ?: return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub sent an empty download.")
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        FileOutputStream(target).use { out ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    target.delete()
                    return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "The built site is bigger than 60 MB, which is too large to download here.")
                }
                out.write(buffer, 0, count)
            }
        }
        return CoderGitHubResult.Ok(total)
    }

    private suspend fun <T> fetch(request: Request, parse: (JSONObject) -> T): CoderGitHubResult<T> = guarded {
        val raw = call(request)
        if (raw.code !in 200..299) {
            errorOf(raw)
        } else {
            val text = raw.body ?: return@guarded CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub's answer was too large to read.")
            try {
                CoderGitHubResult.Ok(parse(JSONObject(text)))
            } catch (e: JSONException) {
                CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub sent an answer this app couldn't read.")
            }
        }
    }

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

    private suspend fun call(request: Request): Raw =
        CoderHttp.client.newCall(request).awaitResponse().use { response ->
            Raw(response.code, readBody(response), response.header("X-RateLimit-Remaining"))
        }

    private fun errorOf(raw: Raw): CoderGitHubResult.Err = CoderActionsErrors.map(raw.code, raw.rate, messageOf(raw.body))

    private fun messageOf(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(body)
            if (obj.isNull("message")) null else obj.optString("message").ifEmpty { null }
        } catch (e: JSONException) {
            null
        }
    }

    private fun runOf(obj: JSONObject): ActionRun? = try {
        ActionRun(
            id = obj.getLong("id"),
            status = obj.optString("status", ""),
            conclusion = if (obj.isNull("conclusion")) null else obj.optString("conclusion"),
            createdAtMillis = Instant.parse(obj.getString("created_at")).toEpochMilli(),
            headSha = if (obj.isNull("head_sha")) "" else obj.optString("head_sha"),
        )
    } catch (e: Exception) {
        null
    }

    private fun jobOf(obj: JSONObject): ActionJob {
        val stepArray = obj.optJSONArray("steps")
        val steps = ArrayList<ActionStep>()
        if (stepArray != null) {
            for (i in 0 until stepArray.length()) {
                val step = stepArray.getJSONObject(i)
                steps.add(
                    ActionStep(
                        name = step.optString("name", ""),
                        status = step.optString("status", ""),
                        conclusion = if (step.isNull("conclusion")) null else step.optString("conclusion"),
                        number = step.optInt("number", i + 1),
                    ),
                )
            }
        }
        return ActionJob(
            id = obj.getLong("id"),
            name = obj.optString("name", ""),
            status = obj.optString("status", ""),
            conclusion = if (obj.isNull("conclusion")) null else obj.optString("conclusion"),
            steps = steps,
        )
    }

    /** The answer as text, at most 5 MB of it; null when it is bigger. */
    private fun readBody(response: Response): String? {
        val stream = response.body?.byteStream() ?: return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BODY_BYTES) return null
            out.write(buffer, 0, count)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** The first 64 KB of an error answer, enough to find GitHub's message. */
    private fun readSmall(response: Response): String? = try {
        val stream = response.body?.byteStream()
        if (stream == null) {
            null
        } else {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (out.size() < SMALL_BYTES) {
                val count = stream.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    } catch (e: IOException) {
        null
    }

    /** At most [limit] bytes of the answer as text; the rest is not read. */
    private fun readLimited(response: Response, limit: Int): String {
        val stream = response.body?.byteStream() ?: return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (out.size() < limit) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val DISPATCH_RETRIES = 5
        const val DISPATCH_WAIT_MS = 3_000L
        const val BUFFER_BYTES = 8192
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val SMALL_BYTES = 64 * 1024
    }
}
