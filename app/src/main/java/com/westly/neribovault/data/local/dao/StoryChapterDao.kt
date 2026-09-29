package com.westly.neribovault.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import kotlinx.coroutines.flow.Flow

/** One row of the per-story chapter aggregate. */
data class StoryStatsRow(
    @ColumnInfo(name = "storyId") val storyId: String,
    @ColumnInfo(name = "chapterCount") val chapterCount: Int,
    @ColumnInfo(name = "wordCount") val wordCount: Int,
)

/** Database access for [StoryChapterEntity]. */
@Dao
interface StoryChapterDao {
    @Query("SELECT * FROM story_chapters WHERE isDeleted = 0 AND storyId = :storyId ORDER BY sortOrder ASC")
    fun observeForStory(storyId: String): Flow<List<StoryChapterEntity>>

    @Query("SELECT * FROM story_chapters WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<StoryChapterEntity?>

    @Query("SELECT * FROM story_chapters WHERE id = :id")
    suspend fun getById(id: String): StoryChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: StoryChapterEntity)

    @Query("UPDATE story_chapters SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE story_chapters SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("UPDATE story_chapters SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Query("SELECT storyId AS storyId, COUNT(*) AS chapterCount, COALESCE(SUM(wordCount), 0) AS wordCount FROM story_chapters WHERE isDeleted = 0 GROUP BY storyId")
    fun observeStats(): Flow<List<StoryStatsRow>>

    @Query("DELETE FROM story_chapters WHERE storyId = :storyId")
    suspend fun deleteForStory(storyId: String)

    @Query("DELETE FROM story_chapters WHERE storyId IN (SELECT id FROM stories WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff)")
    suspend fun deleteForTrashedStoriesBefore(cutoff: Long)

    @Query("DELETE FROM story_chapters WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM story_chapters WHERE isDeleted = 0")
    suspend fun countActive(): Int
}
