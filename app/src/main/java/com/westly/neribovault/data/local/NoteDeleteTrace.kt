package com.westly.neribovault.data.local

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line of the note trace, shown on Settings > Diagnostics. */
data class NoteTraceEntry(
    /** Position in the trace; unique, so it is safe to use as a list key. */
    val seq: Long,
    val at: Long,
    /** DELETED (row removed), TRASHED (moved to Recently deleted) or PROTECTED (a cloud delete was ignored). */
    val event: String,
    val noteId: String,
    val title: String,
    val bodyLength: Int,
    val reason: String,
    /** Seconds between the last finished cloud sync and this event, or null when no sync has run yet. */
    val secondsSinceSync: Long?,
) {
    fun asLine(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(at))
        val shownTitle = if (title.isBlank()) "(no title)" else "\"$title\""
        val sync = secondsSinceSync?.let { "$it s after the last sync" } ?: "no sync has run yet"
        return "$time | $event | $shownTitle, $bodyLength characters | $reason | $sync | id $noteId"
    }
}

/**
 * SQL for the note trace. Two small tables and two triggers record every note that is removed
 * or moved to the trash, whatever code did it. Code that knows why it is deleting a note says so
 * through [setReason] first; anything else is logged as "Unattributed". The trace stays on the
 * phone: it is not synced and not part of backups.
 */
object NoteDeleteTraceSql {
    const val TABLE = "note_delete_trace"
    const val CONTEXT_TABLE = "note_trace_context"
    private const val KEEP = 300

    private const val NOW_MS = "CAST(strftime('%s','now') AS INTEGER) * 1000"
    private const val REASON =
        "COALESCE((SELECT reason FROM note_trace_context WHERE id = 1), 'Unattributed: not from a known code path')"
    private const val SINCE_SYNC =
        "(SELECT CASE WHEN lastSyncAt IS NULL THEN NULL ELSE ($NOW_MS - lastSyncAt) / 1000 END " +
            "FROM note_trace_context WHERE id = 1)"
    private const val PRUNE =
        "DELETE FROM note_delete_trace WHERE seq <= (SELECT MAX(seq) FROM note_delete_trace) - $KEEP;"

    private fun logRow(event: String, row: String): String =
        "INSERT INTO note_delete_trace(at, event, noteId, title, bodyLength, reason, sinceSyncSeconds) " +
            "VALUES ($NOW_MS, '$event', $row.id, COALESCE($row.title, ''), length(COALESCE($row.body, '')), $REASON, $SINCE_SYNC);"

    /** Safe to run on every database open. */
    val statements: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS note_delete_trace (" +
            "seq INTEGER PRIMARY KEY, at INTEGER NOT NULL, event TEXT NOT NULL, noteId TEXT NOT NULL, " +
            "title TEXT NOT NULL, bodyLength INTEGER NOT NULL, reason TEXT NOT NULL, sinceSyncSeconds INTEGER)",
        "CREATE TABLE IF NOT EXISTS note_trace_context (id INTEGER PRIMARY KEY, reason TEXT, lastSyncAt INTEGER)",
        "INSERT OR IGNORE INTO note_trace_context(id, reason, lastSyncAt) VALUES (1, NULL, NULL)",
        "CREATE TRIGGER IF NOT EXISTS trace_note_deleted AFTER DELETE ON notes BEGIN ${logRow("DELETED", "OLD")} $PRUNE END;",
        "CREATE TRIGGER IF NOT EXISTS trace_note_trashed AFTER UPDATE OF isDeleted ON notes " +
            "WHEN OLD.isDeleted = 0 AND NEW.isDeleted = 1 BEGIN ${logRow("TRASHED", "NEW")} $PRUNE END;",
    )

    /** Creates the trace. Never throws: tracing must not be able to stop the app from opening. */
    fun install(db: SupportSQLiteDatabase) {
        statements.forEach { sql -> runCatching { db.execSQL(sql) } }
    }

    /** Names the reason for the note deletes that follow, until [clearReason]. */
    fun setReason(db: SupportSQLiteDatabase, reason: String) {
        db.execSQL("UPDATE note_trace_context SET reason = ? WHERE id = 1", arrayOf<Any?>(reason))
    }

    fun clearReason(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE note_trace_context SET reason = NULL WHERE id = 1")
    }

    /** Remembers when a cloud sync last finished, so each entry can show how long after it happened. */
    fun recordSync(db: SupportSQLiteDatabase, finishedAt: Long) {
        db.execSQL("UPDATE note_trace_context SET lastSyncAt = ? WHERE id = 1", arrayOf<Any?>(finishedAt))
    }

    /** Logs that a cloud delete marker was ignored because the local note is newer. */
    fun logProtected(db: SupportSQLiteDatabase, noteId: String, reason: String) {
        db.execSQL(
            "INSERT INTO note_delete_trace(at, event, noteId, title, bodyLength, reason, sinceSyncSeconds) " +
                "SELECT $NOW_MS, 'PROTECTED', id, COALESCE(title, ''), length(COALESCE(body, '')), ?, $SINCE_SYNC " +
                "FROM notes WHERE id = ?",
            arrayOf<Any?>(reason, noteId),
        )
    }
}

/** Reads and manages the note trace, and tags note deletes made through the repositories. */
class NoteDeleteTrace(private val database: RoomDatabase) {

    /**
     * Runs [block] in one transaction in which every note it deletes or trashes is logged with
     * [reason]. If the trace itself fails, the delete still goes ahead.
     */
    suspend fun <T> because(reason: String, block: suspend () -> T): T =
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            runCatching { NoteDeleteTraceSql.setReason(db, reason) }
            try {
                block()
            } finally {
                runCatching { NoteDeleteTraceSql.clearReason(db) }
            }
        }

    /** Newest first. */
    suspend fun recent(limit: Int = 100): List<NoteTraceEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val db = database.openHelper.readableDatabase
            val entries = ArrayList<NoteTraceEntry>()
            db.query(
                "SELECT seq, at, event, noteId, title, bodyLength, reason, sinceSyncSeconds " +
                    "FROM note_delete_trace ORDER BY seq DESC LIMIT $limit",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(
                        NoteTraceEntry(
                            seq = cursor.getLong(0),
                            at = cursor.getLong(1),
                            event = cursor.getString(2),
                            noteId = cursor.getString(3),
                            title = cursor.getString(4),
                            bodyLength = cursor.getInt(5),
                            reason = cursor.getString(6),
                            secondsSinceSync = if (cursor.isNull(7)) null else cursor.getLong(7),
                        ),
                    )
                }
            }
            entries
        }.getOrDefault(emptyList())
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching { database.openHelper.writableDatabase.execSQL("DELETE FROM note_delete_trace") }
        }
    }
}
