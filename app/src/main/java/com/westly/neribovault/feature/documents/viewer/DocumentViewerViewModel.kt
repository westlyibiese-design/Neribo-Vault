package com.westly.neribovault.feature.documents.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.files.SecureFileStore
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val MSG_MISSING = "This file is missing."
internal const val MSG_LOCKED = "This file can't be unlocked on this phone."
internal const val MSG_GENERIC = "Couldn't open this file."

private const val HEADER_BYTES = 8_000
private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
private const val VIEWER_CACHE_DIR = "viewer"
private const val MAX_CAUSE_DEPTH = 12
private const val FALLBACK_TITLE = "Untitled document"

/** Everything the viewer screen draws. */
data class ViewerUiState(
    val title: String = "",
    /** For example "JSON", "PDF", "Image" or "Text". */
    val typeLabel: String = "",
    /** For example "12.4 KB". */
    val sizeText: String = "",
    val content: ViewerContent = ViewerContent.Loading,
)

/** What the viewer is showing. */
sealed interface ViewerContent {
    object Loading : ViewerContent

    class Text(
        /** The whole decoded text; used by Copy. */
        val fullText: String,
        /** The text cut at line boundaries; used for display. */
        val chunks: List<String>,
        val monospace: Boolean,
        /** The file is longer than 2 MiB and only the start is shown. */
        val truncated: Boolean,
    ) : ViewerContent

    class Pdf(val pageCount: Int, val source: PdfPageSource) : ViewerContent

    class Image(val bitmap: Bitmap) : ViewerContent

    class Unsupported(val extensionLabel: String, val reason: UnsupportedReason) : ViewerContent

    class Error(val message: String) : ViewerContent
}

enum class UnsupportedReason { Generic, ProtectedPdf }

/**
 * Loads one saved file for the viewer, off the main thread, once. It survives rotation, so the
 * file is never read twice. A PDF's temporary decrypted copy lives in `cacheDir/viewer/` and is
 * deleted when the viewer is left.
 */
class DocumentViewerViewModel(
    private val documentId: String,
    private val repository: PersonalDocumentsRepository,
    appContext: Context,
) : ViewModel() {

    private val context: Context = appContext.applicationContext

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var cleared = false
    private var pdfSource: PdfPageSource? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        var secure = false
        try {
            clearViewerCache()
            val entity = repository.getById(documentId)
            val path = entity?.fileUri
            if (entity == null || path.isNullOrBlank()) {
                fail(MSG_MISSING)
                return
            }
            val file = File(path)
            if (!file.exists()) {
                fail(MSG_MISSING)
                return
            }
            secure = SecureFileStore.isSecure(file)

            val header = readHeader(file)
            val kind = detectKind(entity.title, header)
            val size = SecureFileStore.plainSize(context, file)
            val title = entity.title.trim().ifEmpty { FALLBACK_TITLE }
            val label = typeLabel(kind, entity.title)
            val sizeText = formatFileSize(size)

            val content = when (kind) {
                FileKind.Text -> loadText(file, entity.title)
                FileKind.Pdf -> loadPdf(file)
                FileKind.Image -> loadImage(file, isJpeg = isJpeg(header))
                FileKind.Unsupported -> ViewerContent.Unsupported(
                    extensionLabel = extensionOf(entity.title).uppercase(Locale.ROOT).ifEmpty { "Unknown" },
                    reason = UnsupportedReason.Generic,
                )
            }
            _state.value = ViewerUiState(
                title = title,
                typeLabel = label,
                sizeText = sizeText,
                content = content,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Includes OutOfMemoryError from decoding. Never log names or content.
            fail(describeFailure(t, secure))
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(content = ViewerContent.Error(message))
    }

    // ---- Header and text -----------------------------------------------------------------

    private suspend fun readHeader(file: File): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var total = 0
        SecureFileStore.openInput(context, file).use { stream ->
            while (total < HEADER_BYTES) {
                val read = stream.read(buffer, total, HEADER_BYTES - total)
                if (read < 0) break
                total += read
            }
        }
        currentCoroutineContext().ensureActive()
        return if (total == HEADER_BYTES) buffer else buffer.copyOf(total)
    }

    private fun isJpeg(header: ByteArray): Boolean =
        header.size >= 3 &&
            (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF

    private suspend fun loadText(file: File, name: String): ViewerContent {
        // One byte more than the limit tells us whether the file is longer.
        val limit = MAX_TEXT_BYTES + 1
        val buffer = ByteArray(limit)
        var total = 0
        SecureFileStore.openInput(context, file).use { stream ->
            while (total < limit) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer, total, limit - total)
                if (read < 0) break
                total += read
            }
        }
        val truncated = total > MAX_TEXT_BYTES
        val used = if (truncated) MAX_TEXT_BYTES else total
        val text = decodeText(buffer, used, truncated)
        return ViewerContent.Text(
            fullText = text,
            chunks = chunkText(text),
            monospace = isMonospace(name),
            truncated = truncated,
        )
    }

    // ---- PDF -----------------------------------------------------------------------------

    private suspend fun loadPdf(file: File): ViewerContent {
        var temp: File? = null
        try {
            val readable: File
            if (SecureFileStore.isSecure(file)) {
                val folder = File(context.cacheDir, VIEWER_CACHE_DIR)
                if (!folder.exists() && !folder.mkdirs() && !folder.exists()) {
                    throw IOException("Cannot create the viewer folder")
                }
                // A random part keeps this copy apart from one a previous viewer is still removing.
                val copy = File(folder, tempPdfName(file.name))
                temp = copy
                FileOutputStream(copy).use { out -> SecureFileStore.decryptTo(context, file, out) }
                readable = copy
            } else {
                readable = file
            }

            val source = try {
                PdfPageSource.open(readable, temp)
            } catch (e: SecurityException) {
                deleteQuietly(temp)
                return protectedPdf()
            } catch (e: IOException) {
                deleteQuietly(temp)
                return protectedPdf()
            } catch (e: IllegalArgumentException) {
                deleteQuietly(temp)
                return protectedPdf()
            }

            val adopted = synchronized(lock) {
                if (cleared) {
                    false
                } else {
                    pdfSource = source
                    true
                }
            }
            if (!adopted) {
                withContext(NonCancellable) { source.close() }
                throw CancellationException("The viewer was closed")
            }
            return ViewerContent.Pdf(pageCount = source.pageCount, source = source)
        } catch (t: Throwable) {
            deleteQuietly(temp)
            throw t
        }
    }

    private fun protectedPdf(): ViewerContent =
        ViewerContent.Unsupported(extensionLabel = "PDF", reason = UnsupportedReason.ProtectedPdf)

    private fun tempPdfName(fileName: String): String =
        fileName.removeSuffix(".${SecureFileStore.EXTENSION}") + "-" + newId().take(8) + ".pdf"

    private fun clearViewerCache() {
        val folder = File(context.cacheDir, VIEWER_CACHE_DIR)
        folder.listFiles()?.forEach { old -> deleteQuietly(old) }
    }

    private fun deleteQuietly(file: File?) {
        if (file == null) return
        try {
            file.delete()
        } catch (ignored: Exception) {
            // Removed the next time the viewer opens.
        }
    }

    // ---- Image ---------------------------------------------------------------------------

    private suspend fun loadImage(file: File, isJpeg: Boolean): ViewerContent {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        SecureFileStore.openInput(context, file).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ViewerContent.Error(MSG_GENERIC)
        currentCoroutineContext().ensureActive()

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = SecureFileStore.openInput(context, file).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return ViewerContent.Error(MSG_GENERIC)
        currentCoroutineContext().ensureActive()

        val upright = if (isJpeg) applyOrientation(decoded, file) else decoded
        return ViewerContent.Image(upright)
    }

    /** Turns a phone photo upright using its EXIF orientation. Shows it as decoded if that fails. */
    private fun applyOrientation(decoded: Bitmap, file: File): Bitmap {
        return try {
            val orientation = SecureFileStore.openInput(context, file).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
            val matrix = orientationMatrix(orientation) ?: return decoded
            val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (turned !== decoded) decoded.recycle()
            turned
        } catch (e: Exception) {
            decoded
        } catch (e: OutOfMemoryError) {
            decoded
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return null
        }
        return matrix
    }

    // ---- Leaving -------------------------------------------------------------------------

    override fun onCleared() {
        val source = synchronized(lock) {
            cleared = true
            val held = pdfSource
            pdfSource = null
            held
        }
        if (source == null) {
            cleanupScope.cancel()
        } else {
            // Takes the renderer's own lock first, so a page being drawn is never cut off.
            cleanupScope.launch { source.close() }.invokeOnCompletion { cleanupScope.cancel() }
        }
        super.onCleared()
    }
}

/**
 * One friendly sentence for a failure. A decryption failure is a `GeneralSecurityException`, or an
 * `IOException` with one in its cause chain, while reading an encrypted file.
 */
internal fun describeFailure(t: Throwable, isSecure: Boolean): String = when {
    t is FileNotFoundException -> MSG_MISSING
    isSecure && isDecryptionFailure(t) -> MSG_LOCKED
    else -> MSG_GENERIC
}

private fun isDecryptionFailure(t: Throwable): Boolean {
    if (t is GeneralSecurityException) return true
    if (t !is IOException) return false
    var cause: Throwable? = t.cause
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is GeneralSecurityException) return true
        cause = cause.cause
        depth++
    }
    return false
}
