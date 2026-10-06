package com.westly.neribovault.data.cloud

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.NoteDeleteTraceSql
import java.util.concurrent.Callable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the sync engine is doing right now. */
sealed interface SyncState {
    object Idle : SyncState

    data class Syncing(val progress: String) : SyncState

    data class Success(val at: Long) : SyncState

    data class Error(val message: String) : SyncState
}

/** The outcome of one [SyncEngine.syncNow] call. */
sealed interface SyncResult {
    data class Success(val pulled: Int, val pushed: Int, val at: Long) : SyncResult

    data class Failed(val message: String, val retryable: Boolean) : SyncResult

    /** Nobody is signed in, so nothing was sent anywhere. */
    object NotSignedIn : SyncResult

    /** Another sync (or a restore) already holds the engine. */
    object AlreadyRunning : SyncResult
}

internal data class SyncColumn(val name: String, val type: String, val notNull: Boolean)

internal class RemoteRow(val id: String, val updatedAt: Long, val data: JSONObject) {
    /** A permanent delete made on another device carries no `updatedAt` in its data. */
    val isTombstone: Boolean get() = !data.has("updatedAt")
}

internal class LocalRow(val id: String, val updatedAt: Long, val data: JSONObject)

/**
 * Two-way, last-write-wins sync of every enabled vault table with the owner's Supabase project.
 *
 * Each table is stored remotely as JSON in `vault_items` and read and written locally with raw
 * SQL, column by column, so there is no per-entity mapping code. For each enabled table the
 * engine pulls first, then pushes. Nothing here logs tokens, URLs or row contents, and no network
 * request is made unless a user is signed in.
 */
class SyncEngine(
    context: Context,
    private val database: NeriboDatabase,
    private val auth: CloudAuth,
) {
    private val appContext = context.applicationContext
    private val config: CloudConfigStore get() = auth.config
    private val api: SupabaseApi get() = auth.api
    private val mutex = Mutex()
    private val columnCache = HashMap<String, List<SyncColumn>>()

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Observable progress for the UI. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** True while a sync (or a [withSyncPaused] block) is running. */
    val isBusy: Boolean get() = mutex.isLocked

    /**
     * Holds the engine while [block] runs, so no sync can start meanwhile. For restore or backup
     * work that must not race with sync. A sync requested during [block] returns
     * [SyncResult.AlreadyRunning].
     */
    suspend fun <T> withSyncPaused(block: suspend () -> T): T = mutex.withLock { block() }

    /** Forgets an old error or success message, for example after signing out. */
    fun clearStatus() {
        if (_state.value !is SyncState.Syncing) _state.value = SyncState.Idle
    }

    /** Runs one full sync. Safe to call from anywhere; two syncs never run at once. */
    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        if (!config.isSignedIn()) return@withContext SyncResult.NotSignedIn
        if (!mutex.tryLock()) return@withContext SyncResult.AlreadyRunning
        try {
            runSync()
        } finally {
            mutex.unlock()
            if (_state.value is SyncState.Syncing) _state.value = SyncState.Idle
        }
    }

    private suspend fun runSync(): SyncResult {
        if (!isOnline(appContext)) return fail(SupabaseApi.OFFLINE_MESSAGE, retryable = true)
        return try {
            var pulled = 0
            var pushed = 0
            for (table in SyncTables.ALL) {
                if (!config.vaultEnabled(table.vaultId)) continue
                _state.value = SyncState.Syncing("Syncing ${SyncTables.vaultLabel(table.vaultId)}\u2026")
                pulled += pullTable(table)
                pushed += pushTable(table.name)
            }
            val now = System.currentTimeMillis()
            config.setLastSyncAt(now)
            runCatching { NoteDeleteTraceSql.recordSync(database.openHelper.writableDatabase, now) }
            _state.value = SyncState.Success(now)
            SyncResult.Success(pulled, pushed, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            fail(e.message.orEmpty(), e.retryable)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed (${e.javaClass.simpleName})")
            fail("Sync stopped unexpectedly. Your data on this phone is safe. Try again in a moment.", retryable = true)
        }
    }

    private fun fail(message: String, retryable: Boolean): SyncResult {
        _state.value = SyncState.Error(message)
        return SyncResult.Failed(message, retryable)
    }

    // ---- Pull ----------------------------------------------------------------------------

    private suspend fun pullTable(table: SyncTable): Int {
        val name = table.name
        val db = database.openHelper.writableDatabase
        val columns = tableColumns(db, name)
        val startCursor = config.pullCursor(name)

        val remote = ArrayList<RemoteRow>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = auth.withToken { token -> api.fetchRows(token, name, startCursor, offset, PULL_PAGE_SIZE) }
            for (i in 0 until page.length()) {
                parseRemoteRow(page.optJSONObject(i))?.let { remote.add(it) }
            }
            if (page.length() < PULL_PAGE_SIZE) break
            offset += PULL_PAGE_SIZE
        }
        if (remote.isEmpty()) return 0

        guardAgainstBadRemote(db, table, remote)

        val chunks = remote.chunked(PULL_PAGE_SIZE)
        val overallMax = remote.maxOf { it.updatedAt }
        var applied = 0
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            applied += database.runInTransaction(Callable { applyChunk(db, name, columns, chunk) })
            // Only after the page is committed. Earlier pages stop one short so rows that share
            // the last timestamp are never skipped if the run is interrupted.
            val cursor = if (index == chunks.lastIndex) overallMax else chunk.maxOf { it.updatedAt } - 1
            config.advancePullCursor(name, cursor)
        }
        return applied
    }

    private fun parseRemoteRow(obj: JSONObject?): RemoteRow? {
        if (obj == null) return null
        val id = obj.stringOrNull("id") ?: return null
        val data = obj.optJSONObject("data") ?: return null
        return RemoteRow(id, obj.optLong("updated_at", 0L), data)
    }

    /** Stops a pull that would overwrite or remove a large share of what is on the phone. */
    private fun guardAgainstBadRemote(db: SupportSQLiteDatabase, table: SyncTable, remote: List<RemoteRow>) {
        var impact = 0
        for (row in remote) {
            val local = localUpdatedAt(db, table.name, row.id) ?: continue
            if (row.isTombstone || row.updatedAt > local) impact++
        }
        if (impact < GUARD_MIN_ROWS) return
        val localCount = countRows(db, table.name)
        if (impact * 100L > localCount * GUARD_PERCENT) {
            throw CloudException(
                CloudErrorKind.SafetyGuard,
                "Sync stopped to protect your data: the cloud copy would change more than " +
                    "$GUARD_PERCENT% of your ${SyncTables.vaultLabel(table.vaultId)} on this phone. " +
                    "Nothing was changed. Check your Supabase project, then try again.",
            )
        }
    }

    private fun applyChunk(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<SyncColumn>,
        rows: List<RemoteRow>,
    ): Int {
        var applied = 0
        for (row in rows) applied += applyRow(db, table, columns, row)
        return applied
    }

    /** Applies one remote row. Returns 1 when the local table changed, otherwise 0. */
    private fun applyRow(db: SupportSQLiteDatabase, table: String, columns: List<SyncColumn>, row: RemoteRow): Int {
        // A document saved as an encrypted file never takes part in sync, in either direction.
        if (isPrivateFileDocument(db, table, row)) return 0
        val localUpdatedAt = localUpdatedAt(db, table, row.id)

        if (row.isTombstone) {
            if (localUpdatedAt == null) return 0
            // A delete marker only wins over a row that has not been changed since the marker was
            // made. Marker times are cut down to whole seconds, so a row saved in the same second as
            // its marker counts as newer: a marker for a row that still exists is never a real delete.
            if (localUpdatedAt >= row.updatedAt) {
                if (table == NOTES_TABLE) {
                    runCatching {
                        NoteDeleteTraceSql.logProtected(
                            db,
                            row.id,
                            "Cloud sync: ignored a delete marker because the note on this phone is newer",
                        )
                    }
                }
                // The cloud now holds only the marker, so send this newer row up to replace it.
                db.execSQL(
                    "UPDATE \"$table\" SET updatedAt = ? WHERE id = ?",
                    arrayOf<Any?>(System.currentTimeMillis(), row.id),
                )
                return 0
            }
            if (table == NOTES_TABLE) {
                runCatching { NoteDeleteTraceSql.setReason(db, "Cloud sync: applied a delete marker from the cloud") }
            }
            db.execSQL("DELETE FROM \"$table\" WHERE id = ?", arrayOf<Any?>(row.id))
            if (table == NOTES_TABLE) runCatching { NoteDeleteTraceSql.clearReason(db) }
            // The delete above just made a tombstone of its own; it must not be sent back.
            db.execSQL("DELETE FROM sync_tombstones WHERE kind = ? AND rowId = ?", arrayOf<Any?>(table, row.id))
            return 1
        }

        if (localUpdatedAt != null && row.updatedAt <= localUpdatedAt) return 0
        if (localUpdatedAt == null) {
            // A newer local permanent delete beats an older remote copy; it will be pushed.
            val deletedAt = tombstoneDeletedAt(db, table, row.id)
            if (deletedAt != null && deletedAt >= row.updatedAt) return 0
        }

        val deviceLocal = SyncTables.deviceLocalColumn(table)
        val localDeviceValue = if (localUpdatedAt != null && deviceLocal != null) {
            readTextColumn(db, table, deviceLocal.column, row.id)
        } else {
            null
        }
        val values = buildValues(columns, row, deviceLocal, localDeviceValue, localUpdatedAt != null)
            ?: return 0

        val columnList = columns.joinToString(", ") { "\"${it.name}\"" }
        val placeholders = columns.joinToString(", ") { "?" }
        db.execSQL("INSERT OR REPLACE INTO \"$table\" ($columnList) VALUES ($placeholders)", values)

        // A live row now exists, so any delete marker for it is stale.
        db.execSQL("DELETE FROM sync_tombstones WHERE kind = ? AND rowId = ?", arrayOf<Any?>(table, row.id))
        return 1
    }

    /**
     * Builds the bind values in table-column order, keeping only columns that really exist in the
     * table. Returns null when a required column is missing, so a malformed row is skipped.
     */
    private fun buildValues(
        columns: List<SyncColumn>,
        row: RemoteRow,
        deviceLocal: DeviceLocalColumn?,
        localDeviceValue: String?,
        localExists: Boolean,
    ): Array<Any?>? {
        val values = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { index, column ->
            val value: Any? = when {
                column.name == "id" -> row.id
                deviceLocal != null && column.name == deviceLocal.column ->
                    if (localExists) localDeviceValue else deviceLocal.emptyValue
                row.data.has(column.name) -> toBindValue(row.data.opt(column.name), column.type)
                else -> null
            }
            if (value == null && column.notNull) return null
            values[index] = value
        }
        return values
    }

    private fun toBindValue(raw: Any?, columnType: String): Any? {
        if (raw == null || raw === JSONObject.NULL) return null
        val wantsInteger = columnType.contains("INT", ignoreCase = true)
        return when (raw) {
            is Boolean -> if (raw) 1L else 0L
            is Int -> raw.toLong()
            is Long -> raw
            is Double -> if (wantsInteger && raw == Math.rint(raw)) raw.toLong() else raw
            is String -> raw
            else -> raw.toString()
        }
    }

    // ---- Push ----------------------------------------------------------------------------

    private suspend fun pushTable(table: String): Int {
        val db = database.openHelper.writableDatabase
        val columns = tableColumns(db, table)
        val hasIsDeleted = columns.any { it.name == "isDeleted" }
        val deviceLocal = SyncTables.deviceLocalColumn(table)

        var lastTimestamp = config.pushCursor(table)
        var lastId: String? = null
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = readLocalPage(db, table, lastTimestamp, lastId)
            if (page.isEmpty()) break
            for (chunk in page.chunked(PUSH_BATCH_SIZE)) {
                val payload = JSONArray()
                chunk.forEach { payload.put(toEnvelope(table, it, hasIsDeleted, deviceLocal)) }
                auth.withToken { token -> api.upsertRows(token, payload) }
                // Stay one short: rows sharing the last timestamp may not all be sent yet.
                config.advancePushCursor(table, chunk.last().updatedAt - 1)
            }
            lastTimestamp = page.last().updatedAt
            lastId = page.last().id
            total += page.size
            if (page.size < PUSH_PAGE_SIZE) break
        }
        if (total > 0) config.advancePushCursor(table, lastTimestamp)
        return total + pushTombstones(db, table)
    }

    private fun readLocalPage(db: SupportSQLiteDatabase, table: String, timestamp: Long, afterId: String?): List<LocalRow> {
        val sql: String
        val args: Array<Any?>
        // Documents saved as encrypted files (fileUri ends in .nvenc) are never uploaded.
        val privateFilter = if (table == PERSONAL_DOCUMENTS_TABLE) PRIVATE_FILE_FILTER else ""
        if (afterId == null) {
            sql = "SELECT * FROM \"$table\" WHERE updatedAt > ?$privateFilter ORDER BY updatedAt, id LIMIT $PUSH_PAGE_SIZE"
            args = arrayOf<Any?>(timestamp)
        } else {
            sql = "SELECT * FROM \"$table\" WHERE (updatedAt > ? OR (updatedAt = ? AND id > ?))$privateFilter " +
                "ORDER BY updatedAt, id LIMIT $PUSH_PAGE_SIZE"
            args = arrayOf<Any?>(timestamp, timestamp, afterId)
        }
        return db.query(sql, args).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val timeIndex = cursor.getColumnIndexOrThrow("updatedAt")
            val rows = ArrayList<LocalRow>()
            while (cursor.moveToNext()) {
                rows.add(LocalRow(cursor.getString(idIndex), cursor.getLong(timeIndex), cursorToJson(cursor)))
            }
            rows
        }
    }

    private fun cursorToJson(cursor: Cursor): JSONObject {
        val json = JSONObject()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> json.put(name, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> json.put(name, cursor.getDouble(i))
                Cursor.FIELD_TYPE_STRING -> json.put(name, cursor.getString(i))
                else -> json.put(name, JSONObject.NULL)
            }
        }
        return json
    }

    private fun toEnvelope(table: String, row: LocalRow, hasIsDeleted: Boolean, deviceLocal: DeviceLocalColumn?): JSONObject {
        val data = row.data
        if (deviceLocal != null) {
            // File paths belong to this device only: send an empty value instead.
            data.put(deviceLocal.column, deviceLocal.emptyValue ?: JSONObject.NULL)
        }
        val isDeleted = hasIsDeleted && data.optLong("isDeleted", 0L) != 0L
        return JSONObject()
            .put("kind", table)
            .put("id", row.id)
            .put("updated_at", row.updatedAt)
            .put("is_deleted", isDeleted)
            .put("data", data)
    }

    private suspend fun pushTombstones(db: SupportSQLiteDatabase, table: String): Int {
        val pending = readTombstones(db, table)
        var count = 0
        for (chunk in pending.chunked(PUSH_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            val payload = JSONArray()
            chunk.forEach { (rowId, deletedAt) ->
                payload.put(
                    JSONObject()
                        .put("kind", table)
                        .put("id", rowId)
                        .put("updated_at", deletedAt)
                        .put("is_deleted", true)
                        .put("data", JSONObject().put("id", rowId)),
                )
            }
            auth.withToken { token -> api.upsertRows(token, payload) }
            chunk.forEach { (rowId, deletedAt) ->
                db.execSQL(
                    "DELETE FROM sync_tombstones WHERE kind = ? AND rowId = ? AND deletedAt = ?",
                    arrayOf<Any?>(table, rowId, deletedAt),
                )
            }
            count += chunk.size
        }
        return count
    }

    private fun readTombstones(db: SupportSQLiteDatabase, table: String): List<Pair<String, Long>> =
        db.query(
            "SELECT rowId, deletedAt FROM sync_tombstones WHERE kind = ? " +
                "AND NOT EXISTS (SELECT 1 FROM \"$table\" WHERE id = sync_tombstones.rowId) " +
                "ORDER BY deletedAt, rowId",
            arrayOf<Any?>(table),
        ).use { cursor ->
            val rows = ArrayList<Pair<String, Long>>()
            while (cursor.moveToNext()) rows.add(cursor.getString(0) to cursor.getLong(1))
            rows
        }

    // ---- Small SQL helpers ---------------------------------------------------------------

    /** Reads the table's columns once. Only these names are ever accepted from remote data. */
    private fun tableColumns(db: SupportSQLiteDatabase, table: String): List<SyncColumn> =
        columnCache.getOrPut(table) {
            db.query("PRAGMA table_info(\"$table\")").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val columns = ArrayList<SyncColumn>()
                while (cursor.moveToNext()) {
                    columns.add(
                        SyncColumn(
                            name = cursor.getString(nameIndex),
                            type = cursor.getString(typeIndex).orEmpty(),
                            notNull = cursor.getInt(notNullIndex) == 1,
                        ),
                    )
                }
                columns
            }
        }

    private fun localUpdatedAt(db: SupportSQLiteDatabase, table: String, id: String): Long? =
        db.query("SELECT updatedAt FROM \"$table\" WHERE id = ?", arrayOf<Any?>(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun tombstoneDeletedAt(db: SupportSQLiteDatabase, table: String, id: String): Long? =
        db.query(
            "SELECT deletedAt FROM sync_tombstones WHERE kind = ? AND rowId = ?",
            arrayOf<Any?>(table, id),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun readTextColumn(db: SupportSQLiteDatabase, table: String, column: String, id: String): String? =
        db.query("SELECT \"$column\" FROM \"$table\" WHERE id = ?", arrayOf<Any?>(id)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    /**
     * True for a personal document that is saved as an encrypted file (its `fileUri` ends in
     * `.nvenc`), whether the remote row or the local row says so. Such documents are left out of
     * every download; [PRIVATE_FILE_FILTER] leaves them out of every upload.
     */
    private fun isPrivateFileDocument(db: SupportSQLiteDatabase, table: String, row: RemoteRow): Boolean {
        if (table != PERSONAL_DOCUMENTS_TABLE) return false
        if (row.data.optString("fileUri", "").endsWith(PRIVATE_FILE_SUFFIX)) return true
        return readTextColumn(db, table, "fileUri", row.id).orEmpty().endsWith(PRIVATE_FILE_SUFFIX)
    }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM \"$table\"").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private companion object {
        const val TAG = "SyncEngine"
        const val PULL_PAGE_SIZE = 500
        const val PUSH_PAGE_SIZE = 200
        const val PUSH_BATCH_SIZE = 100
        const val GUARD_MIN_ROWS = 10
        const val GUARD_PERCENT = 30
        const val NOTES_TABLE = "notes"
        const val PERSONAL_DOCUMENTS_TABLE = "personal_documents"
        const val PRIVATE_FILE_SUFFIX = ".nvenc"
        const val PRIVATE_FILE_FILTER = " AND (fileUri IS NULL OR fileUri NOT LIKE '%.nvenc')"
    }
}
