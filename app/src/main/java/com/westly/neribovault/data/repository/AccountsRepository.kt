package com.westly.neribovault.data.repository

import androidx.room.withTransaction
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.dao.AccountDao
import com.westly.neribovault.data.local.dao.AccountFieldDao
import com.westly.neribovault.data.local.dao.AccountItemDao
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.feature.accounts.AccountLogos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Accounts: pinned first, then name A to Z. Removing an account for good also removes its items
 * and the custom fields owned by the account and by those items, all in one transaction, and
 * deletes its custom logo file (when [filesDir] is given).
 */
class AccountsRepository(
    private val database: NeriboDatabase,
    private val dao: AccountDao,
    private val itemDao: AccountItemDao,
    private val fieldDao: AccountFieldDao,
    private val filesDir: File? = null,
) {
    fun observeAll(): Flow<List<AccountEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<AccountEntity?> = dao.observeById(id)

    suspend fun getById(id: String): AccountEntity? = dao.getById(id)

    /** Every account row, trashed ones included. Used for re-encryption and backup checks. */
    suspend fun getAllIncludingTrashed(): List<AccountEntity> = dao.getAllIncludingTrashed()

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: AccountEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Writes [item] exactly as given, without touching `updatedAt`. Used when the PIN changes and
     * every password is re-encrypted: the password's age must not reset.
     */
    suspend fun upsertExact(item: AccountEntity) {
        dao.upsert(item)
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<AccountEntity>> = dao.observeTrashed()

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned)
    }

    /** Hard-deletes the account, its items and every field owned by the account or its items. */
    suspend fun deletePermanently(id: String) {
        val logo = dao.getById(id)?.customLogoPath
        database.withTransaction { removeEverythingOf(id) }
        deleteLogoFiles(listOf(logo))
    }

    /** Hard-deletes every account trashed before [cutoffMillis], with its items and fields. */
    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        val logos = dao.trashedIdsBefore(cutoffMillis).map { dao.getById(it)?.customLogoPath }
        database.withTransaction {
            dao.trashedIdsBefore(cutoffMillis).forEach { removeEverythingOf(it) }
            dao.deleteTrashedBefore(cutoffMillis)
        }
        deleteLogoFiles(logos)
    }

    private suspend fun deleteLogoFiles(paths: List<String?>) {
        val dir = filesDir ?: return
        withContext(Dispatchers.IO) { paths.forEach { AccountLogos.delete(dir, it) } }
    }

    private suspend fun removeEverythingOf(accountId: String) {
        val itemIds = itemDao.idsForAccountIncludingTrashed(accountId)
        itemIds.chunked(CHUNK).forEach { fieldDao.deleteForOwnerIds(it) }
        fieldDao.deleteForOwner(OWNER_ACCOUNT, accountId)
        itemDao.deleteForAccount(accountId)
        dao.deletePermanently(accountId)
    }

    /**
     * Copies the account as "Copy of <name>", with new ids and fresh timestamps. Every other
     * field is kept, including the encrypted password and its IV (still valid, because the key
     * is the same). The account's items and the fields of the account and of those items are
     * copied too, with corrected owner ids. Returns the new account's id, or null if the
     * account does not exist.
     */
    suspend fun duplicate(accountId: String): String? = database.withTransaction {
        val source = dao.getById(accountId) ?: return@withTransaction null
        val now = System.currentTimeMillis()
        val newAccountId = newId()
        val dir = filesDir
        val newLogo = if (dir != null) source.customLogoPath?.let { AccountLogos.copy(dir, it, newAccountId) } else null
        dao.upsert(
            source.copy(
                id = newAccountId,
                createdAt = now,
                updatedAt = now,
                isDeleted = false,
                deletedAt = null,
                name = "Copy of " + source.name,
                customLogoPath = newLogo,
            ),
        )
        val copiedFields = ArrayList<AccountFieldEntity>()
        fieldDao.getForOwner(OWNER_ACCOUNT, accountId).forEach { field ->
            copiedFields.add(field.asCopy(OWNER_ACCOUNT, newAccountId, now))
        }
        for (item in itemDao.getForAccount(accountId)) {
            val newItemId = newId()
            itemDao.upsert(
                item.copy(
                    id = newItemId,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    accountId = newAccountId,
                ),
            )
            fieldDao.getForOwner(OWNER_ITEM, item.id).forEach { field ->
                copiedFields.add(field.asCopy(OWNER_ITEM, newItemId, now))
            }
        }
        if (copiedFields.isNotEmpty()) fieldDao.upsertAll(copiedFields)
        newAccountId
    }

    private fun AccountFieldEntity.asCopy(type: String, ownerId: String, now: Long): AccountFieldEntity =
        copy(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            isDeleted = false,
            deletedAt = null,
            ownerType = type,
            ownerId = ownerId,
        )

    private companion object {
        const val OWNER_ACCOUNT = "account"
        const val OWNER_ITEM = "item"
        const val CHUNK = 500
    }
}
