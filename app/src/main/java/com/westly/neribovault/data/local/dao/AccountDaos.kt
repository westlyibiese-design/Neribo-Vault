package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.AccountEntity
import com.westly.neribovault.data.local.entity.AccountFieldEntity
import com.westly.neribovault.data.local.entity.AccountItemEntity
import kotlinx.coroutines.flow.Flow

/** The number of non-deleted items under one account. */
data class AccountItemCount(val accountId: String, val total: Int)

/** Database access for [AccountEntity]. */
@Dao
interface AccountDao {
    @Query("SELECT * FROM platform_accounts WHERE isDeleted = 0 ORDER BY isPinned DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM platform_accounts WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<AccountEntity?>

    @Query("SELECT * FROM platform_accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM platform_accounts")
    suspend fun getAllIncludingTrashed(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AccountEntity)

    @Query("UPDATE platform_accounts SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE platform_accounts SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM platform_accounts WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<AccountEntity>>

    @Query("DELETE FROM platform_accounts WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("SELECT id FROM platform_accounts WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedIdsBefore(cutoff: Long): List<String>

    @Query("DELETE FROM platform_accounts WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("UPDATE platform_accounts SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}

/** Database access for [AccountItemEntity]. */
@Dao
interface AccountItemDao {
    @Query("SELECT * FROM account_items WHERE isDeleted = 0 ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<AccountItemEntity>>

    @Query("SELECT * FROM account_items WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<AccountItemEntity?>

    @Query("SELECT * FROM account_items WHERE id = :id")
    suspend fun getById(id: String): AccountItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AccountItemEntity)

    @Query("UPDATE account_items SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE account_items SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM account_items WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<AccountItemEntity>>

    @Query("DELETE FROM account_items WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("SELECT id FROM account_items WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedIdsBefore(cutoff: Long): List<String>

    @Query("DELETE FROM account_items WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT * FROM account_items WHERE accountId = :accountId AND isDeleted = 0 ORDER BY name COLLATE NOCASE ASC")
    fun observeForAccount(accountId: String): Flow<List<AccountItemEntity>>

    @Query("SELECT * FROM account_items WHERE accountId = :accountId AND isDeleted = 0 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getForAccount(accountId: String): List<AccountItemEntity>

    @Query("SELECT id FROM account_items WHERE accountId = :accountId")
    suspend fun idsForAccountIncludingTrashed(accountId: String): List<String>

    @Query("SELECT accountId AS accountId, COUNT(*) AS total FROM account_items WHERE isDeleted = 0 GROUP BY accountId")
    fun observeCounts(): Flow<List<AccountItemCount>>

    @Query("DELETE FROM account_items WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

/** Database access for [AccountFieldEntity]. */
@Dao
interface AccountFieldDao {
    @Query("SELECT * FROM account_fields WHERE isDeleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<AccountFieldEntity>>

    @Query("SELECT * FROM account_fields WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<AccountFieldEntity?>

    @Query("SELECT * FROM account_fields WHERE id = :id")
    suspend fun getById(id: String): AccountFieldEntity?

    @Query("SELECT * FROM account_fields")
    suspend fun getAllIncludingTrashed(): List<AccountFieldEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: AccountFieldEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AccountFieldEntity>)

    @Query("UPDATE account_fields SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE account_fields SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM account_fields WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<AccountFieldEntity>>

    @Query("DELETE FROM account_fields WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM account_fields WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT * FROM account_fields WHERE ownerType = :ownerType AND ownerId = :ownerId AND isDeleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun observeForOwner(ownerType: String, ownerId: String): Flow<List<AccountFieldEntity>>

    @Query("SELECT * FROM account_fields WHERE ownerType = :ownerType AND ownerId = :ownerId AND isDeleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getForOwner(ownerType: String, ownerId: String): List<AccountFieldEntity>

    @Query("DELETE FROM account_fields WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteForOwner(ownerType: String, ownerId: String)

    @Query("DELETE FROM account_fields WHERE ownerId IN (:ownerIds)")
    suspend fun deleteForOwnerIds(ownerIds: List<String>)
}
