package com.westly.neribovault.data.repository

import com.westly.neribovault.data.local.NoteDeleteTrace
import com.westly.neribovault.data.local.dao.NoteDao
import com.westly.neribovault.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Notes: pinned first, then most recently edited.
 *
 * Deletes take a [reason] that is written to the note trace (Settings > Diagnostics), so a note
 * that disappears can be traced back to the code path that removed it.
 */
class NotesRepository(
    private val dao: NoteDao,
    private val trace: NoteDeleteTrace? = null,
) {
    fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()

    fun observeById(id: String): Flow<NoteEntity?> = dao.observeById(id)

    suspend fun getById(id: String): NoteEntity? = dao.getById(id)

    /** Inserts or replaces [item], stamping `updatedAt` with the current time. */
    suspend fun upsert(item: NoteEntity) {
        dao.upsert(item.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Moves a note to Recently deleted. Stamps `updatedAt` so the change reaches the cloud. */
    suspend fun softDelete(id: String, reason: String = "Moved to trash (caller did not say why)") {
        traced(reason) { dao.softDelete(id, System.currentTimeMillis()) }
    }

    /** Brings a note back from Recently deleted. Stamps `updatedAt` so the change reaches the cloud. */
    suspend fun restore(id: String) {
        dao.restore(id, System.currentTimeMillis())
    }

    fun observeTrashed(): Flow<List<NoteEntity>> = dao.observeTrashed()

    suspend fun deletePermanently(id: String, reason: String = "Permanent delete (caller did not say why)") {
        traced(reason) { dao.deletePermanently(id) }
    }

    suspend fun purgeTrashedBefore(
        cutoffMillis: Long,
        reason: String = "Clean-up of old trash (caller did not say why)",
    ) {
        traced(reason) { dao.deleteTrashedBefore(cutoffMillis) }
    }

    fun observeActive(): Flow<List<NoteEntity>> = dao.observeActive()

    fun observeArchived(): Flow<List<NoteEntity>> = dao.observeArchived()

    fun search(query: String): Flow<List<NoteEntity>> = dao.search(query)

    suspend fun setPinned(id: String, pinned: Boolean) {
        dao.setPinned(id, pinned, System.currentTimeMillis())
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        dao.setArchived(id, archived, System.currentTimeMillis())
    }

    private suspend fun traced(reason: String, block: suspend () -> Unit) {
        if (trace == null) block() else trace.because(reason, block)
    }
}
