package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [AuditLogEntity]. */
@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log WHERE id = :id")
    suspend fun getById(id: String): AuditLogEntity?

    @Query("DELETE FROM audit_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM audit_log")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun countAll(): Int
}
