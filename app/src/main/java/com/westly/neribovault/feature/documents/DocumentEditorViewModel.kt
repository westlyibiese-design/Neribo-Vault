package com.westly.neribovault.feature.documents

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import com.westly.neribovault.feature.documents.reminders.DocumentReminderScheduler
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor shows besides the text fields, which the screen edits directly. */
data class DocumentEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val issueDate: Long? = null,
    val expiryDate: Long? = null,
    val remindDaysBefore: Int = DEFAULT_REMIND_DAYS,
    val attachmentPath: String? = null,
    /** True while a picked file is being copied into private storage. */
    val isImporting: Boolean = false,
    val isSaving: Boolean = false,
    val titleError: String? = null,
    val dateError: String? = null,
)

/** Everything the owner can change, for telling whether anything is unsaved. */
private data class Snapshot(
    val title: String,
    val category: String,
    val issuer: String,
    val notes: String,
    val issueDate: Long?,
    val expiryDate: Long?,
    val remindDaysBefore: Int,
    val attachmentPath: String?,
)

/**
 * Loads one document (or prepares a new one) for a deliberate form with an explicit Save. Nothing
 * is written until Save succeeds, and a brand-new document the owner leaves untouched is simply
 * never created.
 *
 * Attachments are copied into private storage as soon as they are picked. A file that was only
 * imported in this session is deleted when it is replaced, removed or the editor is left without
 * saving; the file that was already saved is deleted only after a Save that no longer lists it.
 */
class DocumentEditorViewModel(
    private val appContext: Context,
    private val documentId: String,
    private val repository: PersonalDocumentsRepository,
    private val fileStore: DocumentFileStore,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = documentId == DocumentsRoutes.NEW_DOCUMENT_ID

    private val _state = MutableStateFlow(DocumentEditorUiState(isLoaded = isNew))
    val state: StateFlow<DocumentEditorUiState> = _state.asStateFlow()

    private var title: String = ""
    private var category: String = ""
    private var issuer: String = ""
    private var notes: String = ""

    /** The latest text of each field, even before it has been saved. */
    val currentTitle: String get() = title
    val currentCategory: String get() = category
    val currentIssuer: String get() = issuer
    val currentNotes: String get() = notes

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Gentle one-off messages (for example a refused file), each delivered once. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private var currentId: String? = if (isNew) null else documentId
    private var createdAt: Long = 0L

    /** The attachment path stored in the database right now (null before the first save). */
    private var savedPath: String? = null

    /** Files imported in this session that no saved document refers to yet. */
    private val sessionImports: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    private var initial: Snapshot = snapshot()

    @Volatile
    private var cleared = false

    // File copies and database writes must finish even when the screen is already gone and
    // viewModelScope is cancelled, so they run in their own scope.
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val document = repository.getById(documentId)
            if (document == null || document.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = document.createdAt
            title = document.title
            category = document.category
            issuer = document.issuer
            notes = document.notes
            savedPath = document.fileUri
            _state.update {
                it.copy(
                    isLoaded = true,
                    issueDate = document.issueDate,
                    expiryDate = document.expiryDate,
                    remindDaysBefore = document.remindDaysBefore,
                    attachmentPath = document.fileUri,
                )
            }
            initial = snapshot()
        }
    }

    private fun snapshot(): Snapshot {
        val s = _state.value
        return Snapshot(
            title = title,
            category = category,
            issuer = issuer,
            notes = notes,
            issueDate = s.issueDate,
            expiryDate = s.expiryDate,
            remindDaysBefore = s.remindDaysBefore,
            attachmentPath = s.attachmentPath,
        )
    }

    /** True when anything differs from what was loaded (or from a blank new document). */
    fun hasUnsavedChanges(): Boolean = snapshot() != initial

    fun onTitleChange(value: String) {
        if (value == title) return
        title = value
        if (value.isNotBlank() && _state.value.titleError != null) {
            _state.update { it.copy(titleError = null) }
        }
    }

    fun onCategoryChange(value: String) {
        category = value
    }

    fun onIssuerChange(value: String) {
        issuer = value
    }

    fun onNotesChange(value: String) {
        notes = value
    }

    fun setIssueDate(value: Long?) {
        _state.update { it.copy(issueDate = value, dateError = dateErrorFor(value, it.expiryDate)) }
    }

    fun setExpiryDate(value: Long?) {
        _state.update { it.copy(expiryDate = value, dateError = dateErrorFor(it.issueDate, value)) }
    }

    fun setRemindDays(days: Int) {
        _state.update { it.copy(remindDaysBefore = days) }
    }

    private fun dateErrorFor(issue: Long?, expiry: Long?): String? =
        if (issue != null && expiry != null && expiry < issue) {
            "The expiry date can't be before the issue date."
        } else {
            null
        }

    /**
     * Copies the picked photo or PDF into private storage (never blocking the screen) and makes it
     * the attachment. A file that is refused leaves the current attachment alone and explains why.
     */
    fun attach(uri: Uri) {
        if (_state.value.isImporting) return
        _state.update { it.copy(isImporting = true) }
        workScope.launch {
            when (val result = fileStore.importAttachmentChecked(uri)) {
                is ImportResult.Success -> {
                    val path = result.file.path
                    if (cleared) {
                        // The editor was closed while the file was being copied.
                        fileStore.delete(path)
                        return@launch
                    }
                    sessionImports.add(path)
                    val old = _state.value.attachmentPath
                    // A file imported in this session was never saved, so it can go right away.
                    if (old != null && sessionImports.remove(old)) fileStore.delete(old)
                    _state.update { it.copy(attachmentPath = path, isImporting = false) }
                }
                is ImportResult.Rejected -> {
                    _state.update { it.copy(isImporting = false) }
                    messageChannel.trySend(result.message)
                }
            }
        }
    }

    /** Takes the file off the document. A saved file is deleted once the document is saved without it. */
    fun removeAttachment() {
        val old = _state.value.attachmentPath ?: return
        if (sessionImports.remove(old)) {
            workScope.launch { fileStore.delete(old) }
        }
        _state.update { it.copy(attachmentPath = null) }
    }

    /**
     * Checks the form and, when it is valid, saves the document and its reminder. [onSaved] is
     * told whether a reminder is now scheduled, so the screen can ask for the notification
     * permission the first time. Invalid forms show inline messages and save nothing.
     */
    fun save(onSaved: (reminderScheduled: Boolean) -> Unit) {
        val current = _state.value
        if (current.isSaving || current.isImporting) return
        val titleError = if (title.isBlank()) "Give this document a title." else null
        val dateError = dateErrorFor(current.issueDate, current.expiryDate)
        if (titleError != null || dateError != null) {
            _state.update { it.copy(titleError = titleError, dateError = dateError) }
            return
        }
        _state.update { it.copy(isSaving = true, titleError = null, dateError = null) }
        viewModelScope.launch {
            val scheduled = withContext(Dispatchers.IO + NonCancellable) { persist() }
            _state.update { it.copy(isSaving = false) }
            onSaved(scheduled)
        }
    }

    /** Writes the form, syncs the reminder and removes files the saved document no longer uses. */
    private suspend fun persist(): Boolean {
        val now = System.currentTimeMillis()
        val s = _state.value
        val id = currentId ?: newId().also {
            currentId = it
            createdAt = now
        }
        val entity = PersonalDocumentEntity(
            id = id,
            createdAt = createdAt,
            updatedAt = now,
            title = title.trim().take(MAX_TEXT_LENGTH),
            category = category.trim().take(MAX_TEXT_LENGTH),
            issuer = issuer.trim().take(MAX_TEXT_LENGTH),
            issueDate = s.issueDate,
            expiryDate = s.expiryDate,
            remindDaysBefore = s.remindDaysBefore,
            fileUri = s.attachmentPath,
            notes = notes.trim(),
        )
        repository.upsert(entity)
        DocumentReminderScheduler.sync(appContext, entity)

        // Only now that the document is saved without them are the old files really gone.
        val keep = s.attachmentPath
        val doomed = HashSet<String>()
        savedPath?.let { doomed.add(it) }
        doomed.addAll(sessionImports)
        doomed.remove(keep)
        doomed.forEach { fileStore.delete(it) }
        sessionImports.clear()
        savedPath = keep
        initial = snapshot()

        val expiry = s.expiryDate
        return expiry != null &&
            expiryState(expiry, s.remindDaysBefore, now) != ExpiryState.Expired
    }

    /**
     * Soft-deletes the saved document (its file stays on disk so Undo keeps it) and reports its id,
     * or null when it was never saved. Unsaved edits are dropped.
     */
    fun deleteDocument(onDone: (deletedId: String?) -> Unit) {
        viewModelScope.launch {
            val id = withContext(NonCancellable) {
                val target = currentId
                if (target != null) {
                    repository.softDelete(target)
                    DocumentReminderScheduler.cancel(appContext, target)
                }
                target
            }
            onDone(id)
        }
    }

    override fun onCleared() {
        // Files picked in this session that were never saved would otherwise be left behind.
        cleared = true
        val orphans = sessionImports.toList()
        sessionImports.clear()
        if (orphans.isNotEmpty()) {
            workScope.launch { orphans.forEach { fileStore.delete(it) } }
        }
        super.onCleared()
    }
}
