package com.westly.neribovault.feature.memories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.westly.neribovault.core.util.newId
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The longest edge, in pixels, a stored photo can have. */
private const val MAX_EDGE_PX = 2048

/** JPEG quality used for stored photos. */
private const val JPEG_QUALITY = 85

/**
 * Copies picked photos into the app's private storage (`filesDir/memories/`), shrunk and
 * re-encoded as JPEG, so a memory keeps its pictures even if the originals leave the gallery.
 * Every file operation here runs off the main thread when called from the suspend functions;
 * [delete] and [fileExists] are plain functions that must also be called from a background
 * context.
 */
class MemoryPhotoStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val directory: File
        get() = File(appContext.filesDir, "memories")

    /**
     * Decodes [uri] (long edge at most 2048px, EXIF rotation applied), saves it as a JPEG and
     * returns the absolute file path to store in `MemoryEntity.photoUris`. Returns null when the
     * image cannot be read; a corrupt image never crashes the app.
     */
    suspend fun importPhoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            importBlocking(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Deletes one photo file. Anything outside `filesDir/memories/` is ignored. */
    fun delete(path: String) {
        val file = File(path)
        if (isInsideStore(file)) {
            runCatching { file.delete() }
        }
    }

    /** True when [path] points at an existing file. */
    fun fileExists(path: String): Boolean = runCatching { File(path).isFile }.getOrDefault(false)

    /** Deletes every file in [paths] (see [delete]). */
    suspend fun deleteFilesFor(paths: List<String>) {
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

    private fun importBlocking(uri: Uri): String? {
        val resolver = appContext.contentResolver

        // 1. Read only the size, so a huge photo is never decoded at full size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2. Decode at the smallest power-of-two sample that keeps the long edge within the cap.
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > MAX_EDGE_PX) sample *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        // 3. Honour the camera's rotation, then flatten any transparency onto white.
        val oriented = applyOrientation(decoded, readOrientation(uri))
        val flattened = flattenOnWhite(oriented)

        // 4. Write to a temporary name first so a half-written file is never kept.
        val folder = directory
        if (!folder.exists() && !folder.mkdirs() && !folder.exists()) {
            flattened.recycle()
            return null
        }
        val id = newId()
        val target = File(folder, "$id.jpg")
        val partial = File(folder, "$id.jpg.part")
        val written = try {
            FileOutputStream(partial).use { out ->
                flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            false
        } finally {
            flattened.recycle()
        }
        if (!written || !partial.renameTo(target)) {
            partial.delete()
            return null
        }
        return target.absolutePath
    }

    /**
     * Reads the EXIF orientation (1 to 8) using the framework's ExifInterface, which reads from
     * a stream on minSdk 24 and needs no extra library. Falls back to 1 (upright) on any error.
     */
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
