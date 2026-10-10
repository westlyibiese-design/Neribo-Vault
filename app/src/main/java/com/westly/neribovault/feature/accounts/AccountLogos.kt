package com.westly.neribovault.feature.accounts

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom logos for "Other platform" accounts. A logo is a small PNG copied into the app's private
 * storage (`filesDir/logos/`); the database only keeps its relative path. It never leaves the
 * phone. Every function that touches a file is safe to call from a background thread and never
 * throws.
 */
object AccountLogos {
    private const val DIR = "logos"

    /** The longest side of a stored logo, in pixels. */
    const val MAX_SIZE_PX = 256

    /** The relative path of the saved logo of [accountId]. */
    fun finalPath(accountId: String): String = "$DIR/$accountId.png"

    /** A fresh relative path for a logo that is picked but not saved yet. */
    fun newPendingPath(accountId: String): String =
        "$DIR/$accountId-pending-${System.currentTimeMillis()}.png"

    fun fileFor(filesDir: File, relativePath: String): File = File(filesDir, relativePath)

    /**
     * Reads the picked image, shrinks it to at most [MAX_SIZE_PX] on its longest side and writes
     * it as PNG to [target]. Returns false when the image can't be read. Call off the main thread.
     */
    fun importImage(resolver: ContentResolver, uri: Uri, target: File): Boolean =
        try {
            importOrThrow(resolver, uri, target)
        } catch (e: Exception) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }

    private fun importOrThrow(resolver: ContentResolver, uri: Uri, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIZE_PX && bounds.outHeight / (sample * 2) >= MAX_SIZE_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return false

        val longest = max(decoded.width, decoded.height)
        val scaled: Bitmap = if (longest > MAX_SIZE_PX) {
            val factor = MAX_SIZE_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                max(1, (decoded.width * factor).roundToInt()),
                max(1, (decoded.height * factor).roundToInt()),
                true,
            )
        } else {
            decoded
        }
        try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            val written = FileOutputStream(temp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) {
                temp.delete()
                return false
            }
            if (target.exists()) target.delete()
            return temp.renameTo(target)
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** Moves a pending logo to the saved name of [accountId], replacing an older one. */
    fun commit(filesDir: File, pendingPath: String, accountId: String): Boolean =
        try {
            val source = File(filesDir, pendingPath)
            val destination = File(filesDir, finalPath(accountId))
            if (!source.exists()) {
                false
            } else {
                if (destination.exists()) destination.delete()
                source.renameTo(destination)
            }
        } catch (e: Exception) {
            false
        }

    /** Copies a saved logo for a duplicated account. Returns the new relative path, or null. */
    fun copy(filesDir: File, relativePath: String, newAccountId: String): String? =
        try {
            val source = File(filesDir, relativePath)
            if (!source.exists()) {
                null
            } else {
                source.copyTo(File(filesDir, finalPath(newAccountId)), overwrite = true)
                finalPath(newAccountId)
            }
        } catch (e: Exception) {
            null
        }

    /** Deletes one logo file. */
    fun delete(filesDir: File, relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        try {
            File(filesDir, relativePath).delete()
        } catch (e: Exception) {
            // Nothing to do: a logo that can't be deleted is only a small leftover file.
        }
    }

    /** Deletes every unsaved leftover logo of [accountId]. */
    fun clearPending(filesDir: File, accountId: String) {
        try {
            File(filesDir, DIR).listFiles()?.forEach {
                if (it.name.startsWith("$accountId-pending-")) it.delete()
            }
        } catch (e: Exception) {
            // Same as [delete].
        }
    }
}
