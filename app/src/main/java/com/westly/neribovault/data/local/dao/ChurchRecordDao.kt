package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [ChurchRecordEntity]. */
@Dao
interface ChurchRecordDao {
    @Query("SELECT * FROM church_records WHERE isDeleted = 0 ORDER BY isPinned DESC, recordDate DESC")
    fun observeAll(): Flow<List<ChurchRecordEntity>>

    @Query("SELECT * FROM church_records WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<ChurchRecordEntity?>

    @Query("SELECT * FROM church_records WHERE id = :id")
    suspend fun getById(id: String): ChurchRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChurchRecordEntity)

    @Query("UPDATE church_records SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE church_records SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM church_records WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<ChurchRecordEntity>>

    @Query("DELETE FROM church_records WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM church_records WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM church_records WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM church_records WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR speaker LIKE '%' || :query || '%' OR church LIKE '%' || :query || '%' OR scriptureRefs LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR notes LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') ORDER BY isPinned DESC, recordDate DESC")
    fun search(query: String): Flow<List<ChurchRecordEntity>>

    @Query("UPDATE church_records SET isPinned = :value, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, value: Boolean, now: Long)
}
