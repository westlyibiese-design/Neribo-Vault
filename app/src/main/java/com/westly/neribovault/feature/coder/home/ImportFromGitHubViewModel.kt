package com.westly.neribovault.feature.coder.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.github.CoderGitHubRepos
import com.westly.neribovault.feature.coder.github.CoderGitHubResult
import com.westly.neribovault.feature.coder.github.CoderGitHubSettings
import com.westly.neribovault.feature.coder.github.CoderZipballResult
import com.westly.neribovault.feature.coder.github.GitHubRepoDetails
import com.westly.neribovault.feature.coder.github.GitHubRepoSummary
import com.westly.neribovault.feature.coder.github.parseRepoRef
import com.westly.neribovault.feature.coder.zip.CoderImportReport
import com.westly.neribovault.feature.coder.zip.CoderImportResult
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import com.westly.neribovault.feature.coder.zip.importStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/** What the import is doing right now. */
enum class ImportPhase { Idle, Checking, Downloading, Unpacking }

/** A finished import: the new project and what the unpacking left out. */
data class GitHubImportDone(val projectId: String, val report: CoderImportReport)

/**
 * Everything the Import from GitHub screen shows. [repos] is the full list (at most 100); the screen shows
 * [CoderGitHubRepos.filterRepos] of it for [filter]. [downloadedBytes] and [totalBytes] only mean something while
 * [phase] is [ImportPhase.Downloading]; [totalBytes] is -1 when GitHub did not announce the size.
 */
data class ImportFromGitHubUiState(
    val connected: Boolean = false,
    val reference: String = "",
    val filter: String = "",
    val repos: List<GitHubRepoSummary> = emptyList(),
    val reposLoading: Boolean = false,
    val reposMessage: String? = null,
    val phase: ImportPhase = ImportPhase.Idle,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val error: String? = null,
    val done: GitHubImportDone? = null,
) {
    /** True while an import is running; the inputs are locked then. */
    val busy: Boolean get() = phase != ImportPhase.Idle
}

private const val MAX_REFERENCE_CHARS = 200
private const val MSG_GENERIC = "Couldn't import this repository. Please try again."
private const val MSG_NO_REPOS = "Couldn't load your repositories."
private const val MSG_TOKEN_UNREADABLE = "Couldn't read your saved GitHub connection. Connect GitHub again from a project's GitHub screen."

/**
 * State and actions of Import from GitHub: the list of the person's repositories (when GitHub is connected) and the
 * import itself. The code is downloaded to a temp file in [cacheDir], unpacked by the same stream importer as a ZIP
 * import (as a new project, all or nothing) and, when the person can push to the repository, linked to it. Leaving
 * the screen cancels the work and removes the temp file and any half-made project.
 */
class ImportFromGitHubViewModel(
    private val projects: CoderProjectsRepository,
    private val files: CoderFileStore,
    private val github: CoderGitHubSettings,
    private val cacheDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportFromGitHubUiState())
    val state: StateFlow<ImportFromGitHubUiState> = _state.asStateFlow()

    private var importJob: Job? = null
    private var reposJob: Job? = null

    init {
        viewModelScope.launch {
            github.connected.collect { connected ->
                _state.update { it.copy(connected = connected) }
                if (connected) loadRepos() else clearRepos()
            }
        }
    }

    /** The text of the repository field: `owner/name` or a GitHub web address. */
    fun setReference(text: String) {
        _state.update { it.copy(reference = text.take(MAX_REFERENCE_CHARS)) }
    }

    /** The text of the search box above the list of repositories. */
    fun setFilter(text: String) {
        _state.update { it.copy(filter = text.take(MAX_REFERENCE_CHARS)) }
    }

    /** Puts a repository of the list into the repository field. Nothing is downloaded until Import is tapped. */
    fun choose(repo: GitHubRepoSummary) {
        _state.update { it.copy(reference = repo.fullName) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Hides the finished import (after the person opened the project or closed the report). */
    fun consumeDone() {
        _state.update { it.copy(done = null) }
    }

    /** Loads the list of repositories again. Does nothing when GitHub is not connected. */
    fun loadRepos() {
        reposJob?.cancel()
        reposJob = viewModelScope.launch {
            _state.update { it.copy(reposLoading = true, reposMessage = null) }
            val token = github.token()
            if (token == null) {
                _state.update { it.copy(reposLoading = false, repos = emptyList(), reposMessage = "$MSG_NO_REPOS $MSG_TOKEN_UNREADABLE") }
                return@launch
            }
            when (val result = CoderGitHubRepos.listMyRepos(token)) {
                is CoderGitHubResult.Ok -> _state.update { it.copy(reposLoading = false, repos = result.value) }
                is CoderGitHubResult.Err -> _state.update {
                    it.copy(reposLoading = false, repos = emptyList(), reposMessage = "$MSG_NO_REPOS ${result.message} You can still type a name above.")
                }
            }
        }
    }

    private fun clearRepos() {
        reposJob?.cancel()
        _state.update { it.copy(reposLoading = false, repos = emptyList(), reposMessage = null, filter = "") }
    }

    /** Starts the import of the repository in the field. Ignored while another import runs. */
    fun startImport() {
        if (importJob?.isActive == true) return
        val parsed = parseRepoRef(_state.value.reference)
        if (parsed == null) {
            _state.update { it.copy(error = CoderGitHubRepos.MSG_BAD_REFERENCE) }
            return
        }
        importJob = viewModelScope.launch { runImport(parsed.first, parsed.second) }
    }

    /** Stops the import. The download and any half-made project are removed. */
    fun cancelImport() {
        importJob?.cancel()
    }

    private suspend fun runImport(owner: String, name: String) {
        val temp = File(cacheDir, "coder-github-" + UUID.randomUUID().toString() + ".zip")
        try {
            _state.update { it.copy(phase = ImportPhase.Checking, downloadedBytes = 0L, totalBytes = -1L, error = null) }
            val token = if (_state.value.connected) github.token() else null
            // When the facts cannot be read, the download still tries the default branch and says what went wrong.
            val details = (CoderGitHubRepos.getRepoDetails(token, owner, name) as? CoderGitHubResult.Ok)?.value
            val ref = details?.defaultBranch?.takeIf { it.isNotEmpty() } ?: CoderGitHubRepos.DEFAULT_REF
            val repoName = details?.name ?: name
            _state.update { it.copy(phase = ImportPhase.Downloading) }
            val download = CoderGitHubRepos.downloadZipball(token, owner, name, ref, temp) { done, total ->
                _state.update { it.copy(downloadedBytes = done, totalBytes = total) }
            }
            when (download) {
                is CoderZipballResult.Failed -> fail(download.message)
                CoderZipballResult.TooBig -> fail(CoderGitHubRepos.MSG_TOO_BIG)
                is CoderZipballResult.Downloaded -> {
                    _state.update { it.copy(phase = ImportPhase.Unpacking) }
                    unpack(temp, repoName, details)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { temp.delete() }
                _state.update { it.copy(phase = ImportPhase.Idle) }
            }
        }
    }

    /** Unpacks the downloaded ZIP as a new project named after the repository, then links it when the person can push to it. */
    private suspend fun unpack(zip: File, repoName: String, details: GitHubRepoDetails?) {
        val result = withContext(Dispatchers.IO) {
            importStream(
                store = files,
                open = { FileInputStream(zip) },
                suggestedName = CoderGitHubRepos.projectNameFor(repoName),
            ) { id, baseName ->
                val taken = projects.observeAll().first().map { it.name }
                val name = CoderZipRules.uniqueProjectName(baseName, taken)
                projects.createProject(name, CoderGitHubRepos.TEMPLATE_ID, emptyList(), id) != null
            }
        }
        when (result) {
            is CoderImportResult.Failure -> fail(result.message)
            is CoderImportResult.Success -> {
                if (details != null && details.canPush && details.defaultBranch.isNotEmpty()) {
                    projects.setRepoLink(result.projectId, details.owner, details.name, details.defaultBranch)
                }
                _state.update { it.copy(done = GitHubImportDone(result.projectId, result.report)) }
            }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(error = message.ifEmpty { MSG_GENERIC }) }
    }
}
