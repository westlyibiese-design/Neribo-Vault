package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.SocialPostDao
import com.westly.neribovault.data.local.entity.SocialPostEntity
import kotlinx.coroutines.flow.Flow

/** Repository for SocialPostEntity. */
class PostsRepository(private val dao: SocialPostDao) {
    fun observeAll(): Flow<List<SocialPostEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<SocialPostEntity?> = dao.observeById(id)

    suspend fun getById(id: String): SocialPostEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: SocialPostEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<SocialPostEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByStatus(status: String): Flow<List<SocialPostEntity>> = dao.observeByStatus(status)

    fun observeScheduled(): Flow<List<SocialPostEntity>> = dao.observeScheduled()

    fun search(query: String): Flow<List<SocialPostEntity>> = dao.search(query)

    suspend fun setStatus(id: String, status: String, postedAt: Long? = null) {
        dao.setStatus(id, status, postedAt, System.currentTimeMillis())
    }
}
