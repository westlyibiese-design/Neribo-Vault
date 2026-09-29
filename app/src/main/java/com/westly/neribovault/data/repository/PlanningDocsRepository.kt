package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.PlanningDocDao
import com.westly.neribovault.data.local.entity.PlanningDocEntity
import kotlinx.coroutines.flow.Flow

/** Repository for PlanningDocEntity. */
class PlanningDocsRepository(private val dao: PlanningDocDao) {
    fun observeAll(): Flow<List<PlanningDocEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<PlanningDocEntity?> = dao.observeById(id)

    suspend fun getById(id: String): PlanningDocEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: PlanningDocEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<PlanningDocEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<PlanningDocEntity>> = dao.observeByProject(projectId)
}
