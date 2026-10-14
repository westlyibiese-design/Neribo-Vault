package com.westly.neribovault.feature.coder.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.preview.CoderConsoleMessage
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MESSAGE_KEY_UNREADABLE = "Your key couldn't be read. Open AI settings and save it again."
private const val MESSAGE_UNEXPECTED = "Something went wrong. Try again."
private const val MAX_NOTICE_FILES = 3

/** Whether AI help can be used: not known yet, not set up, or ready with a provider and a model. */
sealed interface CoderAiSetup {
    object Loading : CoderAiSetup
    object NotSetUp : CoderAiSetup
    data class Ready(val provider: CoderAiProvider, val model: String) : CoderAiSetup
}

/** Everything the chat screen shows. Nothing here is written to disk. */
data class CoderAiUiState(
    val messages: List<CoderChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val input: String = "",
    val includeOpenFile: Boolean = true,
    val extraFiles: List<String> = emptyList(),
    val shortenedNotice: Boolean = false,
    /** Why the newest question was not sent, or null. */
    val failureKind: CoderChatFailure? = null,
    /** True while an error text from the preview waits to be sent with the next question. */
    val errorAttached: Boolean = false,
)

/** What one question is made of, kept so a failed question can be sent again unchanged. */
private class PreparedTurn(val turn: BuiltTurn, val note: String?)

/**
 * The chat of one project. Messages live only here: they survive a rotation and leaving the screen,
 * and are gone when the person starts a new chat or leaves the project. Only one request runs at a
 * time. Nothing the AI answers is applied here; the screen applies code only when the person taps.
 */
class CoderAiViewModel(
    private val projectId: String,
    private val settings: CoderAiSettings,
    private val files: CoderFileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(CoderAiUiState())
    val state: StateFlow<CoderAiUiState> = _state.asStateFlow()

    /** Follows the AI settings, so the screen changes as soon as a key is saved or removed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val setup: StateFlow<CoderAiSetup> = settings.activeProvider
        .flatMapLatest { provider -> setupFlow(provider) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoderAiSetup.Loading)

    private var nextId = 1L
    private var job: Job? = null
    private var activeRequest = 0L
    private var pendingConsole: String? = null
    private var lastPrepared: PreparedTurn? = null

    private fun setupFlow(provider: CoderAiProvider?): Flow<CoderAiSetup> {
        if (provider == null) return flowOf(CoderAiSetup.NotSetUp)
        return combine(settings.hasKey(provider), settings.model(provider)) { hasKey, model ->
            setupOf(provider, hasKey, model)
        }
    }

    private fun setupOf(provider: CoderAiProvider, hasKey: Boolean, model: String?): CoderAiSetup =
        if (hasKey && !model.isNullOrBlank()) CoderAiSetup.Ready(provider, model) else CoderAiSetup.NotSetUp

    // ---- Small inputs ----------------------------------------------------------------------

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun setIncludeOpenFile(include: Boolean) {
        _state.update { it.copy(includeOpenFile = include) }
    }

    /** Replaces the extra files; at most 5 are kept. */
    fun setExtraFiles(paths: List<String>) {
        _state.update { it.copy(extraFiles = paths.distinct().take(CODER_AI_MAX_EXTRA_FILES)) }
    }

    /** Drops the error text that was waiting for the next question. */
    fun removeAttachedError() {
        pendingConsole = null
        _state.update { it.copy(errorAttached = false) }
    }

    // ---- Sending ---------------------------------------------------------------------------

    /** Sends the typed [request] with the context the person chose. Does nothing when blank or while another request runs. */
    fun send(request: String, context: CoderAiWorkspaceContext) {
        submit(request, context, forceOpenFile = false, clearInput = true)
    }

    /**
     * Sends a quick prompt about the selected text, or about the open file when nothing is selected.
     * Does nothing when no file is open. The typed text in the input stays where it is.
     */
    fun quick(action: CoderQuickAction, context: CoderAiWorkspaceContext) {
        if (context.openPath == null) return
        submit(action.prompt, context, forceOpenFile = true, clearInput = false)
    }

    /**
     * Fills the input for a preview error and keeps its text (and its source file, when that is a text
     * file of the project) for the next question. It does not send anything.
     */
    fun prepareFromError(message: CoderConsoleMessage) {
        pendingConsole = message.text.take(CODER_AI_MAX_CONSOLE_CHARS)
        _state.update { it.copy(input = PROMPT_FIX_FROM_ERROR, errorAttached = true) }
        val source = message.sourceFile ?: return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { findTextFile(source) } ?: return@launch
            _state.update { s ->
                s.copy(extraFiles = (listOf(found) + s.extraFiles.filter { it != found }).take(CODER_AI_MAX_EXTRA_FILES))
            }
        }
    }

    /** Sends the last question again when it was not sent. */
    fun retry() {
        val current = _state.value
        if (current.isSending) return
        val last = current.messages.lastOrNull() ?: return
        if (last.role != ChatRole.User || !last.failed) return
        val prepared = lastPrepared ?: return
        _state.update { s ->
            s.copy(
                messages = s.messages.map { if (it.id == last.id) it.copy(failed = false, notice = prepared.note) else it },
                isSending = true,
                failureKind = null,
            )
        }
        start(last.id) { prepared }
    }

    /** Stops the running request. The question stays and no error is shown. */
    fun stop() {
        activeRequest++
        job?.cancel()
        job = null
        _state.update { it.copy(isSending = false) }
    }

    /** Clears the whole chat, including the extra files and any waiting error text. */
    fun newChat() {
        activeRequest++
        job?.cancel()
        job = null
        pendingConsole = null
        lastPrepared = null
        _state.update {
            it.copy(
                messages = emptyList(),
                isSending = false,
                input = "",
                includeOpenFile = true,
                extraFiles = emptyList(),
                shortenedNotice = false,
                failureKind = null,
                errorAttached = false,
            )
        }
    }

    private fun submit(request: String, context: CoderAiWorkspaceContext, forceOpenFile: Boolean, clearInput: Boolean) {
        val typed = request.trim()
        val current = _state.value
        if (typed.isEmpty() || current.isSending) return
        val hasSelection = !context.selection.isNullOrBlank()
        val includeOpen = current.includeOpenFile || (forceOpenFile && !hasSelection)
        val extras = current.extraFiles
        val console = pendingConsole
        pendingConsole = null
        val message = CoderChatMessage(id = nextId++, role = ChatRole.User, text = typed)
        _state.update {
            it.copy(
                messages = it.messages + message,
                isSending = true,
                input = if (clearInput) "" else it.input,
                failureKind = null,
                errorAttached = false,
            )
        }
        start(message.id) {
            withContext(Dispatchers.IO) { prepareTurn(typed, context, includeOpen, extras, console) }
        }
    }

    /** Runs one request: prepare, ask, then show the answer or mark the question as not sent. */
    private fun start(messageId: Long, prepare: suspend () -> PreparedTurn) {
        val mine = ++activeRequest
        job = viewModelScope.launch {
            try {
                val prepared = prepare()
                lastPrepared = prepared
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map { if (it.id == messageId) it.copy(notice = prepared.note) else it },
                        shortenedNotice = prepared.turn.shortened,
                    )
                }
                val config = settings.activeConfig()
                val result: CoderChatResult = if (config == null) {
                    CoderChatResult.Failure(MESSAGE_KEY_UNREADABLE, CoderChatFailure.InvalidKey)
                } else {
                    CoderAiClient.chat(config, CODER_SYSTEM_PROMPT, buildHistory(_state.value.messages, prepared.turn.text))
                }
                if (activeRequest != mine) return@launch
                applyResult(messageId, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeRequest == mine) applyResult(messageId, CoderChatResult.Failure(MESSAGE_UNEXPECTED, CoderChatFailure.Other))
            } finally {
                if (activeRequest == mine) {
                    job = null
                    _state.update { it.copy(isSending = false) }
                }
            }
        }
    }

    private fun applyResult(messageId: Long, result: CoderChatResult) {
        when (result) {
            is CoderChatResult.Success -> {
                val reply = CoderChatMessage(
                    id = nextId++,
                    role = ChatRole.Assistant,
                    text = result.text,
                    truncated = result.truncated,
                )
                _state.update { it.copy(messages = it.messages + reply, failureKind = null) }
            }
            is CoderChatResult.Failure -> _state.update { s ->
                s.copy(
                    messages = s.messages.map {
                        if (it.id == messageId) it.copy(failed = true, notice = result.message) else it
                    },
                    failureKind = result.kind,
                )
            }
        }
    }

    // ---- What is sent ----------------------------------------------------------------------

    /** Reads the extra files and builds the text of one question. Runs on the IO dispatcher. */
    private fun prepareTurn(
        request: String,
        context: CoderAiWorkspaceContext,
        includeOpen: Boolean,
        extras: List<String>,
        console: String?,
    ): PreparedTurn {
        val contextFiles = ArrayList<ContextFile>()
        val seen = HashSet<String>()
        val openPath = context.openPath
        val openText = context.openText
        if (includeOpen && openPath != null && openText != null && CoderPaths.isValidPath(openPath)) {
            contextFiles.add(ContextFile(openPath, openText))
            seen.add(openPath)
        }
        for (path in extras) {
            if (!CoderPaths.isValidPath(path) || !seen.add(path)) continue
            if (path == openPath && openText != null) {
                contextFiles.add(ContextFile(path, openText))
                continue
            }
            val read = files.read(projectId, path)
            if (read is CoderReadResult.Text) contextFiles.add(ContextFile(path, read.text))
        }
        val selection = context.selection?.takeIf { it.isNotBlank() }
        val turn = buildUserTurn(context.projectName, context.filePaths, contextFiles, selection, console, request)
        return PreparedTurn(turn, describeContext(contextFiles, selection != null, !console.isNullOrBlank()))
    }

    private fun describeContext(contextFiles: List<ContextFile>, hasSelection: Boolean, hasError: Boolean): String? {
        val parts = ArrayList<String>()
        val shown = contextFiles.take(MAX_NOTICE_FILES).map { it.path }
        parts.addAll(shown)
        if (contextFiles.size > shown.size) parts.add("${contextFiles.size - shown.size} more")
        if (hasSelection) parts.add("selected text")
        if (hasError) parts.add("error text")
        return if (parts.isEmpty()) null else "Used: " + parts.joinToString(", ")
    }

    /**
     * The project path of a text file named by a preview error. The preview reports paths relative to
     * the site's top folder, so the path is also tried inside that folder. Runs on the IO dispatcher.
     */
    private fun findTextFile(source: String): String? {
        val candidates = ArrayList<String>()
        candidates.add(source)
        val root = CoderPreviewRoot.find(files, projectId)
        if (!root.isNullOrEmpty()) candidates.add(CoderPreviewRoot.join(root, source))
        for (path in candidates) {
            if (!CoderPaths.isValidPath(path)) continue
            if (files.read(projectId, path) is CoderReadResult.Text) return path
        }
        return null
    }
}
