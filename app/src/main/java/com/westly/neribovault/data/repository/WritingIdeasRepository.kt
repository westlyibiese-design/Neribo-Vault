package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.WritingIdeaDao
import com.westly.neribovault.data.local.entity.WritingIdeaEntity
import kotlinx.coroutines.flow.Flow

/** Repository for WritingIdeaEntity. */
class WritingIdeasRepository(private val dao: WritingIdeaDao) {
    fun observeAll(): Flow<List<WritingIdeaEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<WritingIdeaEntity?> = dao.observeById(id)

    suspend fun getById(id: String): WritingIdeaEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: WritingIdeaEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<WritingIdeaEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun search(query: String): Flow<List<WritingIdeaEntity>> = dao.search(query)

    suspend fun setStatus(id: String, status: String) {
        dao.setStatus(id, status, System.currentTimeMillis())
    }
}
