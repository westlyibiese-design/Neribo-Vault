package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.IdeaDao
import com.westly.neribovault.data.local.entity.IdeaEntity
import kotlinx.coroutines.flow.Flow

/** Repository for IdeaEntity. */
class IdeasRepository(private val dao: IdeaDao) {
    fun observeAll(): Flow<List<IdeaEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<IdeaEntity?> = dao.observeById(id)

    suspend fun getById(id: String): IdeaEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: IdeaEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<IdeaEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun search(query: String): Flow<List<IdeaEntity>> = dao.search(query)

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun setStatus(id: String, status: String) {
        dao.setStatus(id, status, System.currentTimeMillis())
    }
}
