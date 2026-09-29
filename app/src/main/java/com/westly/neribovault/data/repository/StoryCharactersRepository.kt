package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.StoryCharacterDao
import com.westly.neribovault.data.local.entity.StoryCharacterEntity
import kotlinx.coroutines.flow.Flow

/** Repository for StoryCharacterEntity. */
class StoryCharactersRepository(private val dao: StoryCharacterDao) {
    fun observeAll(): Flow<List<StoryCharacterEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<StoryCharacterEntity?> = dao.observeById(id)

    suspend fun getById(id: String): StoryCharacterEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: StoryCharacterEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<StoryCharacterEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeForStory(storyId: String): Flow<List<StoryCharacterEntity>> = dao.observeForStory(storyId)
}
