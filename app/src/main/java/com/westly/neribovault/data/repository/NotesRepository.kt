package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.NoteDao
import com.westly.neribovault.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/** Notes: pinned first, then most recently edited. */
class NotesRepository(private val dao: NoteDao) {
    fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<NoteEntity?> = dao.observeById(id)

    suspend fun getById(id: String): NoteEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: NoteEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<NoteEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    fun observeActive(): Flow<List<NoteEntity>> = dao.observeActive()

    fun observeArchived(): Flow<List<NoteEntity>> = dao.observeArchived()

    fun search(query: String): Flow<List<NoteEntity>> = dao.search(query)

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(id, archived, System.currentTimeMillis())
    }
}
