package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.StoryNoteEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [StoryNoteEntity]. */
@Dao
interface StoryNoteDao {
    @Query("SELECT * FROM story_notes WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<StoryNoteEntity>>

    @Query("SELECT * FROM story_notes WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<StoryNoteEntity?>

    @Query("SELECT * FROM story_notes WHERE id = :id")
    suspend fun getById(id: String): StoryNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StoryNoteEntity)

    @Query("UPDATE story_notes SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE story_notes SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM story_notes WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<StoryNoteEntity>>

    @Query("DELETE FROM story_notes WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM story_notes WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM story_notes WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM story_notes WHERE isDeleted = 0 AND storyId = :storyId ORDER BY updatedAt DESC")
    fun observeForStory(storyId: String): Flow<List<StoryNoteEntity>>

    @Query("DELETE FROM story_notes WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)

    @Query("DELETE FROM story_notes WHERE storyId IN (SELECT id FROM stories WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff)")
    suspend fun deleteForTrashedStoriesBefore(cutoff: Long)
}
