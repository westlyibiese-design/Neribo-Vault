package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.files.isCoderHiddenPath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val MSG_NOT_APPLIED = "Couldn't apply the files. Nothing was changed."
private const val MSG_NOTHING_SELECTED = "Nothing is selected."
private const val MSG_BACKUP_TOO_BIG = "These files are too large to back up safely. Apply them one at a time."
private const val MSG_BACKUP_FAILED = "Couldn't save a backup, so nothing was changed. Try again."
private const val MSG_ROLLED_BACK = "Couldn't apply the files. Your project is back as it was."
private const val MSG_ROLLBACK_INCOMPLETE =
    "Couldn't apply the files, and some could not be put back. Use Undo last AI build to restore them."
private const val MSG_LIMIT = "This project has reached its size or file limit."
private const val MSG_BACKUP_UNREADABLE = "The backup for this build can't be read, so it can't be undone."
private const val HEX_DIGITS = "0123456789abcdef"
private const val TEMP_MARKER = ".tmp-"
private const val TEMP_TOKEN_LENGTH = 8
private const val FILES_FOLDER = "files"
private const val MANIFEST_NAME = "manifest.json"
private const val MAX_BACKUP_ID_LENGTH = 40

/** How an apply ended. */
sealed interface CoderApplyResult {
    /**
     * Every file was written. [created] and [replaced] are the project paths; [backupId] names the backup that
     * [CoderAiApply.undo] needs; [tabsReset] are the open files whose editor history was cleared (filled in by the workspace).
     */
    data class Applied(
        val backupId: String,
        val created: List<String>,
        val replaced: List<String>,
        val tabsReset: List<String> = emptyList(),
    ) : CoderApplyResult

    /** Nothing was changed, because a check failed before the first write. [message] is plain English. */
    data class Refused(val message: String) : CoderApplyResult

    /** A write failed and the project was put back as it was (or, when that failed too, [message] says so). */
    data class RolledBack(val message: String) : CoderApplyResult

    /** The apply was stopped and the project was put back as it was. */
    object Cancelled : CoderApplyResult
}

/** How an undo ended. */
sealed interface CoderUndoResult {
    /**
     * The build was undone. [deleted] are the created files that were removed, [restored] the replaced files that got
     * their old text back, [kept] the created files that were edited after the build and so were left alone.
     */
    data class Done(val deleted: List<String>, val restored: List<String>, val kept: List<String>) : CoderUndoResult

    /** The undo could not be finished; the backup was kept. [message] is plain English. */
    data class Failed(val message: String) : CoderUndoResult

    /** There is no such backup (it was already used, or it is older than the last 5). */
    object NoBackup : CoderUndoResult
}

/** One file that passed every check: where it goes, whether it is already there and, if so, whether it uses CRLF. */
private class Prepared(val item: CoderApplyItem, val target: File, val existed: Boolean, val crlf: Boolean)

private sealed interface Prep {
    class Ok(val list: List<Prepared>) : Prep
    class No(val message: String) : Prep
}

/** What a backup folder remembers: the files the build created and replaced, the folders it made and a fingerprint of each created file. */
private class Manifest(
    val created: List<String>,
    val replaced: List<String>,
    val folders: List<String>,
    val hashes: Map<String, String>,
    val createdAtMillis: Long,
)

/** What putting a build back did. [failed] are the paths that could not be put back. */
private class Reverted(
    val deleted: List<String>,
    val restored: List<String>,
    val kept: List<String>,
    val failed: List<String>,
)

/**
 * Applies several AI files to a project in one go, with a backup, and undoes that. Every function does blocking disk
 * work, so call it from `Dispatchers.IO`. A backup is the folder `.coder-backup/<id>/` inside the project (hidden:
 * never listed, pushed, exported or sent to an AI): `files/<path>` holds the exact bytes of every file that was
 * replaced and `manifest.json` lists what the build created and replaced. The copies sit under `files/` so that a
 * project file called `manifest.json` can never meet the manifest. The last 5 backups are kept.
 */
object CoderAiApply {
    /** The hidden folder of a project that holds its backups. */
    const val BACKUP_FOLDER = ".coder-backup"

    /** The most bytes one backup may hold: 20 MB. */
    const val MAX_BACKUP_BYTES = 20L * 1024L * 1024L

    /** How many backups a project keeps. */
    const val KEEP_BACKUPS = 5

    /** The id of the backup made at [millis]: digits only, so ids sort by time. */
    fun backupIdFor(millis: Long): String = String.format(Locale.US, "%013d", maxOf(millis, 0L))

    /** The ids of the project's backups, newest first. A folder without a readable manifest is not a backup. */
    fun backupIds(files: CoderFileStore, projectId: String): List<String> {
        val base = files.resolve(projectId, BACKUP_FOLDER) ?: return emptyList()
        return allIds(base).filter { File(File(base, it), MANIFEST_NAME).isFile }
    }

    /** The id of the newest backup, or null when there is none. */
    fun latestBackupId(files: CoderFileStore, projectId: String): String? = backupIds(files, projectId).firstOrNull()

    /**
     * Writes [items] into the project. Steps: check everything first (nothing is changed when a check fails), copy the
     * files that will be replaced into a new backup, write the files (a new file with createFile, a replaced one with
     * write, keeping its CRLF style), then fingerprint the new files and keep only the last [keep] backups. When a write
     * fails, or [shouldContinue] says false (asked before every copy and every write), everything is put back from
     * the backup and the backup is removed. [now] names the backup; [maxBackupBytes] is the backup limit.
     */
    fun apply(
        files: CoderFileStore,
        projectId: String,
        items: List<CoderApplyItem>,
        shouldContinue: () -> Boolean = { true },
        now: Long = System.currentTimeMillis(),
        maxBackupBytes: Long = MAX_BACKUP_BYTES,
        keep: Int = KEEP_BACKUPS,
        limits: CoderApplyLimits = CoderApplyLimits(),
    ): CoderApplyResult {
        val base = files.resolve(projectId, BACKUP_FOLDER) ?: return CoderApplyResult.Refused(MSG_NOT_APPLIED)
        val prepared = when (val prep = prepare(files, projectId, items, limits)) {
            is Prep.No -> return CoderApplyResult.Refused(prep.message)
            is Prep.Ok -> prep.list
        }
        val replacedItems = prepared.filter { it.existed }
        if (replacedItems.sumOf { it.target.length() } > maxBackupBytes) return CoderApplyResult.Refused(MSG_BACKUP_TOO_BIG)
        val createdPaths = prepared.filter { !it.existed }.map { it.item.path }
        val replacedPaths = replacedItems.map { it.item.path }
        val folders = LinkedHashSet<String>()
        for (p in prepared) if (!p.existed) addMissingFolders(files, projectId, p.item.path, folders)
        val planned = Manifest(createdPaths, replacedPaths, folders.toList(), emptyMap(), now)
        val id = freshId(base, now)
        val dir = File(base, id)
        try {
            val filesDir = File(dir, FILES_FOLDER)
            for (p in replacedItems) {
                if (!shouldContinue()) {
                    dir.deleteRecursively()
                    return CoderApplyResult.Cancelled
                }
                val copy = backupFile(filesDir, p.item.path) ?: throw IOException("Backup path is not usable")
                writeBytes(copy, p.target.readBytes())
            }
            writeManifest(dir, planned)
        } catch (e: Exception) {
            dir.deleteRecursively()
            return CoderApplyResult.Refused(MSG_BACKUP_FAILED)
        }
        var cancelled = false
        var failure: String? = null
        try {
            for (p in prepared) {
                if (!shouldContinue()) {
                    cancelled = true
                    break
                }
                val result = if (p.existed) {
                    files.write(projectId, p.item.path, p.item.code, p.crlf)
                } else {
                    files.createFile(projectId, p.item.path, p.item.code)
                }
                if (result != CoderWriteResult.Saved) {
                    failure = MSG_ROLLED_BACK
                    break
                }
            }
        } catch (e: Exception) {
            failure = MSG_ROLLED_BACK
        }
        if (cancelled || failure != null) return rollBack(files, projectId, dir, planned, cancelled, failure)
        try {
            val hashes = LinkedHashMap<String, String>()
            for (p in prepared) if (!p.existed) hashes[p.item.path] = sha256Hex(p.target.readBytes())
            writeManifest(dir, Manifest(createdPaths, replacedPaths, folders.toList(), hashes, now))
        } catch (e: Exception) {
            return rollBack(files, projectId, dir, planned, false, MSG_ROLLED_BACK)
        }
        prune(base, keep)
        return CoderApplyResult.Applied(id, createdPaths, replacedPaths)
    }

    /**
     * Undoes the build of backup [backupId]: removes the files it created (a created file that was edited since is
     * kept and listed), puts the replaced files back byte for byte, removes the folders the build made when they are
     * empty, and deletes the backup. When a file can't be put back the backup is kept and [CoderUndoResult.Failed]
     * says so; running the undo again continues where it stopped.
     */
    fun undo(files: CoderFileStore, projectId: String, backupId: String): CoderUndoResult {
        if (!isBackupId(backupId)) return CoderUndoResult.NoBackup
        val base = files.resolve(projectId, BACKUP_FOLDER) ?: return CoderUndoResult.NoBackup
        val dir = File(base, backupId)
        if (!dir.isDirectory) return CoderUndoResult.NoBackup
        val manifest = readManifest(dir) ?: return CoderUndoResult.Failed(MSG_BACKUP_UNREADABLE)
        val reverted = revert(files, projectId, dir, manifest, verifyHashes = true)
        if (reverted.failed.isNotEmpty()) {
            val count = reverted.failed.size
            return CoderUndoResult.Failed("Couldn't put back $count ${CoderApplyPlan.fileWord(count)}. The backup was kept, so you can try again.")
        }
        dir.deleteRecursively()
        return CoderUndoResult.Done(reverted.deleted, reverted.restored, reverted.kept)
    }

    /** Undoes the newest backup; [CoderUndoResult.NoBackup] when there is none. */
    fun undoLast(files: CoderFileStore, projectId: String): CoderUndoResult {
        val id = latestBackupId(files, projectId) ?: return CoderUndoResult.NoBackup
        return undo(files, projectId, id)
    }

    /** The result line after an apply, for example "Created 2, replaced 1", plus a note when open files lost their editor history. */
    fun appliedLine(result: CoderApplyResult.Applied): String {
        val line = "Created ${result.created.size}, replaced ${result.replaced.size}"
        val reset = result.tabsReset.size
        if (reset == 0) return line
        return "$line. The editor history of $reset open ${CoderApplyPlan.fileWord(reset)} was cleared."
    }

    /** The result line after an undo: what was removed and restored, then one line for every file that was kept. */
    fun undoLine(done: CoderUndoResult.Done): String {
        val removed = done.deleted.size
        val restored = done.restored.size
        val head = "Undone: removed $removed ${CoderApplyPlan.fileWord(removed)}, restored $restored ${CoderApplyPlan.fileWord(restored)}."
        if (done.kept.isEmpty()) return head
        return head + " " + done.kept.joinToString(" ") { "$it was edited after the AI build, so it was kept." }
    }

    // ---- Checks before anything is written -------------------------------------------------

    /** Checks every item against the rules and the disk. Returns the prepared files or the first reason to refuse. */
    private fun prepare(files: CoderFileStore, projectId: String, items: List<CoderApplyItem>, limits: CoderApplyLimits): Prep {
        if (items.isEmpty()) return Prep.No(MSG_NOTHING_SELECTED)
        if (items.size > limits.maxFiles) return Prep.No("Only ${limits.maxFiles} files can be applied at once.")
        val seen = HashSet<String>()
        val out = ArrayList<Prepared>(items.size)
        var total = 0L
        var growth = 0L
        var newFiles = 0
        for (item in items) {
            val refusal = CoderApplyPlan.pathRefusal(item.path)
            if (refusal != null) return Prep.No(refusal)
            if (!seen.add(item.path)) return Prep.No("${item.path} is in the list twice.")
            val bytes = item.code.toByteArray(Charsets.UTF_8).size.toLong()
            if (bytes > limits.maxFileBytes) return Prep.No("${item.path} is bigger than 300 KB.")
            total += bytes
            if (total > limits.maxTotalBytes) return Prep.No("The files together are bigger than 2 MB.")
            val target = files.resolve(projectId, item.path) ?: return Prep.No("${item.path} can't be used as a file name.")
            if (target.isDirectory) return Prep.No("${item.path} is a folder, so it can't be replaced by a file.")
            val blocker = fileInTheWay(files, projectId, item.path)
            if (blocker != null) return Prep.No("$blocker is a file, so ${item.path} can't be created.")
            if (target.isFile) {
                val read = files.read(projectId, item.path)
                if (read !is CoderReadResult.Text) return Prep.No("${item.path} isn't a text file that can be replaced here.")
                out.add(Prepared(item, target, true, read.usesCrlf))
                growth += bytes - target.length()
            } else {
                out.add(Prepared(item, target, false, false))
                newFiles++
                growth += bytes
            }
        }
        if (files.fileCount(projectId) + newFiles > CoderLimits.MAX_FILES_PER_PROJECT) return Prep.No(MSG_LIMIT)
        if (growth > 0L && files.projectSizeBytes(projectId) + growth > CoderLimits.MAX_PROJECT_BYTES) return Prep.No(MSG_LIMIT)
        return Prep.Ok(out)
    }

    /** The first folder part of [path] that is really a file, or null. */
    private fun fileInTheWay(files: CoderFileStore, projectId: String, path: String): String? {
        var parent = CoderPaths.parentOf(path)
        while (parent.isNotEmpty()) {
            if (files.resolve(projectId, parent)?.isFile == true) return parent
            parent = CoderPaths.parentOf(parent)
        }
        return null
    }

    /** Adds to [out] the folders above [path] that do not exist yet, the deepest first. */
    private fun addMissingFolders(files: CoderFileStore, projectId: String, path: String, out: MutableSet<String>) {
        var parent = CoderPaths.parentOf(path)
        while (parent.isNotEmpty()) {
            if (files.resolve(projectId, parent)?.exists() == true) break
            out.add(parent)
            parent = CoderPaths.parentOf(parent)
        }
    }

    // ---- Putting a build back --------------------------------------------------------------

    /** Puts the project back after a failed or stopped apply, and removes the backup when that worked. */
    private fun rollBack(
        files: CoderFileStore,
        projectId: String,
        dir: File,
        planned: Manifest,
        cancelled: Boolean,
        failure: String?,
    ): CoderApplyResult {
        val clean = try {
            revert(files, projectId, dir, planned, verifyHashes = false).failed.isEmpty()
        } catch (e: Exception) {
            false
        }
        if (clean) dir.deleteRecursively()
        if (!clean) return CoderApplyResult.RolledBack(MSG_ROLLBACK_INCOMPLETE)
        return if (cancelled) CoderApplyResult.Cancelled else CoderApplyResult.RolledBack(failure ?: MSG_ROLLED_BACK)
    }

    /**
     * Removes the created files, puts the replaced files back from the backup and removes the folders the build made
     * when they are empty. With [verifyHashes] a created file whose fingerprint no longer matches (it was edited) is kept.
     */
    private fun revert(files: CoderFileStore, projectId: String, dir: File, manifest: Manifest, verifyHashes: Boolean): Reverted {
        val deleted = ArrayList<String>()
        val restored = ArrayList<String>()
        val kept = ArrayList<String>()
        val failed = ArrayList<String>()
        val filesDir = File(dir, FILES_FOLDER)
        for (path in manifest.created) {
            if (!isSafePath(path)) continue
            val target = files.resolve(projectId, path) ?: continue
            if (!target.exists()) continue
            val expected = manifest.hashes[path]
            if (verifyHashes && expected != null && hashOf(target) != expected) {
                kept.add(path)
                continue
            }
            if (target.delete()) deleted.add(path) else failed.add(path)
        }
        for (path in manifest.replaced) {
            val target = if (isSafePath(path)) files.resolve(projectId, path) else null
            val source = if (isSafePath(path)) backupFile(filesDir, path) else null
            if (target == null || source == null || !source.isFile) {
                failed.add(path)
                continue
            }
            try {
                writeBytes(target, source.readBytes())
                restored.add(path)
            } catch (e: Exception) {
                failed.add(path)
            }
        }
        removeEmptyFolders(files, projectId, manifest.folders)
        return Reverted(deleted, restored, kept, failed)
    }

    /** Removes each folder of [folders] that is an empty folder, the deepest first. */
    private fun removeEmptyFolders(files: CoderFileStore, projectId: String, folders: List<String>) {
        for (folder in folders.sortedByDescending { it.count { c -> c == '/' } }) {
            if (!isSafePath(folder)) continue
            val dirFile = files.resolve(projectId, folder) ?: continue
            if (dirFile.isDirectory && dirFile.list()?.isEmpty() == true) dirFile.delete()
        }
    }

    // ---- Backup folders --------------------------------------------------------------------

    /** The ids of every folder in [base] that is named like a backup, newest first. */
    private fun allIds(base: File): List<String> {
        val children = base.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && isBackupId(it.name) }.map { it.name }.sortedDescending()
    }

    private fun isBackupId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_BACKUP_ID_LENGTH && id.all { it in '0'..'9' || it == '-' }

    /** An id for a backup made at [now] that no folder of [base] uses yet. */
    private fun freshId(base: File, now: Long): String {
        var millis = maxOf(now, 0L)
        while (File(base, backupIdFor(millis)).exists()) millis++
        return backupIdFor(millis)
    }

    /** Keeps the newest [keep] backups (at least one) and removes every other folder named like a backup. */
    private fun prune(base: File, keep: Int) {
        var kept = 0
        for (name in allIds(base)) {
            val dir = File(base, name)
            if (File(dir, MANIFEST_NAME).isFile && kept < maxOf(keep, 1)) {
                kept++
            } else {
                dir.deleteRecursively()
            }
        }
    }

    /** The place inside [filesDir] where the copy of project file [path] lives, or null when it would leave [filesDir]. */
    private fun backupFile(filesDir: File, path: String): File? {
        if (!CoderPaths.isValidPath(path)) return null
        val target = File(filesDir, path)
        return try {
            val base = filesDir.canonicalPath
            val canonical = target.canonicalPath
            if (canonical.startsWith(base + File.separator)) target else null
        } catch (e: IOException) {
            null
        }
    }

    /** True for a path a manifest may name: a valid project path outside the app's own folders. */
    private fun isSafePath(path: String): Boolean = CoderPaths.isValidPath(path) && !isCoderHiddenPath(path)

    // ---- Manifest and bytes ----------------------------------------------------------------

    private fun writeManifest(dir: File, manifest: Manifest) {
        val root = JSONObject()
        root.put("createdAtMillis", manifest.createdAtMillis)
        root.put("createdPaths", JSONArray(manifest.created))
        root.put("replacedPaths", JSONArray(manifest.replaced))
        root.put("createdFolders", JSONArray(manifest.folders))
        val hashes = JSONObject()
        for ((path, hash) in manifest.hashes) hashes.put(path, hash)
        root.put("createdSha256", hashes)
        writeBytes(File(dir, MANIFEST_NAME), root.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readManifest(dir: File): Manifest? {
        return try {
            val root = JSONObject(File(dir, MANIFEST_NAME).readText(Charsets.UTF_8))
            val hashes = LinkedHashMap<String, String>()
            val stored = root.optJSONObject("createdSha256")
            if (stored != null) {
                val keys = stored.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    hashes[key] = stored.optString(key, "")
                }
            }
            Manifest(
                created = stringsOf(root.optJSONArray("createdPaths")),
                replaced = stringsOf(root.optJSONArray("replacedPaths")),
                folders = stringsOf(root.optJSONArray("createdFolders")),
                hashes = hashes,
                createdAtMillis = root.optLong("createdAtMillis", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun stringsOf(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    /** Writes [bytes] to [target] through a temp file in the same folder, so a stopped write never leaves half a file. Throws [IOException] on failure. */
    private fun writeBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("No folder")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IOException("Folder could not be made")
        val temp = File(parent, "." + target.name + TEMP_MARKER + UUID.randomUUID().toString().substring(0, TEMP_TOKEN_LENGTH))
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            var moved = temp.renameTo(target)
            if (!moved) {
                target.delete()
                moved = temp.renameTo(target)
            }
            if (!moved) throw IOException("The file could not be moved into place")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** The fingerprint of a file, or null when it can't be read (so it counts as changed). */
    private fun hashOf(file: File): String? =
        try {
            if (file.isFile) sha256Hex(file.readBytes()) else null
        } catch (e: Exception) {
            null
        }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val value = b.toInt() and 0xff
            out.append(HEX_DIGITS[value shr 4])
            out.append(HEX_DIGITS[value and 0x0f])
        }
        return out.toString()
    }
}
