package com.westly.neribovault.feature.coder.zip

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** What an import added and what it left out. [strippedWrapperFolder] is the outer folder that was removed, or null. */
data class CoderImportReport(
    val importedFiles: Int,
    val skippedFiles: Int,
    val skippedReasons: List<String>,
    val strippedWrapperFolder: String?,
)

/** The outcome of an import: a new project, or a plain-English reason why nothing was imported. */
sealed interface CoderImportResult {
    data class Success(val projectId: String, val report: CoderImportReport) : CoderImportResult
    data class Failure(val message: String) : CoderImportResult
}

/**
 * Turns a ZIP file into a new Coder project, all or nothing. Entry names are untrusted: each one is
 * cleaned, checked and resolved through [CoderFileStore], sizes are counted from the bytes really
 * written, and a failed or cancelled import deletes everything it made. File names and contents are
 * never logged.
 */
class CoderZipImporter(
    context: Context,
    private val store: CoderFileStore,
    private val projects: CoderProjectsRepository,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Imports the ZIP at [uri] as a new project named after [suggestedName] (the picked file's name). Runs on `Dispatchers.IO` and stops when cancelled. */
    suspend fun import(uri: Uri, suggestedName: String?): CoderImportResult = withContext(Dispatchers.IO) {
        val size = queryPickedSize(uri)
        if (size != null && size > CoderZipRules.MAX_ZIP_FILE_BYTES) {
            return@withContext CoderImportResult.Failure(MSG_ZIP_TOO_BIG)
        }
        importStream(
            store = store,
            open = { resolver.openInputStream(uri) ?: throw IOException("The file could not be opened") },
            suggestedName = suggestedName,
        ) { id, baseName ->
            val taken = projects.observeAll().first().map { it.name }
            val name = CoderZipRules.uniqueProjectName(baseName, taken)
            projects.createProject(name, CoderZipRules.TEMPLATE_ID, emptyList(), id) != null
        }
    }

    private fun queryPickedSize(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
        }
    } catch (e: Exception) {
        null
    }
}

private const val BUFFER_SIZE = 64 * 1024

private const val MSG_ZIP_TOO_BIG = "This ZIP is bigger than 50 MB, which is too large to import."
private const val MSG_UNPACKED_TOO_BIG = "This ZIP is bigger than 50 MB once unpacked, which is too large to import."
private const val MSG_BAD_NAMES = "This ZIP uses file names the app can't read."

/** The message for a ZIP whose entry names try to leave the project folder. Shared with the self-test. */
internal const val ZIP_UNSAFE_NAMES_MESSAGE =
    "This ZIP has file names that try to leave the project folder, so it was not imported."
private const val MSG_PASSWORD = "This ZIP is password-protected. Remove the password and try again."
private const val MSG_NOT_ZIP = "This doesn't look like a valid ZIP file."
private const val MSG_NO_FILES = "This ZIP has no files the app can import."
private const val MSG_TOO_MANY = "This ZIP has more than 2,000 files. Remove unneeded folders and try again."
private const val MSG_GENERIC = "Couldn't import this ZIP. Please try again."

/** Carries a message for the person out of the middle of an import. */
private class ImportFailure(val userMessage: String) : Exception()

/** Running totals of one pass over the ZIP. */
private class Counters {
    var scanned = 0L
    var total = 0L
    var imported = 0
}

/**
 * What the first pass learned. [wrapper] is the outer folder to remove, or null. [folderPaths] are the
 * zero-byte entries that some tools write as a folder without the trailing slash; they are treated as
 * folders. [nameClashes] are files with content whose name is also used as a folder; they are skipped.
 */
private class ScanResult(val wrapper: String?, val folderPaths: Set<String>, val nameClashes: Set<String>)

/** One accepted entry of the first pass: its project path, whether the ZIP marks it as a folder and the bytes it really holds. */
private class AcceptedEntry(val path: String, val folderEntry: Boolean, val bytes: Long)

/** What to do with one ZIP entry. The same entry always gets the same verdict in both passes. */
private sealed interface Verdict {
    class Accept(val path: String) : Verdict
    class Skipped(val reason: SkipReason) : Verdict
    object Unsafe : Verdict
    object Duplicate : Verdict
}

/** Counts what was left out, for the report. */
private class Tally {
    private var nodeModules = false
    private var gitFolder = false
    private var systemFiles = false
    private var unsafeNames = false
    private var tooLarge = 0
    private var suspicious = 0
    private var nameClashes = 0

    var skippedFiles = 0
        private set

    fun skip(reason: SkipReason, folder: Boolean) {
        when (reason) {
            SkipReason.NODE_MODULES -> nodeModules = true
            SkipReason.GIT_FOLDER -> gitFolder = true
            SkipReason.SYSTEM_FILE -> systemFiles = true
        }
        if (!folder) skippedFiles++
    }

    /** A name that is unsafe or already taken. Folders are dropped without a word. */
    fun rejected(folder: Boolean) {
        if (folder) return
        unsafeNames = true
        skippedFiles++
    }

    fun largeFile() {
        tooLarge++
        skippedFiles++
    }

    fun suspiciousFile() {
        suspicious++
        skippedFiles++
    }

    /** A file with content that has the same name as a folder. */
    fun nameClash() {
        nameClashes++
        skippedFiles++
    }

    fun reasons(): List<String> {
        val out = ArrayList<String>()
        if (nodeModules) out.add("node_modules folders (not needed, skipped)")
        if (gitFolder) out.add(".git folders (skipped)")
        if (systemFiles) out.add("system files (skipped)")
        if (tooLarge > 0) out.add(countOf(tooLarge, "file too large", "files too large") + " (skipped)")
        if (suspicious > 0) out.add(countOf(suspicious, "suspicious file", "suspicious files") + " (skipped)")
        if (nameClashes > 0) out.add(countOf(nameClashes, "file", "files") + " skipped because a folder has the same name")
        if (unsafeNames) out.add("unsafe or duplicate names (skipped)")
        return out
    }

    private fun countOf(count: Int, one: String, many: String): String = if (count == 1) "1 $one" else "$count $many"
}

/**
 * The whole import on a stream, so the self-test can run it without a database. [open] is called
 * twice (a first pass that only looks, a second that unpacks). [finish] makes the project row from
 * the new project's id and the name taken from [suggestedName]; false means it could not. Nothing
 * is left behind on any failure or cancellation.
 */
internal suspend fun importStream(
    store: CoderFileStore,
    open: () -> InputStream,
    suggestedName: String?,
    finish: suspend (id: String, baseName: String) -> Boolean,
): CoderImportResult {
    val id = newId()
    return try {
        val scan = scanZip(open)
        val report = extractZip(open, store, id, scan)
        currentCoroutineContext().ensureActive()
        if (!finish(id, CoderZipRules.projectNameFromFile(suggestedName))) throw ImportFailure(MSG_GENERIC)
        CoderImportResult.Success(id, report)
    } catch (e: ImportFailure) {
        discard(store, id)
        CoderImportResult.Failure(e.userMessage)
    } catch (e: CancellationException) {
        discard(store, id)
        throw e
    } catch (e: Exception) {
        discard(store, id)
        CoderImportResult.Failure(MSG_GENERIC)
    }
}

/** Removes the half-made project, even when the import was cancelled. */
private suspend fun discard(store: CoderFileStore, id: String) {
    withContext(NonCancellable) { store.deleteProject(id) }
}

private fun isFolderEntry(entry: ZipEntry): Boolean = entry.isDirectory || entry.name.endsWith("\\")

private fun judge(rawName: String, seen: MutableSet<String>): Verdict {
    val cleaned = CoderZipRules.cleanEntryName(rawName) ?: return Verdict.Unsafe
    val reason = CoderZipRules.shouldSkip(cleaned)
    if (reason != null) return Verdict.Skipped(reason)
    val path = CoderZipRules.normalizeEntryName(rawName) ?: return Verdict.Unsafe
    if (!seen.add(path.lowercase())) return Verdict.Duplicate
    return Verdict.Accept(path)
}

private fun withoutWrapper(path: String, wrapper: String?): String? {
    if (wrapper == null) return path
    val prefix = "$wrapper/"
    return if (path.startsWith(prefix)) path.substring(prefix.length) else null
}

/** Reads the rest of the current entry and throws it away, counting the bytes. Returns how many bytes this entry held. */
private suspend fun drain(zip: ZipInputStream, buffer: ByteArray, counters: Counters): Long {
    var entryBytes = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = zip.read(buffer)
        if (read < 0) return entryBytes
        entryBytes += read
        counters.scanned += read
        if (counters.scanned > CoderZipRules.MAX_SCAN_BYTES) throw ImportFailure(MSG_UNPACKED_TOO_BIG)
    }
}

/**
 * Reads the next entry. From Android 14 the reader itself refuses a name that contains ".." or starts with "/",
 * so that case is turned into a plain message here. The exception's own text is never shown or kept.
 */
private fun nextEntry(zip: ZipInputStream): ZipEntry? = try {
    zip.nextEntry
} catch (e: ZipException) {
    throw ImportFailure(entryFailureMessage(e.message))
}

/** The message for a [ZipException] raised while reading an entry name: password, unsafe names, or not a valid ZIP. */
private fun entryFailureMessage(raw: String?): String {
    val text = (raw ?: "").lowercase()
    if (text.contains("encrypt")) return MSG_PASSWORD
    val unsafe = text.contains("path") || text.contains("..") || text.contains("invalid") || text.contains("traversal")
    return if (unsafe) ZIP_UNSAFE_NAMES_MESSAGE else MSG_NOT_ZIP
}

/** First pass: looks at every entry without unpacking, checks the counts, finds folders written without a slash and the outer folder. */
private suspend fun scanZip(open: () -> InputStream): ScanResult {
    val input = open()
    val accepted = ArrayList<AcceptedEntry>()
    val seen = HashSet<String>()
    val counters = Counters()
    val buffer = ByteArray(BUFFER_SIZE)
    var entries = 0
    var fileEntries = 0
    var folders = 0
    try {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = nextEntry(zip) ?: break
                entries++
                val verdict = judge(entry.name, seen)
                val bytes = drain(zip, buffer, counters)
                if (verdict is Verdict.Accept) {
                    val folder = isFolderEntry(entry)
                    accepted.add(AcceptedEntry(verdict.path, folder, bytes))
                    if (folder) folders++ else fileEntries++
                    if (fileEntries > CoderLimits.MAX_FILES_PER_PROJECT || folders > CoderLimits.MAX_FILES_PER_PROJECT) {
                        throw ImportFailure(MSG_TOO_MANY)
                    }
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        throw ImportFailure(MSG_BAD_NAMES)
    } catch (e: ZipException) {
        val encrypted = (e.message ?: "").contains("encrypt", ignoreCase = true)
        throw ImportFailure(if (encrypted) MSG_PASSWORD else MSG_NOT_ZIP)
    } catch (e: IOException) {
        throw ImportFailure(MSG_NOT_ZIP)
    }
    if (entries == 0) throw ImportFailure(MSG_NOT_ZIP)
    return classify(accepted)
}

/**
 * Sorts the accepted entries. A zero-byte entry that is not marked as a folder but whose path is the start of
 * another accepted entry's path is a folder. A file with content in that position is a name clash. Everything
 * else is a file, and only files decide the outer folder. Both passes use this one result.
 */
private fun classify(accepted: List<AcceptedEntry>): ScanResult {
    val parents = HashSet<String>()
    for (item in accepted) {
        var slash = item.path.indexOf('/')
        while (slash > 0) {
            parents.add(item.path.substring(0, slash))
            slash = item.path.indexOf('/', slash + 1)
        }
    }
    val folderPaths = HashSet<String>()
    val nameClashes = HashSet<String>()
    val files = ArrayList<String>()
    for (item in accepted) {
        if (item.folderEntry) continue
        if (item.path !in parents) {
            files.add(item.path)
        } else if (item.bytes == 0L) {
            folderPaths.add(item.path)
        } else {
            nameClashes.add(item.path)
        }
    }
    if (files.isEmpty()) throw ImportFailure(MSG_NO_FILES)
    return ScanResult(CoderZipRules.stripWrapperFolder(files), folderPaths, nameClashes)
}

/** Second pass: unpacks the importable files into the new project folder and builds the report. */
private suspend fun extractZip(open: () -> InputStream, store: CoderFileStore, id: String, scan: ScanResult): CoderImportReport {
    val seen = HashSet<String>()
    val tally = Tally()
    val counters = Counters()
    val buffer = ByteArray(BUFFER_SIZE)
    ZipInputStream(BufferedInputStream(open())).use { zip ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = nextEntry(zip) ?: break
            val folder = isFolderEntry(entry)
            when (val verdict = judge(entry.name, seen)) {
                is Verdict.Skipped -> tally.skip(verdict.reason, folder)
                Verdict.Unsafe -> tally.rejected(folder)
                Verdict.Duplicate -> tally.rejected(folder)
                is Verdict.Accept -> {
                    if (verdict.path in scan.nameClashes) {
                        tally.nameClash()
                    } else {
                        val relative = withoutWrapper(verdict.path, scan.wrapper)
                        if (relative != null) {
                            if (folder || verdict.path in scan.folderPaths) {
                                makeFolder(store, id, relative)
                            } else {
                                extractFile(zip, entry, store, id, relative, buffer, counters, tally)
                            }
                        }
                    }
                }
            }
            drain(zip, buffer, counters)
        }
    }
    return CoderImportReport(counters.imported, tally.skippedFiles, tally.reasons(), scan.wrapper)
}

private fun makeFolder(store: CoderFileStore, id: String, relative: String) {
    val dir = store.resolve(id, relative) ?: return
    if (!dir.isDirectory) dir.mkdirs()
}

/**
 * Copies the current entry to [relative]. The sizes that count are the bytes really written, not the
 * ones the ZIP claims. A file that is too big or suspiciously packed is deleted again and only noted.
 */
private suspend fun extractFile(
    zip: ZipInputStream,
    entry: ZipEntry,
    store: CoderFileStore,
    id: String,
    relative: String,
    buffer: ByteArray,
    counters: Counters,
    tally: Tally,
) {
    val target = store.resolve(id, relative)
    if (target == null) {
        tally.rejected(false)
        return
    }
    if (entry.size > CoderZipRules.MAX_ENTRY_BYTES) {
        tally.largeFile()
        return
    }
    val parent = target.parentFile
    val blocked = target.isDirectory || parent == null || (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory)
    if (blocked) {
        tally.rejected(false)
        return
    }
    var written = 0L
    var tooLarge = false
    FileOutputStream(target).use { out ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = zip.read(buffer)
            if (read < 0) break
            written += read
            counters.scanned += read
            if (counters.scanned > CoderZipRules.MAX_SCAN_BYTES) throw ImportFailure(MSG_UNPACKED_TOO_BIG)
            if (written > CoderZipRules.MAX_ENTRY_BYTES) {
                tooLarge = true
                break
            }
            out.write(buffer, 0, read)
        }
    }
    when {
        tooLarge -> {
            target.delete()
            tally.largeFile()
        }
        CoderZipRules.isSuspiciousRatio(written, entry.compressedSize) -> {
            target.delete()
            tally.suspiciousFile()
        }
        else -> {
            counters.total += written
            if (counters.total > CoderZipRules.MAX_TOTAL_BYTES) throw ImportFailure(MSG_UNPACKED_TOO_BIG)
            counters.imported++
        }
    }
}
