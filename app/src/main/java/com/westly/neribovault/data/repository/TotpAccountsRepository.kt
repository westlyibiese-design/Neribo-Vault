package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.TotpAccountDao
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import kotlinx.coroutines.flow.Flow

/** Authenticator accounts: pinned first, then by sort order, then by service and account name. */
class TotpAccountsRepository(private val dao: TotpAccountDao) {
    fun observeAll(): Flow<List<TotpAccountEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<TotpAccountEntity?> = dao.observeById(id)

    suspend fun getById(id: String): TotpAccountEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: TotpAccountEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<TotpAccountEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    suspend fun getAllIncludingTrashed(): List<TotpAccountEntity> = dao.getAllIncludingTrashed()

    /** The highest sort order in use, counting trashed rows too; 0 when there are none. */
    suspend fun maxSortOrder(): Int = dao.maxSortOrder()

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned)
    }
}
