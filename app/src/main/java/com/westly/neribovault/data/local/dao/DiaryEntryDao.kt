package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [DiaryEntryEntity]. */
@Dao
interface DiaryEntryDao {
    @Query("SELECT * FROM diary_entries WHERE isDeleted = 0 ORDER BY entryDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<DiaryEntryEntity?>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun getById(id: String): DiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DiaryEntryEntity)

    @Query("UPDATE diary_entries SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE diary_entries SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM diary_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<DiaryEntryEntity>>

    @Query("DELETE FROM diary_entries WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM diary_entries WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM diary_entries WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM diary_entries WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY entryDate DESC, createdAt DESC")
    fun search(query: String): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries WHERE isDeleted = 0 AND entryDate >= :start AND entryDate < :end ORDER BY entryDate DESC, createdAt DESC")
    fun observeInRange(start: Long, end: Long): Flow<List<DiaryEntryEntity>>

    @Query("SELECT DISTINCT entryDate FROM diary_entries WHERE isDeleted = 0 ORDER BY entryDate DESC")
    fun observeEntryDates(): Flow<List<Long>>
}
