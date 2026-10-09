package com.westly.neribovault.feature.backup

import android.content.Context
import android.database.Cursor
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.core.files.SecureFileStore
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.feature.accounts.security.AccountsBackup
import com.westly.neribovault.feature.developer.secrets.SecretsBackup
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Writes the encrypted backup. The data is streamed: rows go from the database cursor into the
 * zip, and files are copied in small pieces, so nothing large is ever held in memory.
 *
 * [write] is blocking and must run on one background thread (it holds a read transaction so the
 * backup is a consistent snapshot).
 */
class BackupWriter(
    private val context: Context,
    private val database: NeriboDatabase,
) {

    /**
     * Writes a complete backup to [output], encrypted with [password], and closes [output].
     * [progress] receives short status lines; [checkActive] should throw when the work is cancelled.
     */
    fun write(
        output: OutputStream,
        password: String,
        progress: (String) -> Unit,
        checkActive: () -> Unit,
    ): BackupSummary {
        val counted = CountingOutputStream(BufferedOutputStream(output))
        val db = database.openHelper.readableDatabase
        val tables = BackupFormat.backedUpTables(db)
        // Encrypted document files that cannot be read are left out here, before any zip entry for
        // them is started, so a bad file never leaves a half-written entry behind.
        val selection = try {
            selectFiles(progress, checkActive)
        } catch (e: Throwable) {
            // [output] is documented as closed by this function, also when the backup is cancelled.
            runCatching { output.close() }
            throw e
        }
        val files = selection.files
        val skippedFiles = selection.skipped
        val photoCount = files.getValue(BackupFormat.DIR_MEMORIES).size
        val documentCount = files.getValue(BackupFormat.DIR_DOCUMENTS).size

        progress("Securing your backup\u2026")
        val encrypted = BackupCrypto.encryptTo(counted, password)
        val counts = LinkedHashMap<String, Int>()
        // A deferred transaction that is never marked successful: it only pins a snapshot.
        db.beginTransactionNonExclusive()
        try {
            ZipOutputStream(BufferedOutputStream(encrypted)).use { zip ->
                for (table in tables) counts[table] = countRows(db, table)

                zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_MANIFEST))
                zip.write(
                    manifestJson(db, counts, photoCount, documentCount, skippedFiles)
                        .toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_DATA))
                writeData(db, tables, zip, progress, checkActive)
                zip.closeEntry()

                val secrets = SecretsBackup.exportState(context)
                if (secrets != null) {
                    zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_SECRETS))
                    zip.write(secrets.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                val accounts = AccountsBackup.exportState(context)
                if (accounts != null) {
                    zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_ACCOUNTS))
                    zip.write(accounts.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                copyFiles(zip, files, progress, checkActive)
                progress("Finishing\u2026")
            }
        } finally {
            db.endTransaction()
        }
        return BackupSummary(
            bytes = counted.count,
            tableCounts = counts,
            photoCount = photoCount,
            documentCount = documentCount,
        )
    }

    private fun manifestJson(
        db: SupportSQLiteDatabase,
        counts: Map<String, Int>,
        photoCount: Int,
        documentCount: Int,
        skippedFiles: Int,
    ): String {
        val tables = JSONObject()
        for ((table, count) in counts) tables.put(table, count)
        val fileCounts = JSONObject()
            .put(BackupFormat.DIR_MEMORIES, photoCount)
            .put(BackupFormat.DIR_DOCUMENTS, documentCount)
        return JSONObject()
            .put("formatVersion", BackupFormat.FORMAT_VERSION)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("databaseVersion", db.version)
            .put("createdAt", System.currentTimeMillis())
            .put("tables", tables)
            .put("files", fileCounts)
            .put("skippedFiles", skippedFiles)
            .toString()
    }

    /** Writes `{ "table": [ {row}, ... ], ... }` straight into [zip] without keeping rows around. */
    private fun writeData(
        db: SupportSQLiteDatabase,
        tables: List<String>,
        zip: ZipOutputStream,
        progress: (String) -> Unit,
        checkActive: () -> Unit,
    ) {
        val writer = JsonWriter(BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8)))
        writer.beginObject()
        for (table in tables) {
            checkActive()
            progress("Adding ${tableLabel(table).lowercase()}\u2026")
            writer.name(table)
            writer.beginArray()
            db.query("SELECT * FROM \"$table\"").use { cursor ->
                var written = 0
                while (cursor.moveToNext()) {
                    writeRow(writer, cursor)
                    written++
                    if (written % CHECK_EVERY_ROWS == 0) checkActive()
                }
            }
            writer.endArray()
        }
        writer.endObject()
        // Flush only. Closing the JsonWriter would close the zip stream under it.
        writer.flush()
    }

    private fun writeRow(writer: JsonWriter, cursor: Cursor) {
        writer.beginObject()
        for (i in 0 until cursor.columnCount) {
            writer.name(cursor.getColumnName(i))
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> {
                    val number = cursor.getDouble(i)
                    if (number.isNaN() || number.isInfinite()) writer.nullValue() else writer.value(number)
                }
                Cursor.FIELD_TYPE_STRING -> writer.value(cursor.getString(i))
                // No table of the app stores BLOBs; anything unexpected is written as null.
                else -> writer.nullValue()
            }
        }
        writer.endObject()
    }

    private fun copyFiles(
        zip: ZipOutputStream,
        files: Map<String, List<File>>,
        progress: (String) -> Unit,
        checkActive: () -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        for (dir in BackupFormat.FILE_DIRS) {
            val list = files.getValue(dir)
            if (list.isEmpty()) continue
            val label = if (dir == BackupFormat.DIR_MEMORIES) "photos" else "document files"
            list.forEachIndexed { index, file ->
                checkActive()
                progress("Adding $label (${index + 1} of ${list.size})\u2026")
                zip.putNextEntry(ZipEntry(BackupFormat.entryName(dir, file.name)))
                if (SecureFileStore.isSecure(file)) {
                    // The backup carries the readable content: the whole backup file is encrypted,
                    // and the phone's own key would be gone after clearing the app's data.
                    runBlocking { SecureFileStore.decryptTo(context, file, zip) }
                } else {
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    /** The files that will go into the backup, and how many encrypted files were left out. */
    private class FileSelection(val files: Map<String, List<File>>, val skipped: Int)

    /**
     * Lists the files of every folder. An encrypted (`.nvenc`) document file is checked by reading
     * it through once; one that cannot be decrypted is skipped and counted.
     */
    private fun selectFiles(progress: (String) -> Unit, checkActive: () -> Unit): FileSelection {
        var skipped = 0
        val selected = LinkedHashMap<String, List<File>>()
        for (dir in BackupFormat.FILE_DIRS) {
            val all = listFiles(dir)
            if (dir != BackupFormat.DIR_DOCUMENTS || all.none { SecureFileStore.isSecure(it) }) {
                selected[dir] = all
                continue
            }
            progress("Checking your document files\u2026")
            val readable = ArrayList<File>()
            for (file in all) {
                checkActive()
                if (SecureFileStore.isSecure(file) && SecureFileStore.plainSize(context, file) < 0L) {
                    skipped++
                } else {
                    readable.add(file)
                }
            }
            selected[dir] = readable
        }
        return FileSelection(selected, skipped)
    }

    /** The finished files of [dir] (half-written `.part` files are left out). */
    private fun listFiles(dir: String): List<File> {
        val folder = File(context.filesDir, dir)
        val all = folder.listFiles() ?: return emptyList()
        return all
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.name }
    }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
        const val CHECK_EVERY_ROWS = 500
    }
}
