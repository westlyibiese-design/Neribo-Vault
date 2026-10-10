package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.dao.AccountFieldDao
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import kotlinx.coroutines.flow.Flow

/**
 * Custom fields on accounts and items, in the owner's order. Fields have no trash: they are
 * removed for good when the owner removes them (softDelete and restore exist but the UI never
 * uses them).
 */
class AccountFieldsRepository(private val dao: AccountFieldDao) {
    /** Non-deleted fields, `sortOrder` ascending. */
    fun observeAll(): Flow<List<AccountFieldEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<AccountFieldEntity?> = dao.observeById(id)

    suspend fun getById(id: String): AccountFieldEntity? = dao.getById(id)

    /** Non-deleted fields of one owner ("account" or "item"), by `sortOrder` then creation time. */
    fun observeForOwner(ownerType: String, ownerId: String): Flow<List<AccountFieldEntity>> =
        dao.observeForOwner(ownerType, ownerId)

    /** Non-deleted fields of every non-deleted item of one account, in one query. */
    fun observeForItemsOfAccount(accountId: String): Flow<List<AccountFieldEntity>> =
        dao.observeForItemsOfAccount(accountId)

    /** Every field row, trashed ones included. Used for re-encryption and backup checks. */
    suspend fun getAllIncludingTrashed(): List<AccountFieldEntity> = dao.getAllIncludingTrashed()

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: AccountFieldEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Writes [item] exactly as given, without touching `updatedAt` (used when re-encrypting). */
    suspend fun upsertExact(item: AccountFieldEntity) {
        dao.upsert(item)
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<AccountFieldEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String) {
        dao.deletePermanently(id)
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        dao.deleteTrashedBefore(cutoffMillis)
    }

    /** Hard-deletes every field owned by the given owner. */
    suspend fun deleteForOwner(ownerType: String, ownerId: String) {
        dao.deleteForOwner(ownerType, ownerId)
    }
}
