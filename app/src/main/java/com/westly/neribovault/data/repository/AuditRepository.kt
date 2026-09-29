package com.westly.neribovault.data.repository

import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.dao.AuditLogDao
import com.westly.neribovault.data.local.entity.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/** Append-only log of important actions. */
class AuditRepository(private val dao: AuditLogDao) {
    suspend fun log(action: String, entityType: String, entityId: String?) {
        dao.insert(
            AuditLogEntity(
                id = newId(),
                action = action,
                entityType = entityType,
                entityId = entityId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun observeRecent(limit: Int = 100): Flow<List<AuditLogEntity>> = dao.observeRecent(limit)

    suspend fun clear() {
        dao.clear()
    }
}
