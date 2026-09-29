package com.westly.neribovault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import kotlinx.coroutines.flow.Flow

/** Database access for [GoalMilestoneEntity]. Milestones are hard-deleted. */
@Dao
interface GoalMilestoneDao {
    @Query("SELECT * FROM goal_milestones WHERE goalId = :goalId ORDER BY sortOrder ASC")
    fun observeForGoal(goalId: String): Flow<List<GoalMilestoneEntity>>

    @Query("SELECT * FROM goal_milestones ORDER BY goalId ASC, sortOrder ASC")
    fun observeAll(): Flow<List<GoalMilestoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(milestone: GoalMilestoneEntity)

    @Query("DELETE FROM goal_milestones WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE goal_milestones SET isDone = :done, doneAt = :doneAt, updatedAt = :now WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean, doneAt: Long?, now: Long)

    @Query("DELETE FROM goal_milestones WHERE goalId = :goalId")
    suspend fun deleteForGoal(goalId: String)

    @Query("DELETE FROM goal_milestones WHERE goalId IN (SELECT id FROM goals WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff)")
    suspend fun deleteForTrashedGoalsBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM goal_milestones")
    suspend fun countAll(): Int
}
