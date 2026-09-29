package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [PersonalDocumentEntity]. */
@Dao
interface PersonalDocumentDao {
    @Query("SELECT * FROM personal_documents WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PersonalDocumentEntity>>

    @Query("SELECT * FROM personal_documents WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<PersonalDocumentEntity?>

    @Query("SELECT * FROM personal_documents WHERE id = :id")
    suspend fun getById(id: String): PersonalDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PersonalDocumentEntity)

    @Query("UPDATE personal_documents SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE personal_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM personal_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<PersonalDocumentEntity>>

    @Query("DELETE FROM personal_documents WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM personal_documents WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM personal_documents WHERE isDeleted = 0")
    suspend fun countActive(): Int
}
