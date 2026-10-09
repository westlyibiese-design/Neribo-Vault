package com.westly.neribovault.feature.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.di.AppContainer
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.feature.backup.formatBytes
import com.westly.neribovault.feature.documents.DocumentFileStore
import com.westly.neribovault.feature.memories.MemoryPhotoStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files that no record uses, and the space they take. */
class UnusedFiles(val files: List<File>, val bytes: Long)

/** What the Storage sheet shows. */
data class StorageUiState(
    val isLoading: Boolean = true,
    val databaseBytes: Long = 0L,
    val photoBytes: Long = 0L,
    val documentBytes: Long = 0L,
    val isBusy: Boolean = false,
    val message: String? = null,
    val confirmCleanup: UnusedFiles? = null,
    val confirmEmptyTrash: Boolean = false,
) {
    val totalBytes: Long get() = databaseBytes + photoBytes + documentBytes
}

/** Measures what Neribo Vault stores on the phone and tidies it up on request. */
class StorageViewModel(
    private val container: AppContainer,
    private val application: Application,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private val photoStore = MemoryPhotoStore(application)
    private val documentStore = DocumentFileStore(application)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) { measure() }
            _state.update {
                it.copy(
                    isLoading = false,
                    databaseBytes = sizes[0],
                    photoBytes = sizes[1],
                    documentBytes = sizes[2],
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- Clean up unused files -----------------------------------------------------------

    /** Looks for unused files and asks to confirm, or says there are none. */
    fun startCleanup() {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true, message = null) }
        viewModelScope.launch {
            try {
                val unused = withContext(Dispatchers.IO) { findUnusedFiles() }
                _state.update {
                    if (unused.files.isEmpty()) {
                        it.copy(isBusy = false, message = "No unused files found. Nothing to clean up.")
                    } else {
                        it.copy(isBusy = false, confirmCleanup = unused)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false, message = "Could not check your files. Please try again.") }
            }
        }
    }

    fun cancelCleanup() = _state.update { it.copy(confirmCleanup = null) }

    fun confirmCleanup() {
        val pending = _state.value.confirmCleanup ?: return
        _state.update { it.copy(confirmCleanup = null, isBusy = true) }
        viewModelScope.launch {
            val freed = withContext(NonCancellable + Dispatchers.IO) {
                // Look again: nothing that became used in the meantime may be deleted.
                val stillUnused = findUnusedFiles().files.map { it.absolutePath }.toSet()
                var bytes = 0L
                for (file in pending.files) {
                    if (file.absolutePath !in stillUnused) continue
                    val size = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) bytes += size
                }
                bytes
            }
            _state.update { it.copy(isBusy = false, message = "Freed ${formatBytes(freed)}.") }
            refresh()
        }
    }

    // ---- Empty all trash -----------------------------------------------------------------

    fun askEmptyTrash() = _state.update { it.copy(confirmEmptyTrash = true) }

    fun cancelEmptyTrash() = _state.update { it.copy(confirmEmptyTrash = false) }

    fun confirmEmptyTrash() {
        _state.update { it.copy(confirmEmptyTrash = false, isBusy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                withContext(NonCancellable) { emptyEverything() }
                "Trash emptied."
            } catch (e: Exception) {
                "Some items could not be removed. Please try again."
            }
            _state.update { it.copy(isBusy = false, message = message) }
            refresh()
        }
    }

    private suspend fun emptyEverything() {
        // Files go first, like the vaults' own "Empty trash"; then the rows.
        val memories = container.memoriesRepository.observeTrashed().first()
        val documents = container.personalDocumentsRepository.observeTrashed().first()
        memories.forEach { photoStore.deleteFilesFor(it.photoUris) }
        documents.forEach { document ->
            val path = document.fileUri
            if (!path.isNullOrEmpty()) documentStore.deleteFiles(listOf(path))
        }
        container.notesRepository.observeTrashed().first().forEach { container.notesRepository.deletePermanently(it.id, "Settings > Storage: empty the trash") }
        container.ideasRepository.observeTrashed().first().forEach { container.ideasRepository.deletePermanently(it.id) }
        container.goalsRepository.observeTrashed().first().forEach { container.goalsRepository.deletePermanently(it.id) }
        container.diaryRepository.observeTrashed().first().forEach { container.diaryRepository.deletePermanently(it.id) }
        container.storiesRepository.observeTrashed().first().forEach { container.storiesRepository.deletePermanently(it.id) }
        container.storyCharactersRepository.observeTrashed().first()
            .forEach { container.storyCharactersRepository.deletePermanently(it.id) }
        container.storyNotesRepository.observeTrashed().first()
            .forEach { container.storyNotesRepository.deletePermanently(it.id) }
        container.writingIdeasRepository.observeTrashed().first()
            .forEach { container.writingIdeasRepository.deletePermanently(it.id) }
        container.postsRepository.observeTrashed().first().forEach { container.postsRepository.deletePermanently(it.id) }
        container.churchRepository.observeTrashed().first().forEach { container.churchRepository.deletePermanently(it.id) }
        container.screenplaysRepository.observeTrashed().first()
            .forEach { container.screenplaysRepository.deletePermanently(it.id) }
        container.songsRepository.observeTrashed().first().forEach { container.songsRepository.deletePermanently(it.id) }
        container.accountsRepository.observeTrashed().first().forEach { container.accountsRepository.deletePermanently(it.id) }
        container.accountItemsRepository.observeTrashed().first().forEach { container.accountItemsRepository.deletePermanently(it.id) }
        memories.forEach { container.memoriesRepository.deletePermanently(it.id) }
        documents.forEach { container.personalDocumentsRepository.deletePermanently(it.id) }
        container.projectsRepository.observeTrashed().first().forEach { container.projectsRepository.deletePermanently(it.id) }
        container.secretsRepository.observeTrashed().first().forEach { container.secretsRepository.deletePermanently(it.id) }
        container.bugsRepository.observeTrashed().first().forEach { container.bugsRepository.deletePermanently(it.id) }
        container.tasksRepository.observeTrashed().first().forEach { container.tasksRepository.deletePermanently(it.id) }
        container.planningDocsRepository.observeTrashed().first()
            .forEach { container.planningDocsRepository.deletePermanently(it.id) }
        container.folderPlansRepository.observeTrashed().first()
            .forEach { container.folderPlansRepository.deletePermanently(it.id) }
        container.promptsRepository.observeTrashed().first().forEach { container.promptsRepository.deletePermanently(it.id) }
        container.projectDocumentsRepository.observeTrashed().first()
            .forEach { container.projectDocumentsRepository.deletePermanently(it.id) }
        // Trashed chapters have no screen of their own; this removes them (and any other
        // leftovers that are in the trash) with everything else.
        container.storiesRepository.purgeTrashedBefore(Long.MAX_VALUE)
    }

    // ---- Measuring -----------------------------------------------------------------------

    /** Database, memory photos and document files, in bytes. */
    private fun measure(): LongArray {
        val dbFile = application.getDatabasePath(NeriboDatabase.FILE_NAME)
        val database = dbFile.length() +
            File(dbFile.path + "-wal").length() +
            File(dbFile.path + "-shm").length() +
            File(dbFile.path + "-journal").length()
        return longArrayOf(
            database,
            folderBytes(File(application.filesDir, "memories")),
            folderBytes(File(application.filesDir, "documents")),
        )
    }

    private fun folderBytes(folder: File): Long =
        folder.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Files in the two file folders that no memory or document row refers to, **trashed rows
     * included**. Half-written `.part` files and files saved within the last few minutes are
     * never touched, because an editor may still be about to save them.
     */
    private suspend fun findUnusedFiles(): UnusedFiles {
        val memoryNames = HashSet<String>()
        val documentNames = HashSet<String>()
        val memories = container.memoriesRepository.observeAll().first() +
            container.memoriesRepository.observeTrashed().first()
        for (memory in memories) {
            memory.photoUris.forEach { path -> memoryNames.add(nameOf(path)) }
        }
        val documents = container.personalDocumentsRepository.observeAll().first() +
            container.personalDocumentsRepository.observeTrashed().first()
        for (document in documents) {
            val path = document.fileUri
            if (!path.isNullOrEmpty()) documentNames.add(nameOf(path))
        }
        val unused = ArrayList<File>()
        unused.addAll(unreferenced(File(application.filesDir, "memories"), memoryNames))
        unused.addAll(unreferenced(File(application.filesDir, "documents"), documentNames))
        return UnusedFiles(unused, unused.sumOf { it.length() })
    }

    private fun unreferenced(folder: File, referenced: Set<String>): List<File> {
        val cutoff = System.currentTimeMillis() - MIN_AGE_MILLIS
        return folder.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in referenced && it.lastModified() < cutoff }
            .orEmpty()
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private companion object {
        /** Files newer than this are left alone by the cleanup. */
        const val MIN_AGE_MILLIS = 5L * 60L * 1000L
    }
}
