package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.WritingIdeaEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [WritingIdeaEntity]. */
@Dao
interface WritingIdeaDao {
    @Query("SELECT * FROM writing_ideas WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WritingIdeaEntity>>

    @Query("SELECT * FROM writing_ideas WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<WritingIdeaEntity?>

    @Query("SELECT * FROM writing_ideas WHERE id = :id")
    suspend fun getById(id: String): WritingIdeaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WritingIdeaEntity)

    @Query("UPDATE writing_ideas SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE writing_ideas SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM writing_ideas WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<WritingIdeaEntity>>

    @Query("DELETE FROM writing_ideas WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM writing_ideas WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM writing_ideas WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM writing_ideas WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<WritingIdeaEntity>>

    @Query("UPDATE writing_ideas SET status = :value, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, value: String, now: Long)
}
