package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.StoryCharacterEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [StoryCharacterEntity]. */
@Dao
interface StoryCharacterDao {
    @Query("SELECT * FROM story_characters WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<StoryCharacterEntity>>

    @Query("SELECT * FROM story_characters WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<StoryCharacterEntity?>

    @Query("SELECT * FROM story_characters WHERE id = :id")
    suspend fun getById(id: String): StoryCharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StoryCharacterEntity)

    @Query("UPDATE story_characters SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE story_characters SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM story_characters WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<StoryCharacterEntity>>

    @Query("DELETE FROM story_characters WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM story_characters WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM story_characters WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM story_characters WHERE isDeleted = 0 AND storyId = :storyId ORDER BY name COLLATE NOCASE ASC")
    fun observeForStory(storyId: String): Flow<List<StoryCharacterEntity>>

    @Query("DELETE FROM story_characters WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)

    @Query("DELETE FROM story_characters WHERE storyId IN (SELECT id FROM stories WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff)")
    suspend fun deleteForTrashedStoriesBefore(cutoff: Long)
}
