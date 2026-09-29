package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.BugDao
import com.westly.neribovault.data.local.entity.BugEntity
import kotlinx.coroutines.flow.Flow

/** Repository for BugEntity. */
class BugsRepository(private val dao: BugDao) {
    fun observeAll(): Flow<List<BugEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<BugEntity?> = dao.observeById(id)

    suspend fun getById(id: String): BugEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: BugEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<BugEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<BugEntity>> = dao.observeByProject(projectId)
}
