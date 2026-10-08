package com.westly.neribovault.data.repository

import androidx.room.withTransaction
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.dao.AccountFieldDao
import com.westly.neribovault.data.local.dao.AccountItemDao
import com.westly.neribovault.data.local.entity.AccountItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Items under an account (projects, apps, pages, domains and so on): name A to Z.
 * Removing an item for good also removes the custom fields it owns.
 */
class AccountItemsRepository(
    private val database: NeriboDatabase,
    private val dao: AccountItemDao,
    private val fieldDao: AccountFieldDao,
) {
    fun observeAll(): Flow<List<AccountItemEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<AccountItemEntity?> = dao.observeById(id)

    suspend fun getById(id: String): AccountItemEntity? = dao.getById(id)

    /** Non-deleted items of one account, name A to Z. */
    fun observeForAccount(accountId: String): Flow<List<AccountItemEntity>> =
        dao.observeForAccount(accountId)

    /** Account id to the number of its non-deleted items. Accounts without items are absent. */
    fun observeCountsByAccount(): Flow<Map<String, Int>> =
        dao.observeCounts().map { rows -> rows.associate { it.accountId to it.total } }

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: AccountItemEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<AccountItemEntity>> = dao.observeTrashed()

    /** Hard-deletes the item and the fields it owns. */
    suspend fun deletePermanently(id: String) {
        database.withTransaction {
            fieldDao.deleteForOwner(OWNER_ITEM, id)
            dao.deletePermanently(id)
        }
    }

    /** Hard-deletes every item trashed before [cutoffMillis], with the fields they own. */
    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        database.withTransaction {
            dao.trashedIdsBefore(cutoffMillis).chunked(CHUNK).forEach { fieldDao.deleteForOwnerIds(it) }
            dao.deleteTrashedBefore(cutoffMillis)
        }
    }

    private companion object {
        const val OWNER_ITEM = "item"
        const val CHUNK = 500
    }
}
