package com.westly.neribovault.data.repository

import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.dao.CoderProjectDao
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderStarterFile
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Coder projects: the row in Room and the project's folder on disk are always changed together.
 * A folder is written before its row is inserted and deleted before its row is removed, so a crash
 * never leaves a row that points at nothing.
 */
class CoderProjectsRepository(
    private val dao: CoderProjectDao,
    private val files: CoderFileStore,
) {
    fun observeAll(): Flow<List<CoderProjectEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<CoderProjectEntity?> = dao.observeById(id)

    suspend fun getById(id: String): CoderProjectEntity? = dao.getById(id)

    /**
     * Writes the starter files, then inserts the row. Returns null if anything failed (nothing is left
     * behind). If the project folder already exists and [starterFiles] is empty, only the row is
     * inserted (used by ZIP import).
     */
    suspend fun createProject(
        name: String,
        templateId: String,
        starterFiles: List<CoderStarterFile>,
        id: String = newId(),
    ): CoderProjectEntity? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > CoderLimits.MAX_PROJECT_NAME_LENGTH) return@withContext null
        val folder = files.resolve(id, "") ?: return@withContext null
        if (starterFiles.isEmpty()) {
            if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) return@withContext null
        } else {
            for (starter in starterFiles) {
                if (files.write(id, starter.path, starter.content) != CoderWriteResult.Saved) {
                    files.deleteProject(id)
                    return@withContext null
                }
            }
        }
        val now = System.currentTimeMillis()
        val row = CoderProjectEntity(
            id = id,
            createdAt = now,
            updatedAt = now,
            name = trimmed,
            templateId = templateId,
        )
        try {
            dao.upsert(row)
            row
        } catch (e: CancellationException) {
            files.deleteProject(id)
            throw e
        } catch (e: Exception) {
            files.deleteProject(id)
            null
        }
    }

    /** Renames the project. A blank or over-long name is ignored. */
    suspend fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.length > CoderLimits.MAX_PROJECT_NAME_LENGTH) return
        val row = dao.getById(id) ?: return
        dao.upsert(row.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
    }

    /** Marks the project as just changed, so it moves to the top of the list. */
    suspend fun touch(id: String) {
        val row = dao.getById(id) ?: return
        dao.upsert(row.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: String) {
        dao.restore(id)
    }

    fun observeTrashed(): Flow<List<CoderProjectEntity>> = dao.observeTrashed()

    /** Links the project to the GitHub repository [owner]/[name] and the branch [branch] it is pushed to. */
    suspend fun setRepoLink(id: String, owner: String, name: String, branch: String) {
        dao.setRepoLink(id, owner, name, branch, System.currentTimeMillis())
    }

    /** Removes the GitHub link. The files of the project and the repository itself are not touched. */
    suspend fun clearRepoLink(id: String) {
        dao.setRepoLink(id, null, null, null, System.currentTimeMillis())
    }

    /** Records that the project was just pushed to GitHub. */
    suspend fun markPushed(id: String) {
        dao.markPushed(id, System.currentTimeMillis())
    }

    /** Deletes the project's folder first, then its row. */
    suspend fun deletePermanently(id: String) {
        withContext(Dispatchers.IO) { files.deleteProject(id) }
        dao.deletePermanently(id)
    }

    /** Permanently removes every project that has been in the trash since before [cutoffMillis]: folders first, then rows. */
    suspend fun purgeTrashedBefore(cutoffMillis: Long) {
        val ids = dao.trashedIdsBefore(cutoffMillis)
        withContext(Dispatchers.IO) { ids.forEach { files.deleteProject(it) } }
        dao.deleteTrashedBefore(cutoffMillis)
    }
}
