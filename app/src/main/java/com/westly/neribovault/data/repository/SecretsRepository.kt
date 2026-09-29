package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.SecretDao
import com.westly.neribovault.data.local.entity.SecretEntity
import kotlinx.coroutines.flow.Flow

/** Repository for SecretEntity. */
class SecretsRepository(private val dao: SecretDao) {
    fun observeAll(): Flow<List<SecretEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<SecretEntity?> = dao.observeById(id)

    suspend fun getById(id: String): SecretEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: SecretEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<SecretEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<SecretEntity>> = dao.observeByProject(projectId)
}
