package com.westly.neribovault.data.cloud

/** One Room table that takes part in cloud sync, and the vault whose switch controls it. */
data class SyncTable(val name: String, val vaultId: String)

/** A vault as shown in the per-vault sync switches. */
data class SyncVault(val id: String, val label: String, val defaultEnabled: Boolean = true)

/** A column that holds a device-specific file path and must never be uploaded or overwritten. */
data class DeviceLocalColumn(val column: String, val emptyValue: String?)

/** The fixed list of tables that sync, and the rules that go with them. */
object SyncTables {
    /** Tables that never leave the device. Listed for documentation and for defensive checks. */
    val NEVER_SYNCED: Set<String> = setOf("secrets", "audit_log", "sync_tombstones")

    val VAULTS: List<SyncVault> = listOf(
        SyncVault("notes", "Notes"),
        SyncVault("ideas", "Ideas"),
        SyncVault("goals", "Goals"),
        SyncVault("diary", "Diary", defaultEnabled = false),
        SyncVault("writers", "Writers"),
        SyncVault("posts", "Posts"),
        SyncVault("church", "Church Records"),
        SyncVault("memories", "Memories"),
        SyncVault("documents", "Documents"),
        SyncVault("developer", "Developer"),
    )

    /** The 21 synced tables, in sync order. */
    val ALL: List<SyncTable> = listOf(
        SyncTable("notes", "notes"),
        SyncTable("ideas", "ideas"),
        SyncTable("goals", "goals"),
        SyncTable("goal_milestones", "goals"),
        SyncTable("diary_entries", "diary"),
        SyncTable("stories", "writers"),
        SyncTable("story_chapters", "writers"),
        SyncTable("story_characters", "writers"),
        SyncTable("story_notes", "writers"),
        SyncTable("writing_ideas", "writers"),
        SyncTable("social_posts", "posts"),
        SyncTable("church_records", "church"),
        SyncTable("memories", "memories"),
        SyncTable("personal_documents", "documents"),
        SyncTable("projects", "developer"),
        SyncTable("bugs", "developer"),
        SyncTable("tasks", "developer"),
        SyncTable("planning_docs", "developer"),
        SyncTable("folder_plans", "developer"),
        SyncTable("prompts", "developer"),
        SyncTable("project_documents", "developer"),
    )

    fun vaultLabel(vaultId: String): String =
        VAULTS.firstOrNull { it.id == vaultId }?.label ?: vaultId

    fun isVaultEnabledByDefault(vaultId: String): Boolean =
        VAULTS.firstOrNull { it.id == vaultId }?.defaultEnabled ?: false

    /**
     * The device-local column of [table], if it has one. Photo paths (memories) and the attached
     * file path (personal documents) are meaningless on another device.
     */
    fun deviceLocalColumn(table: String): DeviceLocalColumn? = when (table) {
        "memories" -> DeviceLocalColumn("photoUris", "")
        "personal_documents" -> DeviceLocalColumn("fileUri", null)
        else -> null
    }

    /** SQL that records a tombstone whenever a row of [table] is permanently deleted. */
    fun tombstoneTriggerSql(table: String): String =
        "CREATE TRIGGER IF NOT EXISTS sync_tomb_$table AFTER DELETE ON $table BEGIN " +
            "INSERT OR REPLACE INTO sync_tombstones(kind, rowId, deletedAt) " +
            "VALUES('$table', OLD.id, CAST(strftime('%s','now') AS INTEGER) * 1000); END;"
}
