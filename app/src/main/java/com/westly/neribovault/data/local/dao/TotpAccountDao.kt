package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [TotpAccountEntity]. */
@Dao
interface TotpAccountDao {
    @Query(
        "SELECT * FROM totp_accounts WHERE isDeleted = 0 " +
            "ORDER BY isPinned DESC, sortOrder ASC, issuer COLLATE NOCASE ASC, accountName COLLATE NOCASE ASC",
    )
    fun observeAll(): Flow<List<TotpAccountEntity>>

    @Query("SELECT * FROM totp_accounts WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<TotpAccountEntity?>

    @Query("SELECT * FROM totp_accounts WHERE id = :id")
    suspend fun getById(id: String): TotpAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TotpAccountEntity)

    @Query("UPDATE totp_accounts SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE totp_accounts SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM totp_accounts WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<TotpAccountEntity>>

    @Query("DELETE FROM totp_accounts WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM totp_accounts WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT * FROM totp_accounts")
    suspend fun getAllIncludingTrashed(): List<TotpAccountEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM totp_accounts")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE totp_accounts SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}
