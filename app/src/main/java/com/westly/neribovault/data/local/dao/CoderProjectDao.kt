package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [CoderProjectEntity]. */
@Dao
interface CoderProjectDao {
    @Query("SELECT * FROM coder_projects WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CoderProjectEntity>>

    @Query("SELECT * FROM coder_projects WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<CoderProjectEntity?>

    @Query("SELECT * FROM coder_projects WHERE id = :id")
    suspend fun getById(id: String): CoderProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CoderProjectEntity)

    @Query("UPDATE coder_projects SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE coder_projects SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM coder_projects WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<CoderProjectEntity>>

    @Query("DELETE FROM coder_projects WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("SELECT id FROM coder_projects WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedIdsBefore(cutoff: Long): List<String>

    @Query("DELETE FROM coder_projects WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("UPDATE coder_projects SET repoOwner = :owner, repoName = :name, repoBranch = :branch, updatedAt = :now WHERE id = :id")
    suspend fun setRepoLink(id: String, owner: String?, name: String?, branch: String?, now: Long)

    @Query("UPDATE coder_projects SET lastPushedAt = :time, updatedAt = :time WHERE id = :id")
    suspend fun markPushed(id: String, time: Long)
}
