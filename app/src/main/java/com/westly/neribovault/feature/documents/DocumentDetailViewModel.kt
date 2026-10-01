package com.westly.neribovault.feature.documents

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import com.westly.neribovault.feature.documents.reminders.DocumentReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the document detail screen (and the attachment viewer) draws. */
data class DocumentDetailUiState(
    val document: PersonalDocumentEntity? = null,
    val isLoading: Boolean = true,
    /** True once we know the document is missing or in the trash. */
    val notFound: Boolean = false,
)

/** State and actions for reading one document. */
class DocumentDetailViewModel(
    private val appContext: Context,
    private val documentId: String,
    private val repository: PersonalDocumentsRepository,
) : ViewModel() {

    val state: StateFlow<DocumentDetailUiState> = repository.observeById(documentId)
        .map { document ->
            DocumentDetailUiState(document = document, isLoading = false, notFound = document == null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentDetailUiState())

    /** Soft-deletes the document (its file stays on disk for Restore), cancels its reminder, then reports its id. */
    fun delete(onDone: (deletedId: String) -> Unit) {
        viewModelScope.launch {
            repository.softDelete(documentId)
            DocumentReminderScheduler.cancel(appContext, documentId)
            onDone(documentId)
        }
    }
}
