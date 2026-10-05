package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [ScreenplayEntity]. */
@Dao
interface ScreenplayDao {
    @Query("SELECT * FROM screenplays WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScreenplayEntity>>

    @Query("SELECT * FROM screenplays WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<ScreenplayEntity?>

    @Query("SELECT * FROM screenplays WHERE id = :id")
    suspend fun getById(id: String): ScreenplayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScreenplayEntity)

    @Query("UPDATE screenplays SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE screenplays SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM screenplays WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<ScreenplayEntity>>

    @Query("DELETE FROM screenplays WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM screenplays WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT * FROM screenplays WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<ScreenplayEntity>>
}
