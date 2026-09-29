package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.FolderPlanDao
import com.westly.neribovault.data.local.entity.FolderPlanEntity
import kotlinx.coroutines.flow.Flow

/** Repository for FolderPlanEntity. */
class FolderPlansRepository(private val dao: FolderPlanDao) {
    fun observeAll(): Flow<List<FolderPlanEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<FolderPlanEntity?> = dao.observeById(id)

    suspend fun getById(id: String): FolderPlanEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: FolderPlanEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<FolderPlanEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<FolderPlanEntity>> = dao.observeByProject(projectId)
}
