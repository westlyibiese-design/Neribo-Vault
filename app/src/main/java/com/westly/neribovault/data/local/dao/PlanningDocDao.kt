package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.PlanningDocEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [PlanningDocEntity]. */
@Dao
interface PlanningDocDao {
    @Query("SELECT * FROM planning_docs WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlanningDocEntity>>

    @Query("SELECT * FROM planning_docs WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<PlanningDocEntity?>

    @Query("SELECT * FROM planning_docs WHERE id = :id")
    suspend fun getById(id: String): PlanningDocEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlanningDocEntity)

    @Query("UPDATE planning_docs SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE planning_docs SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM planning_docs WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<PlanningDocEntity>>

    @Query("DELETE FROM planning_docs WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM planning_docs WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM planning_docs WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM planning_docs WHERE isDeleted = 0 AND projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<PlanningDocEntity>>
}
