package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.SocialPostEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [SocialPostEntity]. */
@Dao
interface SocialPostDao {
    @Query("SELECT * FROM social_posts WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE id = :id AND isDeleted = 0")
    fun observeById(id: String): Flow<SocialPostEntity?>

    @Query("SELECT * FROM social_posts WHERE id = :id")
    suspend fun getById(id: String): SocialPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SocialPostEntity)

    @Query("UPDATE social_posts SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE social_posts SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("SELECT * FROM social_posts WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<SocialPostEntity>>

    @Query("DELETE FROM social_posts WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("DELETE FROM social_posts WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashedBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM social_posts WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM social_posts WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR caption LIKE '%' || :query || '%' OR hashtags LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE isDeleted = 0 AND status = :status ORDER BY updatedAt DESC")
    fun observeByStatus(status: String): Flow<List<SocialPostEntity>>

    @Query("SELECT * FROM social_posts WHERE isDeleted = 0 AND status = 'scheduled' ORDER BY scheduledAt ASC")
    fun observeScheduled(): Flow<List<SocialPostEntity>>

    @Query("UPDATE social_posts SET status = :status, postedAt = :postedAt, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, postedAt: Long?, now: Long)
}
