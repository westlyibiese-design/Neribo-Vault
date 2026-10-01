package com.westly.neribovault.feature.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.westly.neribovault.core.util.newId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file that was copied into the app's private storage. */
data class ImportedFile(val path: String, val mimeType: String)

/** The outcome of an import: the copied file, or a short friendly reason it was refused. */
sealed interface ImportResult {
    data class Success(val file: ImportedFile) : ImportResult
    data class Rejected(val message: String) : ImportResult
}

private const val MAX_EDGE_PX = 2400
private const val JPEG_QUALITY = 88
private const val MAX_BYTES = 25L * 1024L * 1024L
private const val BUFFER_BYTES = 16 * 1024
private const val MIME_PDF = "application/pdf"
private const val MIME_JPEG = "image/jpeg"

private const val MSG_TOO_LARGE = "That file is larger than 25 MB. Please choose a smaller one."
private const val MSG_UNSUPPORTED = "Only photos and PDF files can be attached."
private const val MSG_UNREADABLE = "That file could not be read. Please try another one."
private const val MSG_STORAGE = "There isn't enough room to save that file."

private enum class Kind { Image, Pdf }

/**
 * Copies a picked photo or PDF into the app's private storage (`filesDir/documents/`) so a
 * document keeps its attachment even if the original is moved or deleted. Photos are shrunk to a
 * long edge of 2400px and saved as JPEG; PDFs are copied as they are. Every file operation in the
 * suspend functions runs off the main thread; [delete] and [exists] must be called from a
 * background context too.
 */
class DocumentFileStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, "documents")

    /** Copies [uri] into private storage. Returns null when it was refused (see [importAttachmentChecked]). */
    suspend fun importAttachment(uri: Uri): ImportedFile? =
        (importAttachmentChecked(uri) as? ImportResult.Success)?.file

    /** Like [importAttachment] but explains a refusal with a message fit to show the owner. */
    suspend fun importAttachmentChecked(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            importBlocking(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult.Rejected(MSG_UNREADABLE)
        } catch (e: OutOfMemoryError) {
            ImportResult.Rejected(MSG_UNREADABLE)
        }
    }

    /** Deletes one attachment file. Anything outside `filesDir/documents/` is ignored. */
    fun delete(path: String) {
        val file = File(path)
        if (isInsideStore(file)) {
            runCatching { file.delete() }
        }
    }

    /** True when [path] points at an existing file. */
    fun exists(path: String): Boolean = runCatching { File(path).isFile }.getOrDefault(false)

    /** Deletes every file in [paths] (see [delete]) on a background thread. */
    suspend fun deleteFiles(paths: Collection<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            paths.forEach { delete(it) }
        }
    }

    private fun isInsideStore(file: File): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        val parent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        return parent == root
    }

    private fun ensureDirectory(): File? {
        val folder = directory
        if (!folder.exists() && !folder.mkdirs() && !folder.exists()) return null
        return folder
    }

    private fun importBlocking(uri: Uri): ImportResult {
        val size = querySize(uri)
        if (size != null && size > MAX_BYTES) return ImportResult.Rejected(MSG_TOO_LARGE)
        val kind = detectKind(uri) ?: return ImportResult.Rejected(MSG_UNSUPPORTED)
        return when (kind) {
            Kind.Image -> importImage(uri)
            Kind.Pdf -> importPdf(uri)
        }
    }

    /** The size the provider reports for [uri], or null when it does not say. */
    private fun querySize(uri: Uri): Long? {
        return runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    var result: Long? = null
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (column >= 0 && !cursor.isNull(column)) result = cursor.getLong(column)
                    }
                    result
                }
        }.getOrNull()
    }

    /**
     * Photo or PDF from the reported type. When the provider gives no useful type the first bytes
     * decide (a PDF starts with "%PDF-", otherwise the file must decode as an image).
     */
    private fun detectKind(uri: Uri): Kind? {
        val resolver = appContext.contentResolver
        val type = resolver.getType(uri)?.lowercase()
        if (type != null && type != "application/octet-stream") {
            return when {
                type == MIME_PDF -> Kind.Pdf
                type.startsWith("image/") -> Kind.Image
                else -> null
            }
        }
        val header = ByteArray(5)
        val read = runCatching {
            resolver.openInputStream(uri)?.use { it.read(header) } ?: -1
        }.getOrDefault(-1)
        if (read == 5 && String(header, Charsets.US_ASCII) == "%PDF-") return Kind.Pdf
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) Kind.Image else null
    }

    private fun importPdf(uri: Uri): ImportResult {
        val folder = ensureDirectory() ?: return ImportResult.Rejected(MSG_STORAGE)
        val id = newId()
        val target = File(folder, "$id.pdf")
        val partial = File(folder, "$id.pdf.part")
        var tooLarge = false
        var copied = false
        try {
            val input = appContext.contentResolver.openInputStream(uri)
            if (input != null) {
                input.use { source ->
                    FileOutputStream(partial).use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) {
                                copied = true
                                break
                            }
                            total += count
                            if (total > MAX_BYTES) {
                                tooLarge = true
                                break
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            copied = false
        }
        if (tooLarge) {
            partial.delete()
            return ImportResult.Rejected(MSG_TOO_LARGE)
        }
        if (!copied || !partial.renameTo(target)) {
            partial.delete()
            return ImportResult.Rejected(MSG_UNREADABLE)
        }
        return ImportResult.Success(ImportedFile(path = target.absolutePath, mimeType = MIME_PDF))
    }

    private fun importImage(uri: Uri): ImportResult {
        val resolver = appContext.contentResolver

        // 1. Read only the size, so a huge photo is never decoded at full size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return ImportResult.Rejected(MSG_UNREADABLE)
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImportResult.Rejected(MSG_UNREADABLE)

        // 2. Decode at the smallest power-of-two sample that keeps the long edge within the cap.
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > MAX_EDGE_PX) sample *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return ImportResult.Rejected(MSG_UNREADABLE)

        // 3. Honour the camera's rotation, then flatten any transparency onto white.
        val oriented = applyOrientation(decoded, readOrientation(uri))
        val flattened = flattenOnWhite(oriented)

        // 4. Write to a temporary name first so a half-written file is never kept.
        val folder = ensureDirectory()
        if (folder == null) {
            flattened.recycle()
            return ImportResult.Rejected(MSG_STORAGE)
        }
        val id = newId()
        val target = File(folder, "$id.jpg")
        val partial = File(folder, "$id.jpg.part")
        val written = try {
            FileOutputStream(partial).use { out ->
                flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: IOException) {
            false
        } finally {
            flattened.recycle()
        }
        if (!written || !partial.renameTo(target)) {
            partial.delete()
            return ImportResult.Rejected(MSG_STORAGE)
        }
        return ImportResult.Success(ImportedFile(path = target.absolutePath, mimeType = MIME_JPEG))
    }

    /** EXIF orientation (1 to 8) read from a stream; 1 (upright) on any error. */
    private fun readOrientation(uri: Uri): Int {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            } ?: 1
        }.getOrDefault(1)
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.postRotate(90f)
            7 -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.postRotate(270f)
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        source.recycle()
        return flat
    }
}
