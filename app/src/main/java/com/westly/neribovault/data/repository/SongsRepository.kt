package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.SongDao
import com.westly.neribovault.data.local.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/** Songs: most recently edited first. */
class SongsRepository(private val dao: SongDao) {
    fun observeAll(): Flow<List<SongEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<SongEntity?> = dao.observeById(id)

    suspend fun getById(id: String): SongEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: SongEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<SongEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    /** Non-deleted songs whose title or writer contains [query], most recently edited first. */
    fun search(query: String): Flow<List<SongEntity>> = dao.search(query)
}
