package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.PersonalDocumentDao
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import kotlinx.coroutines.flow.Flow

/** Repository for PersonalDocumentEntity. */
class PersonalDocumentsRepository(private val dao: PersonalDocumentDao) {
    fun observeAll(): Flow<List<PersonalDocumentEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<PersonalDocumentEntity?> = dao.observeById(id)

    suspend fun getById(id: String): PersonalDocumentEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: PersonalDocumentEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<PersonalDocumentEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }
}
