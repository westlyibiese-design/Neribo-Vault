package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.StoryNoteDao
import com.westly.neribovault.data.local.entity.StoryNoteEntity
import kotlinx.coroutines.flow.Flow

/** Repository for StoryNoteEntity. */
class StoryNotesRepository(private val dao: StoryNoteDao) {
    fun observeAll(): Flow<List<StoryNoteEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<StoryNoteEntity?> = dao.observeById(id)

    suspend fun getById(id: String): StoryNoteEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: StoryNoteEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<StoryNoteEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeForStory(storyId: String): Flow<List<StoryNoteEntity>> = dao.observeForStory(storyId)
}
