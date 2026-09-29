package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.IdeaEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [IdeaEntity]. */
@Dao
interface IdeaDao {
    @Query("SELECT * FROM ideas WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<IdeaEntity?>

    @Query("SELECT * FROM ideas WHERE id = :id")
    suspend fun getById(id: String): IdeaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: IdeaEntity)

    @Query("UPDATE ideas SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE ideas SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM ideas WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<IdeaEntity>>

    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM ideas WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM ideas WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM ideas WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY isPinned DESC, updatedAt DESC")
    fun search(query: String): Flow<List<IdeaEntity>>

    @Query("UPDATE ideas SET isPinned = :value, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, value: Boolean, now: Long)

    @Query("UPDATE ideas SET status = :value, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, value: String, now: Long)
}
