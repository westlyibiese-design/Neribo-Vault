package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.ProjectDocumentDao
import com.westly.neribovault.data.local.entity.ProjectDocumentEntity
import kotlinx.coroutines.flow.Flow

/** Repository for ProjectDocumentEntity. */
class ProjectDocumentsRepository(private val dao: ProjectDocumentDao) {
    fun observeAll(): Flow<List<ProjectDocumentEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ProjectDocumentEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ProjectDocumentEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: ProjectDocumentEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<ProjectDocumentEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<ProjectDocumentEntity>> = dao.observeByProject(projectId)
}
