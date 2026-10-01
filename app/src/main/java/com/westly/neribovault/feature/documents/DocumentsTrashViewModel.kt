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
 * schedules the reminder again; Delete forever and Empty trash remove the attachment file, cancel
 * the reminder and then delete the document.
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
                removeForGood(repository.getById(id))
                repository.deletePermanently(id)
            }
        }
    }

    /** Permanently deletes every document that is in the trash right now, attachments included. */
    fun emptyTrash() {
        viewModelScope.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                repository.observeTrashed().first().forEach { document ->
                    removeForGood(document)
                    repository.deletePermanently(document.id)
                }
            }
        }
    }

    /** Deletes the attachment file and cancels the reminder of [document] (a no-op for null). */
    private fun removeForGood(document: PersonalDocumentEntity?) {
        if (document == null) return
        document.fileUri?.let { fileStore.delete(it) }
        DocumentReminderScheduler.forget(appContext, document.id)
    }
}
