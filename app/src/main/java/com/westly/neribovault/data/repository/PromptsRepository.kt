package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.PromptDao
import com.westly.neribovault.data.local.entity.PromptEntity
import kotlinx.coroutines.flow.Flow

/** Repository for PromptEntity. */
class PromptsRepository(private val dao: PromptDao) {
    fun observeAll(): Flow<List<PromptEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<PromptEntity?> = dao.observeById(id)

    suspend fun getById(id: String): PromptEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: PromptEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<PromptEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<PromptEntity>> = dao.observeByProject(projectId)
}
