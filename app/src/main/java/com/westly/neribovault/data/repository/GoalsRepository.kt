package com.westly.neribovault.data.repository

import androidx.room.withTransaction
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.dao.GoalDao
import com.westly.neribovault.data.local.dao.GoalMilestoneDao
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import kotlinx.coroutines.flow.Flow

/** Goals and their milestones. */
class GoalsRepository(
    private val database: NeriboDatabase,
    private val dao: GoalDao,
    private val milestoneDao: GoalMilestoneDao,
) {
    fun observeAll(): Flow<List<GoalEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<GoalEntity?> = dao.observeById(id)

    suspend fun getById(id: String): GoalEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: GoalEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<GoalEntity>> = dao.observeTrashed()

    /** Removes the goal and all of its milestones. */
    suspend fun deletePermanently(id: String) {
        database.withTransaction {
            milestoneDao.deleteForGoal(id)
            dao.deletePermanently(id)
        }
    }

    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        database.withTransaction {
            milestoneDao.deleteForTrashedGoalsBefore(cutoffMillis)
            dao.deleteTrashedBefore(cutoffMillis)
        }
    }

    fun observeMilestones(goalId: String): Flow<List<GoalMilestoneEntity>> = milestoneDao.observeForGoal(goalId)

    fun observeAllMilestones(): Flow<List<GoalMilestoneEntity>> = milestoneDao.observeAll()

    suspend fun upsertMilestone(milestone: GoalMilestoneEntity) {
        milestoneDao.upsert(milestone.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteMilestone(id: String) {
        milestoneDao.delete(id)
    }

    suspend fun setMilestoneDone(id: String, done: Boolean) {
        val now = System.currentTimeMillis()
        milestoneDao.setDone(id, done, if (done) now else null, now)
    }

    /** Sets the status; `completedAt` is set when completed and cleared otherwise. */
    suspend fun setStatus(id: String, status: String) {
        val now = System.currentTimeMillis()
        dao.setStatus(id, status, if (status == "completed") now else null, now)
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
    }
}
