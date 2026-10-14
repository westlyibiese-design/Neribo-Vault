package com.westly.neribovault.feature.coder.github

import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** The size limits of one push. They are declared here and enforced by the push code. */
object CoderPushLimits {
    const val MAX_FILES_PER_PUSH = 500
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
}

/**
 * Works out what a push would change: it lists the project's files with their git hashes and compares
 * them with the file tree GitHub has. Nothing here uses the network, and nothing ever deletes a file on GitHub.
 */
object CoderPushPlanner {
    private const val TEMP_MARKER = ".tmp-"

    /**
     * True for a path that is never uploaded: what a ZIP import also leaves out (`node_modules`, `.git`,
     * system files) and the half-written temp files the file store makes while saving.
     */
    fun isIgnored(path: String): Boolean {
        if (CoderZipRules.shouldSkip(path) != null) return true
        val name = CoderPaths.nameOf(path)
        return name.startsWith(".") && name.contains(TEMP_MARKER)
    }

    /**
     * Every uploadable file of the project with its size and git hash, hashed on a background thread. A
     * file that cannot be read is left out. Stops quickly when the coroutine is cancelled.
     */
    suspend fun snapshot(store: CoderFileStore, projectId: String): List<LocalFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<LocalFile>()
        for (entry in store.listAll(projectId)) {
            ensureActive()
            if (entry.isDirectory) continue
            if (!CoderPaths.isValidPath(entry.path) || isIgnored(entry.path)) continue
            val file = store.resolve(projectId, entry.path) ?: continue
            if (!file.isFile) continue
            val sha = try {
                CoderGitSha.blobSha(file)
            } catch (e: IOException) {
                continue
            }
            out.add(LocalFile(entry.path, file.length(), sha))
        }
        out
    }

    /**
     * Compares [local] with [remote]. A file is unchanged when GitHub has a blob at that path with the
     * same hash, changed when the blob differs, and new when the path is not on GitHub. Files that are only
     * on GitHub are left alone. A local file whose path is a folder or submodule on GitHub is left out
     * and listed in [PushPlan.conflicts]. For a changed file the mode GitHub has now is recorded in
     * [PushPlan.remoteModes] so an executable file stays executable.
     */
    fun computePlan(local: List<LocalFile>, remote: List<RemoteEntry>): PushPlan {
        val blobs = HashMap<String, RemoteEntry>()
        val others = HashSet<String>()
        for (entry in remote) {
            if (entry.type == "blob") blobs[entry.path] = entry else others.add(entry.path)
        }
        val newFiles = ArrayList<LocalFile>()
        val changedFiles = ArrayList<LocalFile>()
        val conflicts = ArrayList<String>()
        val modes = LinkedHashMap<String, String>()
        var unchanged = 0
        for (file in local) {
            if (file.path in others) {
                conflicts.add(file.path)
                continue
            }
            val existing = blobs[file.path]
            when {
                existing == null -> newFiles.add(file)
                existing.sha == file.sha -> unchanged++
                else -> {
                    changedFiles.add(file)
                    modes[file.path] = existing.mode
                }
            }
        }
        return PushPlan(newFiles, changedFiles, unchanged, modes, conflicts)
    }
}
