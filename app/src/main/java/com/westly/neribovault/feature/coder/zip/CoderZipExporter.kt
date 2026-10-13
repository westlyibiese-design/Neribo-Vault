package com.westly.neribovault.feature.coder.zip

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The outcome of an export: how many files were saved, or a plain-English reason why nothing was. */
sealed interface CoderExportResult {
    data class Success(val fileCount: Int) : CoderExportResult
    data class Failure(val message: String) : CoderExportResult
}

/**
 * Saves a whole project as a ZIP, with the same folders and file names the project has. This ZIP is
 * the project's backup, because Coder is not part of the normal Vault backup. Empty folders are kept.
 */
class CoderZipExporter(
    context: Context,
    private val store: CoderFileStore,
) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * Writes [projectId] to the document at [target], which the person just created in the system file
     * picker. Runs on `Dispatchers.IO`. When the export fails or is cancelled the unfinished document is removed again.
     */
    suspend fun export(projectId: String, target: Uri): CoderExportResult = withContext(Dispatchers.IO) {
        val entries = store.listAll(projectId).filter { !isTempName(CoderPaths.nameOf(it.path)) }
        if (entries.isEmpty()) {
            discardTarget(target)
            return@withContext CoderExportResult.Failure(MSG_EMPTY)
        }
        try {
            val stream = resolver.openOutputStream(target, "w")
            if (stream == null) {
                discardTarget(target)
                CoderExportResult.Failure(MSG_FAILED)
            } else {
                val count = stream.use { writeProjectZip(store, projectId, entries, it) }
                CoderExportResult.Success(count)
            }
        } catch (e: CancellationException) {
            discardTarget(target)
            throw e
        } catch (e: Exception) {
            discardTarget(target)
            CoderExportResult.Failure(MSG_FAILED)
        }
    }

    /** Best effort: a half-written or empty document is of no use to anyone. */
    private fun discardTarget(target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (e: Exception) {
            // The document may already be gone or the provider may not allow deleting; nothing more can be done.
        }
    }
}

private const val EXPORT_BUFFER_SIZE = 64 * 1024
private const val MSG_EMPTY = "This project is empty, so there is nothing to export."
private const val MSG_FAILED = "Couldn't save the ZIP. Please try again."

/** True for the half-written files the file store makes while saving. */
private fun isTempName(name: String): Boolean = name.startsWith(".") && name.contains(".tmp-")

/**
 * Writes [entries] of [projectId] as a ZIP to [out] and returns how many files (not folders) it holds.
 * Folders become entries that end in "/", so empty folders survive. Does not close [out].
 */
internal suspend fun writeProjectZip(store: CoderFileStore, projectId: String, entries: List<CoderEntry>, out: OutputStream): Int {
    val zip = ZipOutputStream(BufferedOutputStream(out))
    val buffer = ByteArray(EXPORT_BUFFER_SIZE)
    var files = 0
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        if (entry.isDirectory) {
            val item = ZipEntry(entry.path + "/")
            item.time = entry.lastModified
            zip.putNextEntry(item)
            zip.closeEntry()
        } else {
            val file = store.resolve(projectId, entry.path) ?: throw IOException("A file is not available")
            val item = ZipEntry(entry.path)
            item.time = entry.lastModified
            zip.putNextEntry(item)
            FileInputStream(file).use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                }
            }
            zip.closeEntry()
            files++
        }
    }
    zip.finish()
    zip.flush()
    return files
}
