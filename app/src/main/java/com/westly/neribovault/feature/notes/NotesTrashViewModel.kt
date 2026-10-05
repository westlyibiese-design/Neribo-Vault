package com.westly.neribovault.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.NoteEntity
import com.westly.neribovault.data.repository.NotesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted notes, most recently deleted first. */
data class NotesTrashUiState(
    val notes: List<NoteEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Notes "Recently deleted" screen. */
class NotesTrashViewModel(private val repository: NotesRepository) : ViewModel() {

    val state: StateFlow<NotesTrashUiState> = repository.observeTrashed()
        .map { notes -> NotesTrashUiState(notes = notes, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesTrashUiState())

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.deletePermanently(id, "Recently deleted: Delete forever") }
    }

    /** Permanently deletes every note that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { note ->
                repository.deletePermanently(note.id, "Recently deleted: Empty all")
            }
        }
    }
}
