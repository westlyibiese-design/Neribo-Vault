package com.westly.neribovault.feature.coder.files

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

/**
 * True when [path] belongs to the app: its first part starts with `.coder` (for example `.coder-build/index.html`).
 * Such folders are hidden from the file tree, never exported, pushed or sent to an AI, and do not count toward the limits.
 */
fun isCoderHiddenPath(path: String): Boolean = path.substringBefore('/').startsWith(CODER_HIDDEN_PREFIX)

/** The start of the name of every folder or file the app keeps for itself inside a project. */
const val CODER_HIDDEN_PREFIX = ".coder"

/** One file that a new project starts with. [path] must pass [CoderPaths.isValidPath]. */
data class CoderStarterFile(val path: String, val content: String)

/** One file or folder of a project. [path] is relative, "/" separated, without a leading slash. */
data class CoderEntry(val path: String, val isDirectory: Boolean, val sizeBytes: Long, val lastModified: Long)

/** The outcome of reading a file. [Text.text] is always LF-only. */
sealed interface CoderReadResult {
    data class Text(val text: String, val usesCrlf: Boolean) : CoderReadResult
    object TooLarge : CoderReadResult
    object Binary : CoderReadResult
    object Missing : CoderReadResult
    object Failed : CoderReadResult
}

/** The outcome of writing, creating or renaming. */
sealed interface CoderWriteResult {
    object Saved : CoderWriteResult
    object TooLarge : CoderWriteResult
    object LimitReached : CoderWriteResult
    object InvalidPath : CoderWriteResult
    object AlreadyExists : CoderWriteResult
    object Failed : CoderWriteResult
}

/** The outcome of copying one file into a project from a stream. */
sealed interface CoderCopyResult {
    /** The file is in the project; [bytes] is how many bytes were really copied. */
    data class Copied(val bytes: Long) : CoderCopyResult
    object TooLarge : CoderCopyResult
    object LimitReached : CoderCopyResult
    object NoSpace : CoderCopyResult
    object AlreadyExists : CoderCopyResult
    object InvalidPath : CoderCopyResult
    object Cancelled : CoderCopyResult
    object Failed : CoderCopyResult
}

/**
 * The files of every Coder project, as real folders under [rootDir] (`filesDir/coder`).
 * All paths are checked here; nothing outside a project's own folder can be read or changed.
 * Every function does blocking disk work, so call it from `Dispatchers.IO`.
 */
class CoderFileStore(private val rootDir: File) {

    /** The folder of [projectId]. For an id that is not a plain id this is a harmless folder that nothing writes to. */
    fun projectDir(projectId: String): File =
        File(rootDir, if (isValidProjectId(projectId)) projectId else INVALID_PROJECT_DIR)

    /**
     * The file or folder at [path] inside [projectId], or null when the id or path is invalid or the
     * result would leave the project folder (the canonical path also blocks symbolic links).
     * An empty [path] is the project folder itself.
     */
    fun resolve(projectId: String, path: String): File? {
        if (!isValidProjectId(projectId)) return null
        if (path.isNotEmpty() && !CoderPaths.isValidPath(path)) return null
        val dir = File(rootDir, projectId)
        val target = if (path.isEmpty()) dir else File(dir, path)
        return try {
            val base = dir.canonicalPath
            val canonical = target.canonicalPath
            if (canonical == base || canonical.startsWith(base + File.separator)) target else null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Every file and folder of the project, each folder followed by its content; folders first, then files, by name
     * ignoring case. Entries whose first part starts with `.coder` belong to the app and are left out unless
     * [includeHidden] is true.
     */
    fun listAll(projectId: String, includeHidden: Boolean = false): List<CoderEntry> {
        val dir = resolve(projectId, "") ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<CoderEntry>()
        collect(dir, "", 0, out, CoderLimits.MAX_FILES_PER_PROJECT + 500, includeHidden)
        return out
    }

    /** Reads a text file. Binary, oversized and missing files are reported, never returned. */
    fun read(projectId: String, path: String): CoderReadResult {
        val file = resolve(projectId, path) ?: return CoderReadResult.Missing
        return try {
            when {
                !file.isFile -> CoderReadResult.Missing
                file.length() > CoderLimits.MAX_EDITABLE_BYTES -> CoderReadResult.TooLarge
                else -> decode(file.readBytes())
            }
        } catch (e: Exception) {
            CoderReadResult.Failed
        }
    }

    /**
     * Writes [text] to [path] atomically (temp file, then rename) and creates parent folders.
     * With [crlf] every line break is written as CRLF.
     */
    fun write(projectId: String, path: String, text: String, crlf: Boolean = false): CoderWriteResult {
        if (path.isEmpty()) return CoderWriteResult.InvalidPath
        val target = resolve(projectId, path) ?: return CoderWriteResult.InvalidPath
        val output = if (crlf) text.replace("\r\n", "\n").replace("\n", "\r\n") else text
        val bytes = output.toByteArray(Charsets.UTF_8)
        if (bytes.size > CoderLimits.MAX_EDITABLE_BYTES) return CoderWriteResult.TooLarge
        return try {
            if (target.isDirectory) return CoderWriteResult.AlreadyExists
            val existingBytes = if (target.isFile) target.length() else -1L
            if (existingBytes < 0L && fileCount(projectId) >= CoderLimits.MAX_FILES_PER_PROJECT) {
                return CoderWriteResult.LimitReached
            }
            val growth = bytes.size.toLong() - maxOf(existingBytes, 0L)
            if (growth > 0L && projectSizeBytes(projectId) + growth > CoderLimits.MAX_PROJECT_BYTES) {
                return CoderWriteResult.LimitReached
            }
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return CoderWriteResult.Failed
            }
            if (writeAtomically(target, bytes)) CoderWriteResult.Saved else CoderWriteResult.Failed
        } catch (e: Exception) {
            CoderWriteResult.Failed
        }
    }

    /** Creates a new file; [CoderWriteResult.AlreadyExists] when anything is already at [path]. */
    fun createFile(projectId: String, path: String, text: String = ""): CoderWriteResult {
        if (path.isEmpty() || isCoderHiddenPath(path)) return CoderWriteResult.InvalidPath
        val target = resolve(projectId, path) ?: return CoderWriteResult.InvalidPath
        if (target.exists()) return CoderWriteResult.AlreadyExists
        return write(projectId, path, text)
    }

    /** Creates a new folder (and any missing parents). */
    fun createFolder(projectId: String, path: String): CoderWriteResult {
        if (path.isEmpty() || isCoderHiddenPath(path)) return CoderWriteResult.InvalidPath
        val target = resolve(projectId, path) ?: return CoderWriteResult.InvalidPath
        if (target.exists()) return CoderWriteResult.AlreadyExists
        return try {
            if (target.mkdirs() || target.isDirectory) CoderWriteResult.Saved else CoderWriteResult.Failed
        } catch (e: Exception) {
            CoderWriteResult.Failed
        }
    }

    /** Renames or moves a file or folder. A folder cannot be moved into itself. */
    fun rename(projectId: String, fromPath: String, toPath: String): CoderWriteResult {
        if (fromPath.isEmpty() || toPath.isEmpty() || isCoderHiddenPath(toPath)) return CoderWriteResult.InvalidPath
        val source = resolve(projectId, fromPath) ?: return CoderWriteResult.InvalidPath
        val target = resolve(projectId, toPath) ?: return CoderWriteResult.InvalidPath
        if (!source.exists()) return CoderWriteResult.Failed
        if (toPath.startsWith("$fromPath/")) return CoderWriteResult.Failed
        if (target.exists()) return CoderWriteResult.AlreadyExists
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return CoderWriteResult.Failed
            }
            if (source.renameTo(target)) CoderWriteResult.Saved else CoderWriteResult.Failed
        } catch (e: Exception) {
            CoderWriteResult.Failed
        }
    }

    /**
     * Copies [input] to [path] (missing parent folders are made). The bytes go to a temp file first, so a copy that is
     * stopped, too big or failed never leaves half a file and never damages the file it would have replaced.
     * More than [maxFileBytes] really read gives [CoderCopyResult.TooLarge]; a project that would pass its size limit
     * gives [CoderCopyResult.LimitReached]. Something already at [path] is only replaced when [overwrite] is true.
     * [shouldContinue] is asked before every chunk; when it says false the copy is undone and [CoderCopyResult.Cancelled]
     * returned. The caller closes [input].
     */
    fun copyIn(
        projectId: String,
        path: String,
        input: InputStream,
        overwrite: Boolean,
        maxFileBytes: Long = CoderFileCopy.MAX_FILE_BYTES,
        shouldContinue: () -> Boolean = { true },
    ): CoderCopyResult {
        if (path.isEmpty() || isCoderHiddenPath(path)) return CoderCopyResult.InvalidPath
        val target = resolve(projectId, path) ?: return CoderCopyResult.InvalidPath
        if (target.isDirectory) return CoderCopyResult.AlreadyExists
        val existed = target.isFile
        if (existed && !overwrite) return CoderCopyResult.AlreadyExists
        if (!existed && fileCount(projectId) >= CoderLimits.MAX_FILES_PER_PROJECT) return CoderCopyResult.LimitReached
        val replacedBytes = if (existed) target.length() else 0L
        val budget = CoderLimits.MAX_PROJECT_BYTES - projectSizeBytes(projectId) + replacedBytes
        val parent = target.parentFile ?: return CoderCopyResult.Failed
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return CoderCopyResult.Failed
        val temp = File(parent, "." + target.name + TEMP_MARKER + UUID.randomUUID().toString().substring(0, TEMP_TOKEN_LENGTH))
        var total = 0L
        var stopped: CoderCopyResult? = null
        try {
            FileOutputStream(temp).use { stream ->
                val buffer = ByteArray(CoderFileCopy.COPY_BUFFER_BYTES)
                while (true) {
                    if (!shouldContinue()) {
                        stopped = CoderCopyResult.Cancelled
                        break
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxFileBytes) {
                        stopped = CoderCopyResult.TooLarge
                        break
                    }
                    if (total > budget) {
                        stopped = CoderCopyResult.LimitReached
                        break
                    }
                    stream.write(buffer, 0, read)
                }
                if (stopped != null) return@use
                stream.flush()
                stream.fd.sync()
            }
        } catch (e: Exception) {
            temp.delete()
            return if (e is IOException && isNoSpace(e)) CoderCopyResult.NoSpace else CoderCopyResult.Failed
        }
        val verdict = stopped
        if (verdict != null) {
            temp.delete()
            return verdict
        }
        var moved = temp.renameTo(target)
        if (!moved) {
            target.delete()
            moved = temp.renameTo(target)
        }
        if (!moved) {
            temp.delete()
            return CoderCopyResult.Failed
        }
        return CoderCopyResult.Copied(total)
    }

    /**
     * Moves the file or folder [fromPath] into the existing folder [newParent] ("" is the project root), keeping its
     * name. It uses [rename], so nothing is ever overwritten: a name that is already taken gives
     * [CoderWriteResult.AlreadyExists]. A folder cannot go into itself or into one of its own folders, and a move that
     * would make the content too deep or too long for the project gives [CoderWriteResult.Failed].
     */
    fun moveEntry(projectId: String, fromPath: String, newParent: String): CoderWriteResult {
        if (fromPath.isEmpty() || isCoderHiddenPath(fromPath)) return CoderWriteResult.InvalidPath
        if (newParent.isNotEmpty() && (!CoderPaths.isValidPath(newParent) || isCoderHiddenPath(newParent))) {
            return CoderWriteResult.InvalidPath
        }
        if (!CoderFileCopy.canMoveInto(fromPath, newParent)) return CoderWriteResult.Failed
        val source = resolve(projectId, fromPath) ?: return CoderWriteResult.InvalidPath
        if (newParent.isNotEmpty() && resolve(projectId, newParent)?.isDirectory != true) return CoderWriteResult.Failed
        val toPath = CoderFileCopy.movedPath(fromPath, newParent)
        if (toPath == fromPath) return CoderWriteResult.Saved
        if (!fitsAfterMove(source, toPath)) return CoderWriteResult.Failed
        return rename(projectId, fromPath, toPath)
    }

    /** True when every path inside [source] is still a valid project path once [source] lives at [toPath]. */
    private fun fitsAfterMove(source: File, toPath: String): Boolean {
        if (!source.isDirectory) return true
        val base = source.path
        for (inside in source.walkTopDown()) {
            val relative = inside.path.removePrefix(base).replace(File.separatorChar, '/')
            if (!CoderPaths.isValidPath(toPath + relative)) return false
        }
        return true
    }

    private fun isNoSpace(e: IOException): Boolean {
        val text = e.message ?: return false
        return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)
    }

    /** Deletes a file or a folder with everything inside it. The project folder itself is refused. */
    fun delete(projectId: String, path: String): Boolean {
        if (path.isEmpty()) return false
        val target = resolve(projectId, path) ?: return false
        return try {
            target.exists() && target.deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the whole project folder. True when it no longer exists afterwards. */
    fun deleteProject(projectId: String): Boolean {
        if (!isValidProjectId(projectId)) return false
        val dir = File(rootDir, projectId)
        return try {
            if (dir.exists()) dir.deleteRecursively()
            !dir.exists()
        } catch (e: Exception) {
            false
        }
    }

    /** The total size of the project's files, in bytes. Files in the app's own `.coder` folders do not count. */
    fun projectSizeBytes(projectId: String): Long {
        val dir = resolve(projectId, "") ?: return 0L
        return visibleFiles(dir).sumOf { it.length() }
    }

    /** How many regular files the project has. Files in the app's own `.coder` folders do not count. */
    fun fileCount(projectId: String): Int {
        val dir = resolve(projectId, "") ?: return 0
        return visibleFiles(dir).size
    }

    /** The regular files under [dir], leaving out every top-level entry whose name starts with `.coder`. */
    private fun visibleFiles(dir: File): List<File> {
        val out = ArrayList<File>()
        val children = dir.listFiles() ?: return out
        for (child in children) {
            if (child.name.startsWith(CODER_HIDDEN_PREFIX)) continue
            if (child.isFile) out.add(child) else out.addAll(child.walkTopDown().filter { it.isFile })
        }
        return out
    }

    private fun collect(dir: File, prefix: String, depth: Int, out: MutableList<CoderEntry>, limit: Int, includeHidden: Boolean) {
        if (depth > CoderLimits.MAX_FOLDER_DEPTH) return
        val children = dir.listFiles() ?: return
        val sorted = children
            .filter { !isTempName(it.name) }
            .filter { includeHidden || prefix.isNotEmpty() || !it.name.startsWith(CODER_HIDDEN_PREFIX) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        for (child in sorted) {
            if (out.size >= limit) return
            val path = if (prefix.isEmpty()) child.name else prefix + "/" + child.name
            if (child.isDirectory) {
                out.add(CoderEntry(path, true, 0L, child.lastModified()))
                collect(child, path, depth + 1, out, limit, includeHidden)
            } else {
                out.add(CoderEntry(path, false, child.length(), child.lastModified()))
            }
        }
    }

    private fun decode(bytes: ByteArray): CoderReadResult {
        val scan = minOf(bytes.size, NUL_SCAN_BYTES)
        for (i in 0 until scan) {
            if (bytes[i] == 0.toByte()) return CoderReadResult.Binary
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            return CoderReadResult.Binary
        }
        val text = if (decoded.startsWith("\uFEFF")) decoded.substring(1) else decoded
        val crlf = text.contains("\r\n")
        return CoderReadResult.Text(if (crlf) text.replace("\r\n", "\n") else text, crlf)
    }

    /** Writes to a temp file in the same folder, syncs it, then renames it over [target]. */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val temp = File(target.parentFile, "." + target.name + TEMP_MARKER + UUID.randomUUID().toString().substring(0, TEMP_TOKEN_LENGTH))
        return try {
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
            if (!moved) temp.delete()
            moved
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    /** True for the half-written files this class creates itself, so they never show up in a project. */
    private fun isTempName(name: String): Boolean {
        if (!name.startsWith(".")) return false
        val marker = name.lastIndexOf(TEMP_MARKER)
        if (marker <= 0) return false
        val token = name.substring(marker + TEMP_MARKER.length)
        return token.length == TEMP_TOKEN_LENGTH && token.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun isValidProjectId(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_PROJECT_ID_LENGTH) return false
        return id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }

    private companion object {
        const val INVALID_PROJECT_DIR = "_invalid"
        const val MAX_PROJECT_ID_LENGTH = 64
        const val NUL_SCAN_BYTES = 8_000
        const val TEMP_MARKER = ".tmp-"
        const val TEMP_TOKEN_LENGTH = 8
    }
}
