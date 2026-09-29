package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.StoryEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [StoryEntity]. */
@Dao
interface StoryDao {
    @Query("SELECT * FROM stories WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<StoryEntity?>

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun getById(id: String): StoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StoryEntity)

    @Query("UPDATE stories SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE stories SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM stories WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<StoryEntity>>

    @Query("DELETE FROM stories WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM stories WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM stories WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM stories WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR synopsis LIKE '%' || :query || '%' OR genre LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<StoryEntity>>

    @Query("UPDATE stories SET status = :value, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, value: String, now: Long)

    @Query("UPDATE stories SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)
}
