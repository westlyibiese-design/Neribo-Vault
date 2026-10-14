package com.westly.neribovault.feature.coder.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.data.repository.ProjectsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a push is. Only one push can run at a time. */
sealed interface CoderPushUiState {
    /** Nothing is running. */
    object Idle : CoderPushUiState

    /** Reading GitHub and the project's files; nothing is uploaded yet. */
    object Checking : CoderPushUiState

    /** The summary is shown and the person has to confirm. */
    data class Confirm(val prepared: PushPrepared) : CoderPushUiState

    /** Files are being uploaded; [done] of [total] have started. */
    data class Uploading(val done: Int, val total: Int) : CoderPushUiState

    /** The push worked. */
    data class Done(val result: PushResult, val owner: String, val name: String) : CoderPushUiState

    /** The push stopped; [error] holds a calm message. */
    data class Failed(val error: CoderGitHubResult.Err) : CoderPushUiState
}

/** The question asked once after a push: save [url] as the repository address of the Developer project [developerProjectId]. */
data class RepoAddressPrompt(val developerProjectId: String, val url: String)

/** Everything the GitHub screen shows. It never holds the token. */
data class CoderGitHubUiState(
    val project: CoderProjectEntity? = null,
    val isLoading: Boolean = true,
    val connected: Boolean = false,
    val login: String? = null,
    val tokenLast4: String? = null,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val scopeWarning: Boolean = false,
    val repoBusy: Boolean = false,
    val repoError: String? = null,
    /** Whether the linked repository is private, or null until GitHub has said. */
    val repoIsPrivate: Boolean? = null,
    val push: CoderPushUiState = CoderPushUiState.Idle,
    /** Set after a successful push when the linked Developer project has no repository address yet. */
    val addressPrompt: RepoAddressPrompt? = null,
    val message: String? = null,
)

private const val MSG_BAD_TOKEN_TEXT = "That doesn't look like a GitHub token. Copy it again and paste it here."
private const val MSG_TOKEN_NOT_SAVED = "Couldn't save the token on this phone. Try again."
private const val MSG_BAD_REPO_REF = "Enter the repository as owner/name, or paste its web address."
private const val MSG_BAD_REPO_NAME = "Use only letters, digits, \"-\", \"_\" and \".\" in the name."
private const val MSG_CREATE_REJECTED = "GitHub couldn't create that repository. A repository with this name may already exist on your account. Try another name."
private const val MSG_NEED_CONNECT = "Connect your GitHub account first."
private const val MSG_UNEXPECTED = "Something went wrong. Try again."
private const val FALLBACK_REPO_NAME = "coder-project"
private const val MAX_REPO_NAME = 100

/**
 * A repository name made from a project name: letters, digits, "-", "_" and "." are kept, spaces become
 * "-", everything else is dropped, repeated "-" are joined, and the result is at most 100 characters.
 */
fun suggestRepoName(projectName: String): String {
    val out = StringBuilder()
    for (ch in projectName.trim()) {
        val safe = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '.'
        when {
            safe -> out.append(ch)
            ch == '-' || ch.isWhitespace() -> if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
    }
    val name = out.toString().trim('-').take(MAX_REPO_NAME).trim('-')
    return if (isValidRepoName(name)) name else FALLBACK_REPO_NAME
}

/**
 * State and actions of the GitHub screen of one project: connect or disconnect the account, link or create
 * the repository and run the push. The token is only passed through to [CoderGitHubSettings]; it is never
 * kept here. A running push is cancelled when the screen is left.
 */
class CoderGitHubViewModel(
    private val projectId: String,
    private val projects: CoderProjectsRepository,
    private val settings: CoderGitHubSettings,
    private val client: CoderGitHubClient,
    private val pusher: CoderGitHubPusher,
    private val developerProjects: ProjectsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CoderGitHubUiState())

    /** What the screen shows. */
    val state: StateFlow<CoderGitHubUiState> = _state.asStateFlow()

    private class Inputs(val project: CoderProjectEntity?, val connected: Boolean, val login: String?, val last4: String?)

    private var pushJob: Job? = null
    private var infoJob: Job? = null
    private var infoKey: String? = null

    /** True from the moment a check or an upload starts until it ends. It is set and read on the main thread only. */
    private var pushBusy = false

    /** Counts started pushes, so the end of a cancelled push cannot free the busy flag of a newer one. */
    private var pushGeneration = 0
    private var lastMessage: String = DEFAULT_COMMIT_MESSAGE

    init {
        viewModelScope.launch {
            combine(
                projects.observeById(projectId),
                settings.connected,
                settings.login,
                settings.tokenLast4,
            ) { project, connected, login, last4 -> Inputs(project, connected, login, last4) }
                .collect { inputs ->
                    _state.update {
                        it.copy(
                            project = inputs.project,
                            connected = inputs.connected,
                            login = inputs.login,
                            tokenLast4 = inputs.last4,
                            isLoading = false,
                        )
                    }
                    refreshRepoInfo(inputs)
                }
        }
    }

    /** Asks GitHub whether the linked repository is private whenever the account or the link changes. */
    private fun refreshRepoInfo(inputs: Inputs) {
        val owner = inputs.project?.repoOwner
        val name = inputs.project?.repoName
        val key = if (inputs.connected && owner != null && name != null) "$owner/$name" else null
        if (key == infoKey) return
        infoKey = key
        infoJob?.cancel()
        _state.update { it.copy(repoIsPrivate = null) }
        if (key == null || owner == null || name == null) return
        infoJob = viewModelScope.launch {
            val token = settings.token() ?: return@launch
            val result = client.getRepo(token, owner, name)
            if (result is CoderGitHubResult.Ok) {
                val isPrivate = result.value.isPrivate
                _state.update { it.copy(repoIsPrivate = isPrivate) }
            }
        }
    }

    /**
     * Checks the pasted token with GitHub and saves it only when GitHub accepts it. [rawToken] is not
     * stored anywhere else, and no message contains it.
     */
    fun connect(rawToken: String) {
        if (_state.value.connecting) return
        val token = cleanGitHubToken(rawToken)
        if (token == null) {
            _state.update { it.copy(connectError = MSG_BAD_TOKEN_TEXT) }
            return
        }
        _state.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            try {
                when (val result = client.getUser(token)) {
                    is CoderGitHubResult.Err -> _state.update { it.copy(connectError = result.message) }
                    is CoderGitHubResult.Ok -> {
                        val user = result.value
                        if (settings.saveToken(token, user.login)) {
                            val scopes = user.scopes
                            _state.update {
                                it.copy(
                                    scopeWarning = scopes != null && !scopes.contains("repo"),
                                    message = "Connected as ${user.login}",
                                )
                            }
                        } else {
                            _state.update { it.copy(connectError = MSG_TOKEN_NOT_SAVED) }
                        }
                    }
                }
            } finally {
                _state.update { it.copy(connecting = false) }
            }
        }
    }

    /** Removes the saved token. The project keeps its repository link. */
    fun disconnect() {
        cancelPushInternal()
        viewModelScope.launch {
            settings.disconnect()
            _state.update { it.copy(scopeWarning = false, connectError = null, message = "Disconnected") }
        }
    }

    /** Links the project to an existing repository written as owner/name or as a GitHub web address. */
    fun linkExisting(rawRef: String) {
        if (_state.value.repoBusy) return
        val ref = parseRepoRef(rawRef)
        if (ref == null) {
            _state.update { it.copy(repoError = MSG_BAD_REPO_REF) }
            return
        }
        runRepoAction { token ->
            when (val result = client.getRepo(token, ref.first, ref.second)) {
                is CoderGitHubResult.Err -> _state.update { it.copy(repoError = result.message) }
                is CoderGitHubResult.Ok -> saveLink(result.value)
            }
        }
    }

    /** Creates a new empty repository under the connected account and links the project to it. */
    fun createAndLink(rawName: String, isPrivate: Boolean) {
        if (_state.value.repoBusy) return
        val name = rawName.trim()
        if (!isValidRepoName(name)) {
            _state.update { it.copy(repoError = MSG_BAD_REPO_NAME) }
            return
        }
        runRepoAction { token ->
            when (val result = client.createRepo(token, name, isPrivate)) {
                is CoderGitHubResult.Err -> {
                    val text = if (result.kind == CoderGitHubErrorKind.Invalid) MSG_CREATE_REJECTED else result.message
                    _state.update { it.copy(repoError = text) }
                }
                is CoderGitHubResult.Ok -> saveLink(result.value)
            }
        }
    }

    /** Runs [action] with the saved token while [CoderGitHubUiState.repoBusy] is set. */
    private fun runRepoAction(action: suspend (String) -> Unit) {
        _state.update { it.copy(repoBusy = true, repoError = null) }
        viewModelScope.launch {
            try {
                val token = settings.token()
                if (token == null) {
                    _state.update { it.copy(repoError = MSG_NEED_CONNECT) }
                } else {
                    action(token)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(repoError = MSG_UNEXPECTED) }
            } finally {
                _state.update { it.copy(repoBusy = false) }
            }
        }
    }

    private suspend fun saveLink(repo: GitHubRepo) {
        projects.setRepoLink(projectId, repo.owner, repo.name, repo.defaultBranch)
        _state.update { it.copy(push = CoderPushUiState.Idle, message = "Linked to ${repo.owner}/${repo.name}") }
    }

    /** Removes the repository link. Nothing is deleted on GitHub or on the phone. */
    fun unlink() {
        cancelPushInternal()
        viewModelScope.launch {
            projects.clearRepoLink(projectId)
            _state.update { it.copy(repoError = null, message = "Unlinked") }
        }
    }

    /** Clears the repository error once the person edits a field. */
    fun clearRepoError() {
        if (_state.value.repoError != null) _state.update { it.copy(repoError = null) }
    }

    /** Clears the account error once the person edits the token field. */
    fun clearConnectError() {
        if (_state.value.connectError != null) _state.update { it.copy(connectError = null) }
    }

    /** Starts the check that comes before a push. A second tap while a push is busy is ignored. */
    fun startPush(commitMessage: String) {
        if (pushBusy) return
        val project = _state.value.project ?: return
        val owner = project.repoOwner ?: return
        val name = project.repoName ?: return
        val branch = project.repoBranch ?: return
        if (!_state.value.connected) return
        pushBusy = true
        val generation = ++pushGeneration
        lastMessage = commitMessage
        _state.update { it.copy(push = CoderPushUiState.Checking) }
        pushJob = viewModelScope.launch {
            try {
                val result = try {
                    pusher.prepare(projectId, owner, name, branch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CoderGitHubResult.Err(CoderGitHubErrorKind.Other, MSG_UNEXPECTED)
                }
                val next = when (result) {
                    is CoderGitHubResult.Ok -> CoderPushUiState.Confirm(result.value)
                    is CoderGitHubResult.Err -> CoderPushUiState.Failed(result)
                }
                _state.update { it.copy(push = next) }
            } finally {
                if (generation == pushGeneration) pushBusy = false
            }
        }
    }

    /** The person confirmed the summary: upload and commit. */
    fun confirmPush() {
        if (pushBusy) return
        val current = _state.value.push as? CoderPushUiState.Confirm ?: return
        val project = _state.value.project ?: return
        val owner = project.repoOwner ?: return
        val name = project.repoName ?: return
        val branch = project.repoBranch ?: return
        val preview = current.prepared.preview
        pushBusy = true
        val generation = ++pushGeneration
        _state.update { it.copy(push = CoderPushUiState.Uploading(0, 0)) }
        pushJob = viewModelScope.launch {
            try {
                val result = try {
                    pusher.execute(projectId, owner, name, branch, preview, lastMessage) { done, total ->
                        _state.update { it.copy(push = CoderPushUiState.Uploading(done, total)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CoderGitHubResult.Err(CoderGitHubErrorKind.Other, MSG_UNEXPECTED)
                }
                val next = when (result) {
                    is CoderGitHubResult.Ok -> CoderPushUiState.Done(result.value, owner, name)
                    is CoderGitHubResult.Err -> CoderPushUiState.Failed(result)
                }
                _state.update { it.copy(push = next) }
                if (result is CoderGitHubResult.Ok) {
                    val prompt = findAddressPrompt(owner, name)
                    _state.update { it.copy(addressPrompt = prompt) }
                }
            } finally {
                if (generation == pushGeneration) pushBusy = false
            }
        }
    }

    /**
     * The question to ask after a push, or null when it should not be asked: the project is not linked to a
     * Developer project, that project is missing or in the trash, or it already has a repository address.
     */
    private suspend fun findAddressPrompt(owner: String, name: String): RepoAddressPrompt? {
        val developerId = _state.value.project?.developerProjectId ?: return null
        return try {
            val developer = developerProjects.getById(developerId)
            if (developer == null || developer.isDeleted || developer.repoUrl.isNotBlank()) {
                null
            } else {
                RepoAddressPrompt(developerId, "https://github.com/$owner/$name")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The answer to the question after a push. Yes saves the address in the Developer project (read again
     * first, so nothing newer is overwritten); No changes nothing. Either way the question is not asked again.
     */
    fun answerAddressPrompt(save: Boolean) {
        val prompt = _state.value.addressPrompt ?: return
        _state.update { it.copy(addressPrompt = null) }
        if (!save) return
        viewModelScope.launch {
            try {
                val developer = developerProjects.getById(prompt.developerProjectId)
                if (developer != null && !developer.isDeleted && developer.repoUrl.isBlank()) {
                    developerProjects.upsert(developer.copy(repoUrl = prompt.url))
                    _state.update { it.copy(message = "Saved the repository address") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Couldn't save the address. Try again later.") }
            }
        }
    }

    /** Closes the summary without pushing. */
    fun dismissSummary() {
        if (_state.value.push is CoderPushUiState.Confirm) _state.update { it.copy(push = CoderPushUiState.Idle) }
    }

    /** Stops a running check or upload. GitHub only changes at the last step, so a stopped push changes nothing. */
    fun cancelPush() {
        if (!pushBusy) return
        cancelPushInternal()
        _state.update { it.copy(message = "Push cancelled") }
    }

    private fun cancelPushInternal() {
        pushJob?.cancel()
        pushJob = null
        pushBusy = false
        pushGeneration++
        _state.update { it.copy(push = CoderPushUiState.Idle) }
    }

    /** Runs the check again after an error, with the same commit message. */
    fun retryPush() {
        startPush(lastMessage)
    }

    /** Hides the result or error card. */
    fun clearPushResult() {
        val push = _state.value.push
        if (push is CoderPushUiState.Done || push is CoderPushUiState.Failed) {
            _state.update { it.copy(push = CoderPushUiState.Idle) }
        }
    }

    /** Marks the snackbar message as shown. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        pushJob?.cancel()
        infoJob?.cancel()
        super.onCleared()
    }
}
