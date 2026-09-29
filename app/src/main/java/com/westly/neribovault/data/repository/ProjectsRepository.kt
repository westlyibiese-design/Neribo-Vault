package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.ProjectDao
import com.westly.neribovault.data.local.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/** Repository for ProjectEntity. */
class ProjectsRepository(private val dao: ProjectDao) {
    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ProjectEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ProjectEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: ProjectEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<ProjectEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }
}
