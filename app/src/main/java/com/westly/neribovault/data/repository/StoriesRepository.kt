package com.westly.neribovault.data.repository

import androidx.room.withTransaction
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.dao.StoryChapterDao
import com.westly.neribovault.data.local.dao.StoryCharacterDao
import com.westly.neribovault.data.local.dao.StoryDao
import com.westly.neribovault.data.local.dao.StoryNoteDao
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.data.local.entity.StoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Chapter count and total word count of one story. */
data class StoryStats(val chapterCount: Int, val wordCount: Int)

/** Stories and their chapters. */
class StoriesRepository(
    private val database: NeriboDatabase,
    private val dao: StoryDao,
    private val chapterDao: StoryChapterDao,
    private val characterDao: StoryCharacterDao,
    private val noteDao: StoryNoteDao,
) {
    fun observeAll(): Flow<List<StoryEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<StoryEntity?> = dao.observeById(id)

    suspend fun getById(id: String): StoryEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: StoryEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<StoryEntity>> = dao.observeTrashed()

    /** Removes the story together with its chapters, characters and notes. */
    suspend fun deletePermanently(id: String) {
        database.withTransaction {
            chapterDao.deleteForStory(id)
            characterDao.deleteForStory(id)
            noteDao.deleteForStory(id)
            dao.deletePermanently(id)
        }
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        database.withTransaction {
            chapterDao.deleteForTrashedStoriesBefore(cutoffMillis)
            characterDao.deleteForTrashedStoriesBefore(cutoffMillis)
            noteDao.deleteForTrashedStoriesBefore(cutoffMillis)
            chapterDao.deleteTrashedBefore(cutoffMillis)
            dao.deleteTrashedBefore(cutoffMillis)
        }
    }

    fun search(query: String): Flow<List<StoryEntity>> = dao.search(query)

    suspend fun setStatus(id: String, status: String) {
        dao.setStatus(id, status, System.currentTimeMillis())
    }

    /** Stats keyed by story id, counting non-deleted chapters only. */
    fun observeStoryStats(): Flow<Map<String, StoryStats>> =
        chapterDao.observeStats().map { rows ->
            rows.associate { row -> row.storyId to StoryStats(row.chapterCount, row.wordCount) }
        }

    fun observeChapters(storyId: String): Flow<List<StoryChapterEntity>> = chapterDao.observeForStory(storyId)

    fun observeChapterById(chapterId: String): Flow<StoryChapterEntity?> = chapterDao.observeById(chapterId)

    suspend fun getChapterById(chapterId: String): StoryChapterEntity? = chapterDao.getById(chapterId)

    /** Saves the chapter and bumps the parent story's `updatedAt`. */
    suspend fun upsertChapter(chapter: StoryChapterEntity) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            chapterDao.upsert(chapter.copy(updatedAt = now))
            dao.touch(chapter.storyId, now)
        }
    }

    suspend fun softDeleteChapter(id: String) {
        chapterDao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restoreChapter(id: String) {
        chapterDao.restore(id)
    }

    /** Rewrites `sortOrder` to 0..n-1 following [orderedChapterIds]. */
    suspend fun reorderChapters(storyId: String, orderedChapterIds: List<String>) {
        database.withTransaction {
            orderedChapterIds.forEachIndexed { index, chapterId ->
                chapterDao.setSortOrder(chapterId, index)
            }
            dao.touch(storyId, System.currentTimeMillis())
        }
    }
}
