package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.DiaryEntryDao
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import kotlinx.coroutines.flow.Flow

/** Repository for DiaryEntryEntity. */
class DiaryRepository(private val dao: DiaryEntryDao) {
    fun observeAll(): Flow<List<DiaryEntryEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<DiaryEntryEntity?> = dao.observeById(id)

    suspend fun getById(id: String): DiaryEntryEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: DiaryEntryEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<DiaryEntryEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun search(query: String): Flow<List<DiaryEntryEntity>> = dao.search(query)

    fun observeInRange(startInclusive: Long, endExclusive: Long): Flow<List<DiaryEntryEntity>> =
        dao.observeInRange(startInclusive, endExclusive)

    fun observeEntryDates(): Flow<List<Long>> = dao.observeEntryDates()
}
