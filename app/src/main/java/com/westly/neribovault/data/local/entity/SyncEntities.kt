package com.westly.neribovault.data.local.entity

import androidx.room.Entity

/**
 * Remembers a row that was permanently deleted on this device, so the delete can be sent to the
 * cloud. Filled by database triggers (see NeriboDatabase), emptied by the sync engine.
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "rowId"])
data class SyncTombstoneEntity(
    val kind: String,
    val rowId: String,
    val deletedAt: Long,
)
