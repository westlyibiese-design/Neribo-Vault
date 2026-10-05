package com.westly.neribovault.feature.backup

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.westly.neribovault.core.files.SecureFileStore
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.NoteDeleteTraceSql
import com.westly.neribovault.feature.developer.secrets.SecretsBackup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.Callable
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject

/** A backup could not be read or restored. [message] is fit to show to the owner. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A decrypted and checked backup waiting for the owner's confirmation. */
class PreparedBackup(
    val zipFile: File,
    val tableCounts: Map<String, Int>,
    val photoCount: Int,
    val documentCount: Int,
    val hasSecretsSetup: Boolean,
    val createdAt: Long,
    val databaseVersion: Int,
)

/** What a finished restore wants to tell the owner. An empty [warnings] means a clean restore. */
class RestoreReport(val warnings: List<String>)

/**
 * Reads and restores a `.nvbackup`. [prepare] decrypts to a temporary file and checks it without
 * changing anything. [restore] then replaces the database in one transaction and swaps the files
 * only after that transaction has committed.
 *
 * Both functions block and must run on a background thread.
 */
class BackupReader(
    private val context: Context,
    private val database: NeriboDatabase,
) {

    /**
     * Decrypts [input] with [password] into a temporary file, validates it and returns its summary.
     * Throws [BackupException] with a friendly message when the password is wrong, the file is
     * damaged or not a backup, or the backup is too new for this app.
     */
    fun prepare(input: InputStream, password: String): PreparedBackup {
        val temp = File(context.cacheDir, "$TEMP_PREFIX${newId()}$TEMP_SUFFIX")
        try {
            decryptToFile(input, password, temp)
            return validate(temp)
        } catch (e: BackupException) {
            temp.delete()
            throw e
        } catch (e: Exception) {
            temp.delete()
            throw BackupException(WRONG_PASSWORD_MESSAGE, e)
        }
    }

    /** Deletes the temporary file of [prepared] and any other leftover temporary restore files. */
    fun discard(prepared: PreparedBackup?) {
        prepared?.zipFile?.delete()
        deleteTempFiles()
    }

    /**
     * Replaces everything on this phone with the contents of [prepared].
     *
     * A [BackupException] thrown from here means **nothing was changed**. Problems after the
     * database was replaced (files or secrets) never throw; they come back as warnings in the
     * [RestoreReport].
     */
    fun restore(prepared: PreparedBackup, progress: (String) -> Unit): RestoreReport {
        val staging = File(context.filesDir, STAGING_DIR)
        val available = HashMap<String, Set<String>>()
        try {
            ZipFile(prepared.zipFile).use { zip ->
                progress("Preparing your files\u2026")
                deleteRecursively(staging)
                for (dir in BackupFormat.FILE_DIRS) {
                    available[dir] = stageFiles(zip, dir, File(staging, dir))
                }
                progress("Restoring your data\u2026")
                replaceDatabase(zip, available)
            }
        } catch (e: BackupException) {
            deleteRecursively(staging)
            throw e
        } catch (e: Exception) {
            deleteRecursively(staging)
            throw BackupException(NOTHING_CHANGED_MESSAGE, e)
        }

        // The database has been committed. From here on nothing may throw.
        val warnings = ArrayList<String>()
        progress("Restoring your files\u2026")
        for (dir in BackupFormat.FILE_DIRS) {
            if (!swapDirectory(dir, staging)) warnings.add(filesWarning(dir))
        }
        deleteRecursively(staging)

        progress("Finishing\u2026")
        val secretsJson = readSecretsEntry(prepared.zipFile)
        if (secretsJson != null) {
            try {
                SecretsBackup.importState(context, secretsJson)
            } catch (e: Exception) {
                warnings.add(
                    "Your Developer secrets were restored, but their PIN setup could not be. " +
                        "You may need to set up the secrets vault again.",
                )
            }
        }
        runCatching { database.openHelper.writableDatabase.execSQL("DELETE FROM ${BackupFormat.TABLE_TOMBSTONES}") }
        discard(prepared)
        return RestoreReport(warnings)
    }

    // ---- Prepare ------------------------------------------------------------------------

    private fun decryptToFile(input: InputStream, password: String, target: File) {
        try {
            BackupCrypto.decryptFrom(input, password).use { plain ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = plain.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: NotABackupException) {
            throw BackupException("That file is not a Neribo Vault backup.", e)
        } catch (e: IOException) {
            throw BackupException(WRONG_PASSWORD_MESSAGE, e)
        }
    }

    private fun validate(file: File): PreparedBackup {
        try {
            ZipFile(file).use { zip ->
                val manifestEntry = zip.getEntry(BackupFormat.ENTRY_MANIFEST)
                    ?: throw BackupException(WRONG_PASSWORD_MESSAGE)
                if (zip.getEntry(BackupFormat.ENTRY_DATA) == null) {
                    throw BackupException(WRONG_PASSWORD_MESSAGE)
                }
                val manifest = zip.getInputStream(manifestEntry).use { stream ->
                    JSONObject(stream.readBytes().toString(Charsets.UTF_8))
                }
                val format = manifest.optInt("formatVersion", -1)
                if (format > BackupFormat.FORMAT_VERSION) {
                    throw BackupException(
                        "This backup was made by a newer version of Neribo Vault. " +
                            "Update the app, then try again.",
                    )
                }
                if (format != BackupFormat.FORMAT_VERSION) throw BackupException(WRONG_PASSWORD_MESSAGE)
                val backupDb = manifest.optInt("databaseVersion", -1)
                val currentDb = database.openHelper.readableDatabase.version
                if (backupDb < 1) throw BackupException(WRONG_PASSWORD_MESSAGE)
                if (backupDb > currentDb) {
                    throw BackupException(
                        "This backup comes from a newer version of Neribo Vault than this phone has. " +
                            "Update the app, then try again.",
                    )
                }
                val counts = LinkedHashMap<String, Int>()
                val tables = manifest.optJSONObject("tables")
                if (tables != null) {
                    val keys = tables.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        counts[key] = tables.optInt(key, 0)
                    }
                }
                return PreparedBackup(
                    zipFile = file,
                    tableCounts = counts,
                    photoCount = countFiles(zip, BackupFormat.DIR_MEMORIES),
                    documentCount = countFiles(zip, BackupFormat.DIR_DOCUMENTS),
                    hasSecretsSetup = zip.getEntry(BackupFormat.ENTRY_SECRETS) != null,
                    createdAt = manifest.optLong("createdAt", 0L),
                    databaseVersion = backupDb,
                )
            }
        } catch (e: ZipException) {
            throw BackupException(WRONG_PASSWORD_MESSAGE, e)
        } catch (e: JSONException) {
            throw BackupException(WRONG_PASSWORD_MESSAGE, e)
        }
    }

    private fun countFiles(zip: ZipFile, dir: String): Int {
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && BackupFormat.fileNameInDir(entry.name, dir) != null) count++
        }
        return count
    }

    // ---- Restore: files -----------------------------------------------------------------

    /** Copies the backup's files of [dir] into [target] and returns their names. */
    private fun stageFiles(zip: ZipFile, dir: String, target: File): Set<String> {
        val names = HashSet<String>()
        if (!target.mkdirs() && !target.isDirectory) throw IOException("Cannot create staging folder")
        val buffer = ByteArray(BUFFER_BYTES)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = BackupFormat.fileNameInDir(entry.name, dir) ?: continue
            val staged = File(target, name)
            zip.getInputStream(entry).use { input ->
                if (dir == BackupFormat.DIR_DOCUMENTS && SecureFileStore.isSecure(staged)) {
                    // A backup holds the readable content of an encrypted file. Encrypt it again
                    // here, under the identical name (the name is part of the encryption), so it
                    // can later move into place without being renamed. The entry stream stays
                    // open and ends with this entry; a failure ends the restore with nothing
                    // changed, like any other file that cannot be copied.
                    runBlocking { SecureFileStore.encryptTo(context, input, staged) }
                } else {
                    staged.outputStream().use { out ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            names.add(name)
        }
        return names
    }

    /** Puts the staged folder in place of the live one. Returns false when that failed. */
    private fun swapDirectory(dir: String, staging: File): Boolean {
        val staged = File(staging, dir)
        val live = File(context.filesDir, dir)
        val old = File(context.filesDir, "$OLD_PREFIX$dir")
        deleteRecursively(old)
        if (live.exists() && !live.renameTo(old)) return false
        if (!staged.renameTo(live)) {
            // Put the previous folder back so the owner is no worse off.
            if (old.exists()) old.renameTo(live)
            return false
        }
        deleteRecursively(old)
        return true
    }

    private fun filesWarning(dir: String): String {
        val what = if (dir == BackupFormat.DIR_MEMORIES) "memory photos" else "document files"
        return "Your text and records were restored, but the $what could not be put back. " +
            "Try restoring the same backup again."
    }

    // ---- Restore: database --------------------------------------------------------------

    private fun replaceDatabase(zip: ZipFile, available: Map<String, Set<String>>) {
        val entry = zip.getEntry(BackupFormat.ENTRY_DATA) ?: throw BackupException(NOTHING_CHANGED_MESSAGE)
        database.runInTransaction(
            Callable {
                val db = database.openHelper.writableDatabase
                val tables = BackupFormat.backedUpTables(db)
                val columnsByTable = HashMap<String, Set<String>>()
                runCatching { NoteDeleteTraceSql.setReason(db, "Backup restore: cleared the tables before importing") }
                for (table in tables) {
                    columnsByTable[table] = BackupFormat.columnsOf(db, table).toSet()
                    db.execSQL("DELETE FROM \"$table\"")
                }
                val statements = HashMap<String, SupportSQLiteStatement>()
                try {
                    JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8)).use { reader ->
                        readTables(reader, db, columnsByTable, available, statements)
                    }
                } finally {
                    statements.values.forEach { runCatching { it.close() } }
                }
                // A restored database has no pending deletes to tell the cloud about.
                db.execSQL("DELETE FROM ${BackupFormat.TABLE_TOMBSTONES}")
                runCatching { NoteDeleteTraceSql.clearReason(db) }
                true
            },
        )
    }

    private fun readTables(
        reader: JsonReader,
        db: SupportSQLiteDatabase,
        columnsByTable: Map<String, Set<String>>,
        available: Map<String, Set<String>>,
        statements: MutableMap<String, SupportSQLiteStatement>,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val table = reader.nextName()
            val allowed = columnsByTable[table]
            if (allowed == null || reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                val row = readRow(reader, allowed)
                if (row.isEmpty()) continue
                insertRow(db, table, row, available, statements)
            }
            reader.endArray()
        }
        reader.endObject()
    }

    /** One row as column to value (Long, Double, String or null). Unknown columns are skipped. */
    private fun readRow(reader: JsonReader, allowed: Set<String>): LinkedHashMap<String, Any?> {
        val row = LinkedHashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val column = reader.nextName()
            if (column !in allowed) {
                reader.skipValue()
                continue
            }
            row[column] = readValue(reader)
        }
        reader.endObject()
        return row
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }
        JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1L else 0L
        JsonToken.NUMBER -> {
            val text = reader.nextString()
            text.toLongOrNull() ?: text.toDoubleOrNull() ?: throw IOException("Bad number in backup")
        }
        JsonToken.STRING -> reader.nextString()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun insertRow(
        db: SupportSQLiteDatabase,
        table: String,
        row: MutableMap<String, Any?>,
        available: Map<String, Set<String>>,
        statements: MutableMap<String, SupportSQLiteStatement>,
    ) {
        when (table) {
            "memories" -> {
                val raw = row["photoUris"]
                if (raw is String) {
                    row["photoUris"] = rewritePhotoPaths(
                        raw = raw,
                        available = available[BackupFormat.DIR_MEMORIES].orEmpty(),
                        directory = File(context.filesDir, BackupFormat.DIR_MEMORIES),
                    )
                }
            }
            "personal_documents" -> {
                if (row.containsKey("fileUri")) {
                    row["fileUri"] = rewriteFilePath(
                        raw = row["fileUri"] as? String,
                        available = available[BackupFormat.DIR_DOCUMENTS].orEmpty(),
                        directory = File(context.filesDir, BackupFormat.DIR_DOCUMENTS),
                    )
                }
            }
        }
        val columns = row.keys.toList()
        val sql = "INSERT OR REPLACE INTO \"$table\" (" +
            columns.joinToString(", ") { "\"$it\"" } +
            ") VALUES (" + columns.joinToString(", ") { "?" } + ")"
        val statement = statements.getOrPut(sql) { db.compileStatement(sql) }
        statement.clearBindings()
        columns.forEachIndexed { index, column ->
            val position = index + 1
            when (val value = row[column]) {
                null -> statement.bindNull(position)
                is Long -> statement.bindLong(position, value)
                is Double -> statement.bindDouble(position, value)
                else -> statement.bindString(position, value.toString())
            }
        }
        statement.executeInsert()
    }

    // ---- Small helpers ------------------------------------------------------------------

    private fun readSecretsEntry(zipFile: File): String? = try {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(BackupFormat.ENTRY_SECRETS)
            if (entry == null) {
                null
            } else {
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun deleteTempFiles() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(TEMP_SUFFIX)) file.delete()
        }
    }

    private fun deleteRecursively(file: File) {
        runCatching { file.deleteRecursively() }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
        const val TEMP_PREFIX = "restore-"
        const val TEMP_SUFFIX = ".zip"
        const val STAGING_DIR = ".restore_staging"
        const val OLD_PREFIX = ".restore_old_"
        const val WRONG_PASSWORD_MESSAGE = "Wrong password or damaged file."
        const val NOTHING_CHANGED_MESSAGE =
            "The restore could not be completed. Nothing on this phone was changed."
    }
}

private const val LIST_SEPARATOR = '\u001F'

/** The file name at the end of a stored path, whichever separator it used. */
internal fun fileNameOfPath(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\')

/**
 * Rewrites the stored photo paths (joined by U+001F) so they point into [directory] on this
 * phone, keeping each file's name. A path whose file is not in [available] is dropped.
 */
internal fun rewritePhotoPaths(raw: String, available: Set<String>, directory: File): String {
    if (raw.isEmpty()) return ""
    return raw.split(LIST_SEPARATOR)
        .map { fileNameOfPath(it) }
        .filter { it.isNotEmpty() && it in available }
        .joinToString(LIST_SEPARATOR.toString()) { File(directory, it).absolutePath }
}

/** The single-file version of [rewritePhotoPaths]: null when empty or when the file is missing. */
internal fun rewriteFilePath(raw: String?, available: Set<String>, directory: File): String? {
    if (raw.isNullOrEmpty()) return null
    val name = fileNameOfPath(raw)
    if (name.isEmpty() || name !in available) return null
    return File(directory, name).absolutePath
}
