package com.westly.neribovault.feature.coder.github

import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** The commit message used when the person leaves the field empty. */
const val DEFAULT_COMMIT_MESSAGE = "Update from Neribo Vault Coder"

private const val MAX_FIRST_LINE_CHARS = 200
private const val MAX_MESSAGE_CHARS = 2000
private const val MODE_EXECUTABLE = "100755"
private const val MODE_NORMAL = "100644"

/**
 * Makes the person's commit message ready for GitHub: trimmed, with the first line cut to 200 characters,
 * and [DEFAULT_COMMIT_MESSAGE] when nothing is left.
 */
fun normalizeCommitMessage(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return DEFAULT_COMMIT_MESSAGE
    val lines = trimmed.lines()
    val first = lines.first().trim().take(MAX_FIRST_LINE_CHARS)
    val rest = lines.drop(1).joinToString("\n").trim().take(MAX_MESSAGE_CHARS)
    return when {
        first.isEmpty() && rest.isEmpty() -> DEFAULT_COMMIT_MESSAGE
        first.isEmpty() -> rest
        rest.isEmpty() -> first
        else -> first + "\n\n" + rest
    }
}

/**
 * What [CoderGitHubPusher.prepare] found: the [preview] to show before anything is uploaded, and the
 * paths of files left out of the push because they are over [CoderPushLimits.MAX_FILE_BYTES].
 */
data class PushPrepared(val preview: PushPreview, val oversized: List<String>)

/**
 * Pushes a project to its GitHub repository in two steps so the person can confirm in between:
 * [prepare] only reads (nothing is uploaded) and [execute] uploads the files and makes one commit.
 * GitHub only changes at the very last call (the branch update), so a cancelled push changes nothing.
 * Nothing here ever deletes a file on GitHub, and no message holds the token, an address or file content.
 */
class CoderGitHubPusher(
    private val client: CoderGitHubClient,
    private val settings: CoderGitHubSettings,
    private val store: CoderFileStore,
    private val projects: CoderProjectsRepository,
) {
    /**
     * Checks the linked repository and the branch, compares the project's files with what GitHub has and
     * scans the files that would be uploaded for secrets. Nothing is uploaded.
     */
    suspend fun prepare(projectId: String, owner: String, name: String, branch: String): CoderGitHubResult<PushPrepared> {
        val token = settings.token() ?: return invalidToken()
        val repo = when (val r = client.getRepo(token, owner, name)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return r
        }

        var emptyRepo = false
        var headSha: String? = null
        var treeSha: String? = null
        var remote: List<RemoteEntry> = emptyList()

        val head = client.getBranchHead(token, owner, name, branch)
        if (head is CoderGitHubResult.Ok) {
            headSha = head.value
            treeSha = when (val r = client.getCommitTree(token, owner, name, head.value)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            remote = when (val r = client.getTree(token, owner, name, treeSha)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
        } else if (head is CoderGitHubResult.Err) {
            // GitHub answers "not found" for a missing branch and "conflict" for a repository with no commit at all.
            val missing = head.kind == CoderGitHubErrorKind.NotFound || head.kind == CoderGitHubErrorKind.Conflict
            if (!missing) return head
            val isEmpty = when (val r = client.isRepoEmpty(token, owner, name)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            if (!isEmpty) {
                return CoderGitHubResult.Err(
                    CoderGitHubErrorKind.Other,
                    "The branch '$branch' doesn't exist on GitHub. Use the repository's default branch.",
                )
            }
            emptyRepo = true
        }

        val local = CoderPushPlanner.snapshot(store, projectId)
        val rawPlan = CoderPushPlanner.computePlan(local, remote)
        val (plan, oversized) = splitOversized(rawPlan)

        val uploadCount = plan.newFiles.size + plan.changedFiles.size
        if (uploadCount > CoderPushLimits.MAX_FILES_PER_PUSH) {
            return CoderGitHubResult.Err(
                CoderGitHubErrorKind.Other,
                "Too many changed files for one push ($uploadCount). Push is limited to ${CoderPushLimits.MAX_FILES_PER_PUSH} files at a time.",
            )
        }

        val findings = scanForSecrets(projectId, plan)
        val preview = PushPreview(
            plan = plan,
            findings = findings,
            isPrivate = repo.isPrivate,
            emptyRepo = emptyRepo,
            headSha = headSha,
            treeSha = treeSha,
        )
        return CoderGitHubResult.Ok(PushPrepared(preview, oversized))
    }

    /**
     * Uploads the files of [preview] one by one, makes one commit with [message] and moves the branch to it.
     * [onProgress] gets (files done so far, files in total) before each upload. Stops quickly when the
     * coroutine is cancelled. For an empty repository the first file makes the first commit and the rest
     * go in a second one.
     */
    suspend fun execute(
        projectId: String,
        owner: String,
        name: String,
        branch: String,
        preview: PushPreview,
        message: String,
        onProgress: (Int, Int) -> Unit,
    ): CoderGitHubResult<PushResult> {
        val token = settings.token() ?: return invalidToken()
        val commitMessage = normalizeCommitMessage(message)

        var activeBranch = branch
        var headSha: String? = preview.headSha
        var treeSha: String? = preview.treeSha
        var plan = preview.plan
        var files = uploadList(plan)
        if (files.isEmpty()) {
            return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "Nothing to push. GitHub already has your latest files.")
        }
        var done = 0
        var total = files.size

        if (preview.emptyRepo) {
            val first = files.first()
            onProgress(1, total)
            val firstBytes = readBytes(projectId, first.path) ?: return unreadable(first.path)
            when (val r = client.createFileInEmptyRepo(token, owner, name, first.path, firstBytes, commitMessage)) {
                is CoderGitHubResult.Ok -> Unit
                is CoderGitHubResult.Err -> return r
            }
            done = 1
            // The first commit created the default branch; continue on top of it.
            val repo = when (val r = client.getRepo(token, owner, name)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            activeBranch = repo.defaultBranch
            val newHead = when (val r = client.getBranchHead(token, owner, name, activeBranch)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            val newTree = when (val r = client.getCommitTree(token, owner, name, newHead)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            val remote = when (val r = client.getTree(token, owner, name, newTree)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            headSha = newHead
            treeSha = newTree
            plan = splitOversized(CoderPushPlanner.computePlan(CoderPushPlanner.snapshot(store, projectId), remote)).first
            files = uploadList(plan)
            if (files.isEmpty()) {
                withContext(NonCancellable) { projects.markPushed(projectId) }
                return CoderGitHubResult.Ok(PushResult(commitSha = newHead, uploadedCount = 1))
            }
            total = 1 + files.size
        }

        val parent = headSha ?: return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub's answer was missing something. Try again.")
        val baseTree = treeSha ?: return CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "GitHub's answer was missing something. Try again.")

        val entries = ArrayList<TreeEntryInput>(files.size)
        for (file in files) {
            currentCoroutineContext().ensureActive()
            onProgress(done + 1, total)
            val bytes = readBytes(projectId, file.path) ?: return unreadable(file.path)
            val blobSha = when (val r = client.createBlob(token, owner, name, bytes)) {
                is CoderGitHubResult.Ok -> r.value
                is CoderGitHubResult.Err -> return r
            }
            val mode = if (plan.remoteModes[file.path] == MODE_EXECUTABLE) MODE_EXECUTABLE else MODE_NORMAL
            entries.add(TreeEntryInput(file.path, mode, blobSha))
            done++
        }

        currentCoroutineContext().ensureActive()
        val newTree = when (val r = client.createTree(token, owner, name, baseTree, entries)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return r
        }
        val commitSha = when (val r = client.createCommit(token, owner, name, commitMessage, newTree, parent)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return r
        }
        currentCoroutineContext().ensureActive()
        when (val r = client.updateRef(token, owner, name, activeBranch, commitSha)) {
            is CoderGitHubResult.Ok -> Unit
            is CoderGitHubResult.Err -> return r
        }
        // The push is on GitHub now, so recording it must not be cut short by a cancel.
        withContext(NonCancellable) { projects.markPushed(projectId) }
        return CoderGitHubResult.Ok(PushResult(commitSha = commitSha, uploadedCount = done))
    }

    /** New and changed files, in path order. */
    private fun uploadList(plan: PushPlan): List<LocalFile> =
        (plan.newFiles + plan.changedFiles).sortedBy { it.path }

    /** Takes files over the size limit out of the plan and returns their paths. */
    private fun splitOversized(plan: PushPlan): Pair<PushPlan, List<String>> {
        val big = ArrayList<String>()
        val newOk = plan.newFiles.filter { f ->
            val ok = f.sizeBytes <= CoderPushLimits.MAX_FILE_BYTES
            if (!ok) big.add(f.path)
            ok
        }
        val changedOk = plan.changedFiles.filter { f ->
            val ok = f.sizeBytes <= CoderPushLimits.MAX_FILE_BYTES
            if (!ok) big.add(f.path)
            ok
        }
        return plan.copy(newFiles = newOk, changedFiles = changedOk) to big.sorted()
    }

    /** Reads every text file that would be uploaded and looks for secrets. Binary and big files are skipped. */
    private suspend fun scanForSecrets(projectId: String, plan: PushPlan): List<SecretFinding> {
        val texts = ArrayList<Pair<String, String>>()
        for (file in uploadList(plan)) {
            currentCoroutineContext().ensureActive()
            val result = withContext(Dispatchers.IO) { store.read(projectId, file.path) }
            if (result is CoderReadResult.Text) texts.add(file.path to result.text)
        }
        return withContext(Dispatchers.Default) { CoderSecretScanner.scan(texts) }
    }

    /** The raw bytes of a project file, or null when it is gone, too big or cannot be read. The path goes through the file store. */
    private suspend fun readBytes(projectId: String, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = store.resolve(projectId, path)
        if (file == null || !file.isFile || file.length() > CoderPushLimits.MAX_FILE_BYTES) {
            null
        } else {
            try {
                file.readBytes()
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun unreadable(path: String): CoderGitHubResult.Err =
        CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "Couldn't read $path. It may have changed while pushing. Try again.")

    private fun invalidToken(): CoderGitHubResult.Err = CoderGitHubErrors.map(401, null, null)
}
