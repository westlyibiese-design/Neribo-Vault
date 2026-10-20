package com.westly.neribovault.feature.coder.buildflow

import android.os.SystemClock
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.github.CoderGitHubErrorKind
import com.westly.neribovault.feature.coder.github.CoderGitHubResult
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A run that was started: the run and the time (phone clock) the start was asked for. */
data class StartedRun(val run: ActionRun, val dispatchedAtMillis: Long)

/** What one look at a run shows: the run and its jobs. */
data class RunSnapshot(val run: ActionRun, val jobs: List<ActionJob>) {
    /** The real GitHub steps of every job, with their states. */
    fun stages(): List<BuildStage> =
        jobs.flatMap { it.steps }.map { BuildStage(it.name, mapStepState(it.status, it.conclusion)) }
}

/** The step that failed and the cleaned last lines of its log (empty when GitHub gave none). */
data class BuildFailureInfo(val stepName: String, val logTail: String)

/** The outcome of unpacking a build into a folder. */
sealed interface ExtractResult {
    data class Ok(val files: Int, val bytes: Long) : ExtractResult
    data class Refused(val message: String) : ExtractResult
}

/**
 * Runs the GitHub build and brings its result home: asks GitHub to start the workflow, finds the run, watches it,
 * downloads the finished site and unpacks it into the project's hidden `.coder-build` folder. The token is passed
 * in by the caller for each call and never kept. Cancelling the coroutine stops watching or downloading and never
 * leaves a half-made build behind; it does not stop the run on GitHub (use [cancelRun]).
 */
class CoderBuildRunner(
    private val actions: CoderGitHubActions,
    private val store: CoderFileStore,
    private val cacheDir: File,
) {
    /** Starts the workflow on [branch] and finds its run (looking every 3 seconds for up to 60 seconds). */
    suspend fun start(token: String, owner: String, repo: String, branch: String): CoderGitHubResult<StartedRun> {
        val dispatchedAt = System.currentTimeMillis()
        when (val sent = actions.dispatch(token, owner, repo, branch)) {
            is CoderGitHubResult.Ok -> Unit
            is CoderGitHubResult.Err -> return sent
        }
        val giveUpAt = SystemClock.elapsedRealtime() + FIND_MAX_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val listed = actions.listRuns(token, owner, repo, branch)) {
                is CoderGitHubResult.Ok -> {
                    val found = CoderActionsLogic.pickRun(listed.value, dispatchedAt)
                    if (found != null) return CoderGitHubResult.Ok(StartedRun(found, dispatchedAt))
                }
                is CoderGitHubResult.Err -> if (!isPassing(listed.kind)) return listed
            }
            if (SystemClock.elapsedRealtime() >= giveUpAt) {
                return CoderGitHubResult.Err(
                    CoderGitHubErrorKind.Other,
                    "GitHub didn't start the build. Open the Actions tab of your repository to see why.",
                )
            }
            delay(FIND_WAIT_MS)
        }
    }

    /** One look at the run and its jobs. A jobs call that fails leaves the list empty; the run call decides. */
    suspend fun look(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<RunSnapshot> {
        val run = when (val r = actions.getRun(token, owner, repo, runId)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return r
        }
        val jobs = when (val j = actions.getJobs(token, owner, repo, runId)) {
            is CoderGitHubResult.Ok -> j.value
            is CoderGitHubResult.Err -> emptyList()
        }
        return CoderGitHubResult.Ok(RunSnapshot(run, jobs))
    }

    /**
     * Looks at the run every 5 seconds, for at most 15 minutes, and calls [onUpdate] after each look, until GitHub
     * says it is completed. A look that fails because of the connection or because GitHub is busy is tried again.
     */
    suspend fun watch(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        onUpdate: (RunSnapshot) -> Unit,
    ): CoderGitHubResult<RunSnapshot> {
        val giveUpAt = SystemClock.elapsedRealtime() + WATCH_MAX_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val seen = look(token, owner, repo, runId)) {
                is CoderGitHubResult.Ok -> {
                    onUpdate(seen.value)
                    if (seen.value.run.status == "completed") return seen
                }
                is CoderGitHubResult.Err -> if (!isPassing(seen.kind)) return seen
            }
            if (SystemClock.elapsedRealtime() >= giveUpAt) {
                return CoderGitHubResult.Err(
                    CoderGitHubErrorKind.Other,
                    "The build took longer than 15 minutes, so Coder stopped waiting. Check the Actions tab on GitHub.",
                )
            }
            delay(POLL_MS)
        }
    }

    /** Asks GitHub to stop the run. */
    suspend fun cancelRun(token: String, owner: String, repo: String, runId: Long): CoderGitHubResult<Unit> =
        actions.cancel(token, owner, repo, runId)

    /** The failed step of a finished run and the cleaned end of its log. */
    suspend fun failureDetails(token: String, owner: String, repo: String, jobs: List<ActionJob>): BuildFailureInfo {
        val good = setOf("success", "skipped", "neutral")
        val job = jobs.firstOrNull { it.conclusion != null && it.conclusion !in good } ?: jobs.firstOrNull()
            ?: return BuildFailureInfo("Starting the build", "")
        val step = job.steps.firstOrNull { it.conclusion != null && it.conclusion !in good }
        val log = when (val r = actions.fetchLogTail(token, owner, repo, job.id)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> ""
        }
        return BuildFailureInfo(step?.name ?: job.name, log)
    }

    /**
     * Downloads the `coder-dist` file of [run], unpacks it into a temporary folder and swaps it in as `.coder-build`
     * (the old build is only removed once the new one is complete), then writes `.coder-build/.meta`. On any failure
     * the temporary folder and the download are deleted and the old build stays as it was.
     */
    suspend fun downloadResult(
        token: String,
        owner: String,
        repo: String,
        projectId: String,
        run: ActionRun,
        outputDirName: String,
    ): CoderGitHubResult<CoderBuildMeta> = withContext(Dispatchers.IO) {
        val list = when (val r = actions.listArtifacts(token, owner, repo, run.id)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return@withContext r
        }
        val choice = CoderActionsLogic.pickArtifact(list)
        val artifact = choice.artifact
            ?: return@withContext CoderGitHubResult.Err(CoderGitHubErrorKind.Other, choice.problem ?: MSG_NO_RESULT)
        removeLeftovers(projectId)
        val zip = File(cacheDir, "coder-build-" + newId() + ".zip")
        try {
            when (val d = actions.downloadArtifact(token, owner, repo, artifact.id, zip, CoderActionsLogic.MAX_ARTIFACT_BYTES)) {
                is CoderGitHubResult.Ok -> Unit
                is CoderGitHubResult.Err -> return@withContext d
            }
            val tempName = TEMP_PREFIX + newId().take(8)
            val outcome = FileInputStream(zip).use { extractInto(it, store, projectId, tempName) }
            if (outcome is ExtractResult.Refused) {
                return@withContext CoderGitHubResult.Err(CoderGitHubErrorKind.Other, outcome.message)
            }
            if (!swapIn(store, projectId, tempName)) {
                return@withContext CoderGitHubResult.Err(CoderGitHubErrorKind.Other, MSG_SWAP_FAILED)
            }
            val meta = CoderBuildMeta(run.id, System.currentTimeMillis(), run.headSha, outputDirName)
            CoderBuildFiles.writeMeta(store, projectId, meta)
            CoderGitHubResult.Ok(meta)
        } finally {
            zip.delete()
        }
    }

    private fun isPassing(kind: CoderGitHubErrorKind): Boolean =
        kind == CoderGitHubErrorKind.Network || kind == CoderGitHubErrorKind.Busy

    /** Removes temporary and replaced build folders that an earlier, interrupted download left in the project. */
    private fun removeLeftovers(projectId: String) {
        val children = store.projectDir(projectId).listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(TEMP_PREFIX) || child.name.startsWith(OLD_PREFIX)) child.deleteRecursively()
        }
    }

    companion object {
        /** The most files a build may hold. */
        const val MAX_FILES = 5_000

        /** The most bytes a build may hold once unpacked, counted from what is really written. */
        const val MAX_BYTES = 60L * 1024 * 1024

        /** The start of the name of the folder a build is unpacked into. */
        const val TEMP_PREFIX = ".coder-build-tmp-"

        /** The start of the name the previous build gets while the new one is swapped in. */
        const val OLD_PREFIX = ".coder-build-old-"

        const val MSG_UNSAFE = "This build has file names that try to leave the project folder, so it was not used."
        const val MSG_TOO_MANY = "This build has more than 5,000 files, which is too many to keep on this phone."
        const val MSG_TOO_BIG = "This build is bigger than 60 MB once unpacked, which is too large to keep on this phone."
        const val MSG_NO_INDEX = "The build finished, but its output folder has no index.html. Check the output folder name."
        const val MSG_FAILED = "Couldn't unpack the build. Please try again."
        private const val MSG_NO_RESULT = "The build finished but made no download. Check the output folder name."
        private const val MSG_SWAP_FAILED = "Couldn't save the build in your project. Please try again."

        private const val FIND_WAIT_MS = 3_000L
        private const val FIND_MAX_MS = 60_000L
        private const val POLL_MS = 5_000L
        private const val WATCH_MAX_MS = 15L * 60 * 1000
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Unpacks the ZIP in [input] into the folder [tempName] of [projectId]. Every name is cleaned and checked: a
         * name that climbs out of the folder, is absolute or has a drive letter refuses the whole build; an entry with
         * an empty or unusable name is skipped; every path is resolved through [CoderFileStore]; at most [maxFiles]
         * files and [maxBytes] bytes are accepted, counted from the bytes really written; the files are plain copies,
         * so a link in the ZIP never becomes a link. A build with no `index.html` at its top is refused. On any
         * result other than [ExtractResult.Ok] the folder is deleted.
         */
        suspend fun extractInto(
            input: InputStream,
            store: CoderFileStore,
            projectId: String,
            tempName: String,
            maxFiles: Int = MAX_FILES,
            maxBytes: Long = MAX_BYTES,
        ): ExtractResult = withContext(Dispatchers.IO) {
            val dir = store.resolve(projectId, tempName) ?: return@withContext ExtractResult.Refused(MSG_FAILED)
            var finished = false
            try {
                if (!dir.mkdirs() && !dir.isDirectory) return@withContext ExtractResult.Refused(MSG_FAILED)
                val zip = ZipInputStream(BufferedInputStream(input))
                val seen = HashSet<String>()
                val buffer = ByteArray(BUFFER_BYTES)
                var files = 0
                var bytes = 0L
                while (true) {
                    ensureActive()
                    val entry = try {
                        zip.nextEntry
                    } catch (e: ZipException) {
                        return@withContext ExtractResult.Refused(MSG_UNSAFE)
                    }
                    if (entry == null) break
                    val cleaned = CoderZipRules.cleanEntryName(entry.name) ?: continue
                    if (isTraversal(cleaned)) return@withContext ExtractResult.Refused(MSG_UNSAFE)
                    if (!CoderPaths.isValidPath(cleaned)) continue
                    val target = store.resolve(projectId, "$tempName/$cleaned") ?: continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    if (!seen.add(cleaned.lowercase())) continue
                    if (files >= maxFiles) return@withContext ExtractResult.Refused(MSG_TOO_MANY)
                    val parent = target.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                        return@withContext ExtractResult.Refused(MSG_FAILED)
                    }
                    FileOutputStream(target).use { out ->
                        while (true) {
                            ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > maxBytes) return@withContext ExtractResult.Refused(MSG_TOO_BIG)
                            out.write(buffer, 0, count)
                        }
                    }
                    files++
                }
                if (!File(dir, "index.html").isFile) return@withContext ExtractResult.Refused(MSG_NO_INDEX)
                finished = true
                ExtractResult.Ok(files, bytes)
            } catch (e: java.io.IOException) {
                ExtractResult.Refused(MSG_FAILED)
            } finally {
                if (!finished) dir.deleteRecursively()
            }
        }

        /**
         * Makes the unpacked folder [tempName] the project's `.coder-build`. The old build is first renamed aside and
         * only deleted after the new one is in place; when anything fails the old build is put back, the temporary
         * folder is deleted and false is returned.
         */
        fun swapIn(store: CoderFileStore, projectId: String, tempName: String): Boolean {
            val temp = store.resolve(projectId, tempName) ?: return false
            val live = store.resolve(projectId, CoderBuildFiles.BUILD_DIR) ?: return false
            val old = store.resolve(projectId, tempName.replace("-tmp-", "-old-")) ?: return false
            var movedAside = false
            if (live.exists()) {
                if (!live.renameTo(old)) {
                    temp.deleteRecursively()
                    return false
                }
                movedAside = true
            }
            if (!temp.renameTo(live)) {
                if (movedAside) old.renameTo(live)
                temp.deleteRecursively()
                return false
            }
            if (movedAside) old.deleteRecursively()
            return true
        }

        /** True for a name that starts at the root, has a drive letter, or has a ".." part (after backslashes became slashes). */
        fun isTraversal(cleanedName: String): Boolean {
            if (cleanedName.startsWith("/")) return true
            if (cleanedName.length >= 2 && cleanedName[1] == ':' && cleanedName[0].isLetter()) return true
            return cleanedName.split('/').any { it == ".." }
        }
    }
}
