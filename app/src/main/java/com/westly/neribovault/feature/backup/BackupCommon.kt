package com.westly.neribovault.feature.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import com.westly.neribovault.data.local.NoteDeleteTraceSql
import java.io.FilterOutputStream
import java.io.OutputStream

/** Names and limits shared by the backup writer and reader. */
internal object BackupFormat {
    const val FORMAT_VERSION = 1
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_DATA = "data.json"
    const val ENTRY_SECRETS = "secrets_state.json"
    const val ENTRY_ACCOUNTS = "accounts_state.json"
    const val DIR_MEMORIES = "memories"
    const val DIR_DOCUMENTS = "documents"
    const val FILES_PREFIX = "files/"

    /** The only table that is never backed up or restored: pending sync deletes. */
    const val TABLE_TOMBSTONES = "sync_tombstones"

    /** The two folders of user files that travel with a backup. */
    val FILE_DIRS: List<String> = listOf(DIR_MEMORIES, DIR_DOCUMENTS)

    /** Zip entry name of the file [name] in [dir]. */
    fun entryName(dir: String, name: String): String = "$FILES_PREFIX$dir/$name"

    /**
     * The file name of a zip entry inside [dir], or null when the entry is not a plain file of
     * that folder. Anything with a slash, a backslash or `..` is refused.
     */
    fun fileNameInDir(entryName: String, dir: String): String? {
        val prefix = "$FILES_PREFIX$dir/"
        if (!entryName.startsWith(prefix)) return null
        val name = entryName.substring(prefix.length)
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return name
    }

    /** Every table of the database that a backup contains, sorted by name. */
    fun backedUpTables(db: SupportSQLiteDatabase): List<String> {
        val names = ArrayList<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                if (isUserTable(name)) names.add(name)
            }
        }
        return names
    }

    /** The column names of [table], read from `PRAGMA table_info`. */
    fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val columns = ArrayList<String>()
        db.query("PRAGMA table_info(\"$table\")").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
        }
        return columns
    }

    private fun isUserTable(name: String): Boolean =
        name != TABLE_TOMBSTONES &&
            name != NoteDeleteTraceSql.TABLE &&
            name != NoteDeleteTraceSql.CONTEXT_TABLE &&
            name != "room_master_table" &&
            name != "android_metadata" &&
            name != "room_table_modification_log" &&
            !name.startsWith("sqlite_")
}

/** What a finished backup contains. */
class BackupSummary(
    val bytes: Long,
    val tableCounts: Map<String, Int>,
    val photoCount: Int,
    val documentCount: Int,
) {
    val totalRows: Int get() = tableCounts.values.sum()
}

/** Counts the bytes that pass through it. */
internal class CountingOutputStream(target: OutputStream) : FilterOutputStream(target) {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len.toLong()
    }
}

/** A friendly label for a table name, used in summaries. */
internal fun tableLabel(table: String): String = when (table) {
    "notes" -> "Notes"
    "ideas" -> "Ideas"
    "goals" -> "Goals"
    "goal_milestones" -> "Goal steps"
    "diary_entries" -> "Diary entries"
    "stories" -> "Stories"
    "story_chapters" -> "Chapters"
    "story_characters" -> "Characters"
    "story_notes" -> "Story notes"
    "writing_ideas" -> "Writing ideas"
    "social_posts" -> "Posts"
    "church_records" -> "Church records"
    "memories" -> "Memories"
    "personal_documents" -> "Documents"
    "projects" -> "Projects"
    "secrets" -> "Secrets"
    "bugs" -> "Bugs"
    "tasks" -> "Tasks"
    "planning_docs" -> "Plans"
    "folder_plans" -> "Folder plans"
    "prompts" -> "Prompts"
    "project_documents" -> "Project docs"
    "audit_log" -> "Audit log"
    else -> table
}

/** Short lines such as "Notes 42" for every non-empty table, then "Photos 38" and "Files 5". */
internal fun summaryLines(counts: Map<String, Int>, photos: Int, documents: Int): List<String> {
    val lines = ArrayList<String>()
    for ((table, count) in counts) {
        if (count > 0 && table != "audit_log") lines.add("${tableLabel(table)} $count")
    }
    if (photos > 0) lines.add("Photos $photos")
    if (documents > 0) lines.add("Document files $documents")
    return lines
}
