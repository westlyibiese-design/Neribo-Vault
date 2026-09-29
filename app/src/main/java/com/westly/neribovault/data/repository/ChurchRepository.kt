package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.ChurchRecordDao
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import kotlinx.coroutines.flow.Flow

/** Repository for ChurchRecordEntity. */
class ChurchRepository(private val dao: ChurchRecordDao) {
    fun observeAll(): Flow<List<ChurchRecordEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<ChurchRecordEntity?> = dao.observeById(id)

    suspend fun getById(id: String): ChurchRecordEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: ChurchRecordEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<ChurchRecordEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun search(query: String): Flow<List<ChurchRecordEntity>> = dao.search(query)

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
    }
}
