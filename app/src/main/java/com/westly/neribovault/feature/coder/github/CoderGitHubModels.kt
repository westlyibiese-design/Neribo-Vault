package com.westly.neribovault.feature.coder.github

/** Why a GitHub call failed, in the groups the screen needs to tell the person what to do next. */
enum class CoderGitHubErrorKind { InvalidToken, NoAccess, NotFound, Conflict, Invalid, RateLimited, Busy, Network, Other }

/** The answer of a GitHub call: [Ok] with a value, or [Err] with a kind and a plain-English message that never holds the token. */
sealed interface CoderGitHubResult<out T> {
    data class Ok<out T>(val value: T) : CoderGitHubResult<T>
    data class Err(val kind: CoderGitHubErrorKind, val message: String) : CoderGitHubResult<Nothing>
}

/** The account a token belongs to. [scopes] is the text of GitHub's `X-OAuth-Scopes` header, null when it is absent. */
data class GitHubUser(val login: String, val scopes: String?)

/** A repository the token can see. */
data class GitHubRepo(val owner: String, val name: String, val defaultBranch: String, val isPrivate: Boolean)

/** One entry of a repository's file tree. [type] is "blob", "tree" or "commit". */
data class RemoteEntry(val path: String, val type: String, val sha: String, val mode: String)

/** One file to put into a new tree. [mode] is "100644" or "100755"; the type is always a blob. */
data class TreeEntryInput(val path: String, val mode: String, val sha: String)

/** One file of a project as it is on the phone: its path, its size and its git blob hash. */
data class LocalFile(val path: String, val sizeBytes: Long, val sha: String)

/**
 * What a push would do. Files that exist only on GitHub are not listed because a push never deletes anything.
 * [remoteModes] holds the mode GitHub has now for each path in [changedFiles]. [conflicts] lists the local
 * paths left out because a folder (or submodule) with that name is on GitHub.
 */
data class PushPlan(
    val newFiles: List<LocalFile>,
    val changedFiles: List<LocalFile>,
    val unchangedCount: Int,
    val remoteModes: Map<String, String>,
    val conflicts: List<String> = emptyList(),
)

/** A line that looks like a password or key. It never contains the matched text. [line] is 1-based. */
data class SecretFinding(val path: String, val line: Int, val kind: String)

/** Everything the screen shows before anything is uploaded. [headSha] and [treeSha] are null only when [emptyRepo] is true. */
data class PushPreview(
    val plan: PushPlan,
    val findings: List<SecretFinding>,
    val isPrivate: Boolean,
    val emptyRepo: Boolean,
    val headSha: String?,
    val treeSha: String?,
)

/** The outcome of a finished push. */
data class PushResult(val commitSha: String, val uploadedCount: Int)

private const val MAX_OWNER_LENGTH = 39
private const val MAX_REPO_NAME_LENGTH = 100

private fun isValidOwner(owner: String): Boolean =
    owner.length in 1..MAX_OWNER_LENGTH &&
        owner.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

/** True for a repository name GitHub accepts: 1 to 100 letters, digits, "-", "_" or ".", and not "." or "..". */
fun isValidRepoName(name: String): Boolean =
    name.length in 1..MAX_REPO_NAME_LENGTH &&
        name != "." && name != ".." &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }

/**
 * Reads a repository reference the way people write it: `owner/name`, `https://github.com/owner/name`,
 * the same with `.git` or a closing slash, or `github.com/owner/name`. Returns (owner, name), or null
 * when the text is anything else.
 */
fun parseRepoRef(raw: String): Pair<String, String>? {
    var text = raw.trim()
    for (scheme in listOf("https://", "http://")) {
        if (text.startsWith(scheme, ignoreCase = true)) {
            text = text.substring(scheme.length)
            break
        }
    }
    for (host in listOf("www.github.com/", "github.com/")) {
        if (text.startsWith(host, ignoreCase = true)) {
            text = text.substring(host.length)
            break
        }
    }
    text = text.trimEnd('/')
    if (text.endsWith(".git", ignoreCase = true)) text = text.dropLast(4)
    val parts = text.split('/')
    if (parts.size != 2) return null
    val owner = parts[0]
    val name = parts[1]
    return if (isValidOwner(owner) && isValidRepoName(name)) owner to name else null
}
