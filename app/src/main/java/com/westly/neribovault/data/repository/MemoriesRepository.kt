package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.MemoryDao
import com.westly.neribovault.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

/** Repository for MemoryEntity. */
class MemoriesRepository(private val dao: MemoryDao) {
    fun observeAll(): Flow<List<MemoryEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<MemoryEntity?> = dao.observeById(id)

    suspend fun getById(id: String): MemoryEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: MemoryEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<MemoryEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }
}
