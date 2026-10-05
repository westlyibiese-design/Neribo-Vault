package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.ScreenplayDao
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import kotlinx.coroutines.flow.Flow

/** Screenplays: most recently edited first. */
class ScreenplaysRepository(private val dao: ScreenplayDao) {
    fun observeAll(): Flow<List<ScreenplayEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ScreenplayEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ScreenplayEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: ScreenplayEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<ScreenplayEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    /** Non-deleted screenplays whose title or author contains [query], most recently edited first. */
    fun search(query: String): Flow<List<ScreenplayEntity>> = dao.search(query)
}
