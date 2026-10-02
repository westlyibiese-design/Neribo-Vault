package com.westly.neribovault.feature.documents

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import com.westly.neribovault.feature.documents.reminders.DocumentReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The deleted documents, most recently deleted first. */
data class DocumentsTrashUiState(
    val documents: List<PersonalDocumentEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * State and actions for the Documents "Recently deleted" screen. Restore keeps the attachment and
 * schedules the reminder again. Delete forever and Empty trash delete the document record first
 * and then its file (a crash in between leaves a harmless stray file, never a record that points at
 * a missing one). This works for the encrypted `.nvenc` files as well as the older photos and PDFs.
 */
class DocumentsTrashViewModel(
    private val appContext: Context,
    private val repository: PersonalDocumentsRepository,
    private val fileStore: DocumentFileStore,
) : ViewModel() {

    val state: StateFlow<DocumentsTrashUiState> = repository.observeTrashed()
        .map { documents -> DocumentsTrashUiState(documents = documents, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentsTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch {
            repository.restore(id)
            val document = repository.getById(id)
            if (document != null) DocumentReminderScheduler.sync(appContext, document)
        }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                val document = repository.getById(id)
                repository.deletePermanently(id)
                removeForGood(document)
            }
        }
    }

    /** Permanently deletes every document that is in the trash right now, attachments included. */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                repository.observeTrashed().first().forEach { document ->
                    repository.deletePermanently(document.id)
                    removeForGood(document)
                }
            }
        }
    }

    /**
     * Deletes the attachment file and cancels the reminder of [document] (a no-op for null). The
     * file store only deletes a file that sits directly inside `filesDir/documents/`, whatever the
     * stored path says, and a failure to delete never stops anything else.
     */
    private fun removeForGood(document: PersonalDocumentEntity?) {
        if (document == null) return
        val path = document.fileUri
        if (!path.isNullOrBlank()) runCatching { fileStore.delete(path) }
        runCatching { DocumentReminderScheduler.forget(appContext, document.id) }
    }
}
