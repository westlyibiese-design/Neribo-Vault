package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.PromptEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [PromptEntity]. */
@Dao
interface PromptDao {
    @Query("SELECT * FROM prompts WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PromptEntity>>

    @Query("SELECT * FROM prompts WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<PromptEntity?>

    @Query("SELECT * FROM prompts WHERE id = :id")
    suspend fun getById(id: String): PromptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PromptEntity)

    @Query("UPDATE prompts SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE prompts SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM prompts WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<PromptEntity>>

    @Query("DELETE FROM prompts WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM prompts WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM prompts WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM prompts WHERE isDeleted = 0 AND projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<PromptEntity>>
}
