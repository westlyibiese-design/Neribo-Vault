package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [NoteEntity]. */
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NoteEntity)

    /** Also stamps `updatedAt`, because sync only sends rows whose `updatedAt` moved forward. */
    @Query("UPDATE notes SET isDeleted = 1, deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** Also stamps `updatedAt`, for the same reason as [softDelete]. */
    @Query("UPDATE notes SET isDeleted = 0, deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: String, now: Long)

    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM notes WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND isArchived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isDeleted = 0 AND isArchived = 0 AND (title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY isPinned DESC, updatedAt DESC")
    fun search(query: String): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET isPinned = :value, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, value: Boolean, now: Long)

    @Query("UPDATE notes SET isArchived = :value, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, value: Boolean, now: Long)
}
