package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.TaskDao
import com.westly.neribovault.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/** Repository for TaskEntity. */
class TasksRepository(private val dao: TaskDao) {
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<TaskEntity?> = dao.observeById(id)

    suspend fun getById(id: String): TaskEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: TaskEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<TaskEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeByProject(projectId: String): Flow<List<TaskEntity>> = dao.observeByProject(projectId)
}
