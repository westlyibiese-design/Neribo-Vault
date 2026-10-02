package com.westly.neribovault.core.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.westly.neribovault.core.util.newId
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The outcome of saving a picked file into the app. */
sealed interface ImportResult {
    data class Saved(
        /** The new id; also the file's base name. */
        val id: String,
        /** Cleaned display name, including its extension. */
        val displayName: String,
        /** Plaintext bytes copied. */
        val sizeBytes: Long,
        /** `filesDir/documents/<id>.nvenc`. */
        val file: File,
    ) : ImportResult

    /** The file is bigger than the limit. Nothing was saved. */
    object TooLarge : ImportResult

    /** The file could not be saved. Nothing was left behind. */
    object Failed : ImportResult
}

private const val BUFFER_BYTES = 64 * 1024
private const val MAX_NAME_LENGTH = 120
private const val MAX_EXTENSION_LENGTH = 12
private const val FALLBACK_NAME = "File"

/**
 * Keeps files the owner adds to the Documents vault **encrypted on the phone**.
 *
 * Files are written to `filesDir/documents/<id>.nvenc` with `EncryptedFile` (AES-256-GCM, 4 KB
 * segments). Two facts about `EncryptedFile` shape everything here: it binds the file's **name**
 * into the encryption, so an encrypted file must never be renamed or written under a temporary
 * name; and the keys live in this app's private storage, so a backup has to carry plaintext copies
 * (inside the already encrypted backup file) for the files to survive cleared app data.
 *
 * Nothing here logs file names or content.
 */
object SecureFileStore {

    const val EXTENSION: String = "nvenc"

    /** 50 MB. */
    const val MAX_IMPORT_BYTES: Long = 52_428_800L

    private val keyLock = Any()

    @Volatile
    private var cachedKey: MasterKey? = null

    /** True when [file] is one of our encrypted files (its name ends with `.nvenc`). */
    fun isSecure(file: File): Boolean = file.name.endsWith(".$EXTENSION")

    /** `filesDir/documents`, created if it is missing. */
    fun documentsDir(context: Context): File {
        val folder = File(context.applicationContext.filesDir, "documents")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    /**
     * Copies the file behind [uri] into the app, encrypted, and returns where it went. Never leaves
     * a half-written file behind. A file bigger than [maxBytes] is refused (the size is enforced
     * while copying, because a provider's reported size can be missing or wrong).
     */
    suspend fun importFrom(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_IMPORT_BYTES,
    ): ImportResult = withContext(Dispatchers.IO) {
        importBlocking(context.applicationContext, uri, maxBytes)
    }

    /**
     * A stream of the file's real content. Decrypts a `.nvenc` file; any other file (the older
     * JPEG and PDF attachments) is read as it is. Blocking: call on `Dispatchers.IO`.
     */
    fun openInput(context: Context, file: File): InputStream =
        if (isSecure(file)) {
            encryptedFile(context, file).openFileInput()
        } else {
            FileInputStream(file)
        }

    /**
     * The size of the file's real content in bytes, or -1 when it cannot be read. For a `.nvenc`
     * file the content is decrypted once and counted (never held whole). Blocking.
     */
    fun plainSize(context: Context, file: File): Long {
        return try {
            if (!file.isFile) return -1L
            if (!isSecure(file)) return file.length()
            openInput(context, file).use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /** Streams the real content of [file] into [out]. Does **not** close [out]. */
    suspend fun decryptTo(context: Context, file: File, out: OutputStream) {
        withContext(Dispatchers.IO) {
            openInput(context, file).use { input -> copyStream(input, out) }
        }
    }

    /**
     * Streams [source] into [destFile] as an encrypted file. Does **not** close [source] (a backup
     * passes a zip stream that must stay open). An existing [destFile] is replaced. The file is
     * written straight to its final name; it is removed again if the copy fails.
     */
    suspend fun encryptTo(context: Context, source: InputStream, destFile: File) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            if (destFile.exists() && !destFile.delete()) throw IOException("Cannot replace the file")
            destFile.parentFile?.mkdirs()
            try {
                encryptedFile(appContext, destFile).openFileOutput().use { out ->
                    copyStream(source, out)
                }
            } catch (e: Exception) {
                runCatching { destFile.delete() }
                throw e
            }
        }
    }

    // ---- Import -------------------------------------------------------------------------

    private suspend fun importBlocking(context: Context, uri: Uri, maxBytes: Long): ImportResult {
        val id = newId()
        val dest = File(documentsDir(context), "$id.$EXTENSION")
        if (dest.exists()) return ImportResult.Failed
        return try {
            val resolver = context.contentResolver
            val reported = querySize(context, uri)
            if (reported != null && reported > maxBytes) return ImportResult.TooLarge
            val displayName = cleanDisplayName(queryDisplayName(context, uri), uri.lastPathSegment)

            val input = resolver.openInputStream(uri) ?: return ImportResult.Failed
            var total = 0L
            var tooLarge = false
            input.use { source ->
                encryptedFile(context, dest).openFileOutput().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            tooLarge = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                runCatching { dest.delete() }
                return ImportResult.TooLarge
            }

            // Prove the key can read what it just wrote. An empty file simply reads -1.
            openInput(context, dest).use { it.read() }
            ImportResult.Saved(id = id, displayName = displayName, sizeBytes = total, file = dest)
        } catch (e: CancellationException) {
            runCatching { dest.delete() }
            throw e
        } catch (e: Exception) {
            runCatching { dest.delete() }
            ImportResult.Failed
        }
    }

    /** The size the provider reports for [uri], or null when it does not say. */
    private fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver
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

    /** The display name the provider reports for [uri], or null when it does not say. */
    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                var result: String? = null
                if (cursor.moveToFirst()) {
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && !cursor.isNull(column)) result = cursor.getString(column)
                }
                result
            }
    }.getOrNull()

    // ---- Encryption ---------------------------------------------------------------------

    private fun masterKey(context: Context): MasterKey {
        cachedKey?.let { return it }
        return synchronized(keyLock) {
            cachedKey ?: MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                .also { cachedKey = it }
        }
    }

    private fun encryptedFile(context: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            context.applicationContext,
            file,
            masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /** Copies [input] to [output] in 64 KB pieces, stopping promptly when cancelled. */
    private suspend fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }
}

/**
 * Turns whatever the provider told us into a safe display name: [providerName] first, then the
 * last segment of [pathSegment], then "File". Slashes and control characters are removed, spaces
 * collapsed, and the length capped at 120 characters while keeping a short extension. Pure, so it
 * can be checked by reading.
 */
internal fun cleanDisplayName(providerName: String?, pathSegment: String?): String {
    val chosen = when {
        !providerName.isNullOrBlank() -> providerName
        !pathSegment.isNullOrBlank() -> pathSegment.substringAfterLast('/')
        else -> FALLBACK_NAME
    }
    val cleaned = chosen
        .filter { ch -> ch != '/' && ch != '\\' && !ch.isISOControl() }
        .replace(Regex(" {2,}"), " ")
        .trim()
    if (cleaned.isBlank()) return FALLBACK_NAME
    val capped = capNameLength(cleaned)
    return if (capped.isBlank()) FALLBACK_NAME else capped
}

/** Caps [name] at 120 characters. A short extension is kept and the part before the dot shrinks. */
private fun capNameLength(name: String): String {
    if (name.length <= MAX_NAME_LENGTH) return name
    val dot = name.lastIndexOf('.')
    if (dot > 0) {
        val extension = name.substring(dot)
        if (extension.length - 1 <= MAX_EXTENSION_LENGTH) {
            val room = MAX_NAME_LENGTH - extension.length
            return safeTake(name.substring(0, dot), room).trimEnd() + extension
        }
    }
    return safeTake(name, MAX_NAME_LENGTH).trimEnd()
}

/** The first [count] characters of [text], never ending on half of an emoji. */
private fun safeTake(text: String, count: Int): String {
    val taken = text.take(count)
    return if (taken.isNotEmpty() && taken.last().isHighSurrogate()) taken.dropLast(1) else taken
}
