package com.westly.neribovault.feature.coder.ai

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.data.repository.PlanningDocsRepository
import com.westly.neribovault.data.repository.ProjectDocumentsRepository
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.feature.coder.CoderPrefs
import com.westly.neribovault.feature.coder.GuidanceMode
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.preview.CoderConsoleMessage
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MESSAGE_KEY_UNREADABLE = "Your key couldn't be read. Open AI settings and save it again."
private const val MESSAGE_UNEXPECTED = "Something went wrong. Try again."
private const val MESSAGE_TOO_LONG = "The answer got too long and was cut short."
private const val MESSAGE_STOPPED = "Stopped. What arrived is shown above."
private const val MESSAGE_STOPPED_EMPTY = "Stopped."
private const val MAX_NOTICE_FILES = 3
private const val MAX_PROJECT_NOTES = 5
private const val FILE_TAG = "<file path=\""

/** The most characters of one answer that are kept. After that the stream is stopped. */
private const val MAX_REPLY_CHARS = 200_000

/** The screen is updated at most this often while an answer arrives. */
private const val PUBLISH_INTERVAL_MS = 50L

/** The three starting ideas shown above the input of an empty chat in Beginner mode. Tapping one fills the input; it never sends. */
val CODER_AI_IDEAS: List<String> = listOf("Make a to-do app", "Add a dark mode", "Make it work on phones")

/** Whether AI help can be used: not known yet, not set up, or ready with a provider and a model. */
sealed interface CoderAiSetup {
    object Loading : CoderAiSetup
    object NotSetUp : CoderAiSetup
    data class Ready(val provider: CoderAiProvider, val model: String) : CoderAiSetup
}

/**
 * Everything the chat screen shows. Nothing here is written to disk. [isSending] is the "AI is busy" signal: true
 * from the moment a question is sent until its answer has ended (finished, stopped or failed); the tip engine and
 * the Stop button follow it.
 */
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
private class PreparedTurn(val turn: BuiltTurn, val note: String?, val fileCount: Int)

/** How a streamed answer ended, before it is shown. */
private sealed interface StreamEnd {
    /** The provider finished the answer; [truncated] is true when it ran out of room. */
    data class Done(val truncated: Boolean) : StreamEnd

    /** The answer passed the 200,000-character limit and the stream was stopped. */
    object TooLong : StreamEnd

    /** The request or the stream failed. */
    data class Broke(val kind: CoderChatFailure, val message: String) : StreamEnd
}

/** Thrown inside the collector to leave the stream when the answer is too long. Never leaves this file. */
private class TooLongSignal : RuntimeException()

/**
 * The answer that is being written right now. It lives on the main thread only: the stream is collected there, and
 * the screen gets a copy of it ([toMessage]) at most every 50 milliseconds.
 */
private class LiveReply(val id: Long, val startedAt: Long) {
    val parser = CoderStreamParser()
    val gate = CoderPublishGate(PUBLISH_INTERVAL_MS)
    var stage = CoderAiStage.Reading
    var fileCount = 0
    var providerName = ""
    var model = ""
    var notice: String? = null
    var truncated = false
    var stopped = false
    var interrupted = false
    var endedAt: Long? = null
    var chars = 0
    var dirty = true
    var lastSecond = 0L

    fun move(event: CoderStageEvent) {
        stage = CoderAiStages.next(stage, event)
    }

    fun toMessage(now: Long): CoderChatMessage = CoderChatMessage(
        id = id,
        role = ChatRole.Assistant,
        text = parser.rawText,
        truncated = truncated,
        notice = notice,
        segments = parser.liveSegments(),
        openBlock = parser.liveOpenBlock(),
        stage = CoderAiProgress(stage, fileCount, providerName, model),
        stopped = stopped,
        interrupted = interrupted,
        elapsedMillis = (endedAt ?: now) - startedAt,
    )
}

/**
 * The chat of one project. Messages live only here: they survive a rotation and leaving the screen,
 * and are gone when the person starts a new chat or leaves the project. Only one request runs at a
 * time. Answers are streamed: the text, the progress steps and the code blocks appear while the AI writes, Stop
 * keeps what arrived, and an answer that breaks off keeps its text and offers Retry. When the app goes to the
 * background (the vault locks) a running answer is stopped the same way. Nothing the AI answers is applied here;
 * the screen applies code only when the person taps.
 * When the project is linked to a Developer project, the question also carries that project's name, status,
 * description and tech stack, and, only while "Project notes" is on, its planning and project documents.
 */
class CoderAiViewModel(
    private val projectId: String,
    private val settings: CoderAiSettings,
    private val files: CoderFileStore,
    private val projects: CoderProjectsRepository,
    private val developerProjects: ProjectsRepository,
    private val planningDocs: PlanningDocsRepository,
    private val projectDocuments: ProjectDocumentsRepository,
    private val prefs: CoderPrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(CoderAiUiState())
    val state: StateFlow<CoderAiUiState> = _state.asStateFlow()

    /** Follows the AI settings, so the screen changes as soon as a key is saved or removed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val setup: StateFlow<CoderAiSetup> = settings.activeProvider
        .flatMapLatest { provider -> setupFlow(provider) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoderAiSetup.Loading)

    /** True while the project is linked to a Developer project that is still there (not deleted, not in the trash). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val linked: StateFlow<Boolean> = projects.observeById(projectId)
        .map { it?.developerProjectId }
        .distinctUntilChanged()
        .flatMapLatest { developerId -> linkedFlow(developerId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True when the person allowed the AI to use the notes of the linked Developer project. Off by default. */
    val notesOn: StateFlow<Boolean> = prefs.notesOptIn(projectId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Beginner or Pro, or null until it is read. The idea chips show only in Beginner mode. */
    val guidanceMode: StateFlow<GuidanceMode?> = prefs.guidanceMode
        .stateIn<GuidanceMode?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var nextId = 1L
    private var job: Job? = null
    private var activeRequest = 0L
    private var pendingConsole: String? = null
    private var lastPrepared: PreparedTurn? = null
    private var liveReply: LiveReply? = null

    /** The app going to the background re-locks the vault; a running answer is stopped then and keeps what arrived. */
    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            stop()
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onCleared()
    }

    private fun setupFlow(provider: CoderAiProvider?): Flow<CoderAiSetup> {
        if (provider == null) return flowOf(CoderAiSetup.NotSetUp)
        return combine(settings.hasKey(provider), settings.model(provider)) { hasKey, model ->
            setupOf(provider, hasKey, model)
        }
    }

    private fun linkedFlow(developerId: String?): Flow<Boolean> {
        if (developerId == null) return flowOf(false)
        return developerProjects.observeById(developerId).map { it != null && !it.isDeleted }
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

    /** Turns the use of the linked project's notes on or off for this project. */
    fun setNotesOn(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotesOptIn(projectId, enabled) }
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

    /**
     * Sends the last question again when its answer did not come: either the question was not sent, or the answer broke
     * off after some text (that partial answer is replaced by the new one). Does nothing otherwise or while busy.
     */
    fun retry() {
        val current = _state.value
        if (current.isSending) return
        val last = current.messages.lastOrNull() ?: return
        val prepared = lastPrepared ?: return
        if (last.role == ChatRole.User && last.failed) {
            _state.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.id == last.id) it.copy(failed = false, notice = prepared.note) else it },
                    isSending = true,
                    failureKind = null,
                )
            }
            start(last.id) { prepared }
        } else if (last.role == ChatRole.Assistant && last.interrupted) {
            val question = current.messages.getOrNull(current.messages.lastIndex - 1) ?: return
            if (question.role != ChatRole.User) return
            _state.update { s ->
                s.copy(messages = s.messages.filter { it.id != last.id }, isSending = true, failureKind = null)
            }
            start(question.id) { prepared }
        }
    }

    /**
     * Stops the running answer at any moment. Everything that arrived stays: the text and the code blocks (a block
     * that was still being written is marked unfinished and can only be copied). Does nothing when nothing runs.
     */
    fun stop() {
        if (!_state.value.isSending) return
        activeRequest++
        job?.cancel()
        job = null
        val live = liveReply
        liveReply = null
        if (live == null) {
            _state.update { it.copy(isSending = false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        live.parser.finish()
        live.move(CoderStageEvent.StopTapped)
        live.stopped = true
        live.notice = if (live.parser.rawText.isBlank()) MESSAGE_STOPPED_EMPTY else MESSAGE_STOPPED
        live.endedAt = now
        val message = live.toMessage(now)
        _state.update { s ->
            s.copy(messages = s.messages.map { if (it.id == live.id) message else it }, isSending = false)
        }
    }

    /** Clears the whole chat, including the extra files and any waiting error text. */
    fun newChat() {
        activeRequest++
        job?.cancel()
        job = null
        liveReply = null
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

    /**
     * Runs one request: adds the answer that is being written, prepares the question, streams the answer into it
     * and ends it. A question that got no text at all is marked as not sent (as before); an answer that got some text
     * and then broke off stays, marked as interrupted.
     */
    private fun start(questionId: Long, prepare: suspend () -> PreparedTurn) {
        val mine = ++activeRequest
        val live = LiveReply(id = nextId++, startedAt = SystemClock.elapsedRealtime())
        liveReply = live
        _state.update { it.copy(messages = it.messages + live.toMessage(live.startedAt)) }
        job = viewModelScope.launch {
            var ticker: Job? = null
            try {
                val prepared = prepare()
                if (activeRequest != mine) return@launch
                lastPrepared = prepared
                live.fileCount = prepared.fileCount
                live.move(CoderStageEvent.ContextRead)
                live.dirty = true
                _state.update { s ->
                    s.copy(
                        messages = s.messages.map { if (it.id == questionId) it.copy(notice = prepared.note) else it },
                        shortenedNotice = prepared.turn.shortened,
                    )
                }
                publishIfDue(live)
                val config = settings.activeConfig()
                if (activeRequest != mine) return@launch
                if (config == null) {
                    conclude(live, questionId, StreamEnd.Broke(CoderChatFailure.InvalidKey, MESSAGE_KEY_UNREADABLE))
                    return@launch
                }
                live.providerName = config.provider.displayName
                live.model = config.model
                live.dirty = true
                publishIfDue(live)
                ticker = launch { tick(live, mine) }
                val history = historyFor(questionId, prepared.turn.text)
                val end = collectAnswer(config, live, history)
                if (activeRequest != mine) return@launch
                conclude(live, questionId, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeRequest == mine) conclude(live, questionId, StreamEnd.Broke(CoderChatFailure.Other, MESSAGE_UNEXPECTED))
            } finally {
                ticker?.cancel()
                if (activeRequest == mine) {
                    job = null
                    liveReply = null
                    _state.update { it.copy(isSending = false) }
                }
            }
        }
    }

    /** The earlier messages up to and including the question, without answers that have no text. */
    private fun historyFor(questionId: Long, latestTurn: String): List<Pair<ChatRole, String>> {
        val all = _state.value.messages
        val upTo = all.indexOfFirst { it.id == questionId }
        val base = if (upTo < 0) all else all.take(upTo + 1)
        val usable = base.filter { !(it.role == ChatRole.Assistant && it.text.isBlank()) }
        return buildHistory(usable, latestTurn)
    }

    /**
     * Collects the stream into [live] and returns how it ended. Text goes into the parser as it arrives; the screen
     * gets a copy at most every 50 milliseconds. Collecting stops by itself when the answer passes 200,000 characters.
     */
    private suspend fun collectAnswer(
        config: CoderAiConfig,
        live: LiveReply,
        history: List<Pair<ChatRole, String>>,
    ): StreamEnd {
        var end: StreamEnd = StreamEnd.Broke(CoderChatFailure.Other, MESSAGE_UNEXPECTED)
        try {
            CoderAiClient.chatStream(config, CODER_SYSTEM_PROMPT, history).collect { event ->
                when (event) {
                    CoderStreamEvent.Started -> {
                        // The provider accepted the request; the stage stays "Asking" until the first text.
                    }
                    is CoderStreamEvent.Text -> {
                        val room = MAX_REPLY_CHARS - live.chars
                        val tooLong = event.delta.length > room
                        val piece = if (tooLong) cutReply(event.delta, room) else event.delta
                        if (piece.isNotEmpty()) {
                            live.parser.feed(piece)
                            live.chars += piece.length
                            live.move(CoderStageEvent.TextArrived)
                            live.dirty = true
                        }
                        if (tooLong) throw TooLongSignal()
                        publishIfDue(live)
                    }
                    is CoderStreamEvent.Finished -> {
                        end = StreamEnd.Done(event.truncated)
                    }
                    is CoderStreamEvent.Failed -> {
                        end = StreamEnd.Broke(event.kind, event.message)
                    }
                }
            }
        } catch (e: TooLongSignal) {
            end = StreamEnd.TooLong
        }
        return end
    }

    /** Publishes a copy of [live] when something changed and the last copy is at least 50 milliseconds old. */
    private fun publishIfDue(live: LiveReply) {
        val now = SystemClock.elapsedRealtime()
        if (live.gate.shouldPublish(now, live.dirty)) publish(live, now)
    }

    private fun publish(live: LiveReply, now: Long) {
        val message = live.toMessage(now)
        live.dirty = false
        live.gate.markPublished(now)
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == live.id) message else it }) }
    }

    /** Wakes up every 50 milliseconds to publish the last text of a pause and to move the seconds counter on. */
    private suspend fun tick(live: LiveReply, request: Long) {
        while (true) {
            delay(PUBLISH_INTERVAL_MS)
            if (activeRequest != request) return
            val now = SystemClock.elapsedRealtime()
            val second = (now - live.startedAt) / 1000L
            if (second != live.lastSecond) {
                live.lastSecond = second
                live.dirty = true
            }
            if (live.gate.shouldPublish(now, live.dirty)) publish(live, now)
        }
    }

    /** Ends the answer [live]: shows its final form, or, when no text came at all, marks the question as not sent. */
    private fun conclude(live: LiveReply, questionId: Long, end: StreamEnd) {
        val now = SystemClock.elapsedRealtime()
        when (end) {
            is StreamEnd.Done -> {
                live.parser.finish()
                live.truncated = end.truncated
                live.move(if (end.truncated) CoderStageEvent.StreamCutShort else CoderStageEvent.StreamFinished)
                settle(live, now, null)
            }
            StreamEnd.TooLong -> {
                live.parser.finish()
                live.truncated = true
                live.notice = MESSAGE_TOO_LONG
                live.move(CoderStageEvent.StreamCutShort)
                settle(live, now, null)
            }
            is StreamEnd.Broke -> {
                if (live.parser.rawText.isBlank()) {
                    _state.update { s ->
                        s.copy(
                            messages = s.messages
                                .filter { it.id != live.id }
                                .map { if (it.id == questionId) it.copy(failed = true, notice = end.message) else it },
                            isSending = false,
                            failureKind = end.kind,
                        )
                    }
                } else {
                    live.parser.finish()
                    live.move(CoderStageEvent.ErrorHappened)
                    live.interrupted = true
                    live.notice = end.message
                    settle(live, now, end.kind)
                }
            }
        }
        liveReply = null
    }

    /** Publishes the final copy of [live], with the time frozen at [now], and ends the busy state. */
    private fun settle(live: LiveReply, now: Long, kind: CoderChatFailure?) {
        live.endedAt = now
        live.dirty = false
        val message = live.toMessage(now)
        _state.update { s ->
            s.copy(
                messages = s.messages.map { if (it.id == live.id) message else it },
                isSending = false,
                failureKind = kind,
            )
        }
    }

    // ---- What is sent ----------------------------------------------------------------------

    /** Reads the extra files and builds the text of one question. Runs on the IO dispatcher. */
    private suspend fun prepareTurn(
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
        val turn = buildUserTurn(
            context.projectName, context.filePaths, contextFiles, selection, console, request, loadProjectInfo(),
        )
        // Count the files that really went into the question: the builder drops the last ones when it is too long.
        val sentFiles = turn.text.lineSequence().count { it.startsWith(FILE_TAG) }
        return PreparedTurn(turn, describeContext(contextFiles, selection != null, !console.isNullOrBlank()), sentFiles)
    }

    /**
     * The text about the linked Developer project, or null when the project is not linked, the Developer
     * project is gone or in the trash, or it cannot be read. Reads the name, status, description and tech stack
     * only; the notes are added only while the person has them switched on. Runs on the IO dispatcher.
     */
    private suspend fun loadProjectInfo(): String? = try {
        val developerId = projects.getById(projectId)?.developerProjectId
        val developer = if (developerId == null) null else developerProjects.getById(developerId)
        if (developerId == null || developer == null || developer.isDeleted) {
            null
        } else {
            val notes = if (prefs.notesOptIn(projectId).first()) loadNotes(developerId) else null
            buildProjectInfo(developer, notes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The newest planning documents and project documents of the Developer project, 5 at most, as title and text pairs. */
    private suspend fun loadNotes(developerId: String): List<Pair<String, String>> {
        val plans = planningDocs.observeByProject(developerId).first()
        val documents = projectDocuments.observeByProject(developerId).first()
        val all = ArrayList<Triple<Long, String, String>>()
        for (plan in plans) all.add(Triple(plan.updatedAt, plan.title, plan.body))
        for (document in documents) all.add(Triple(document.updatedAt, document.title, document.body))
        return all.sortedByDescending { it.first }.take(MAX_PROJECT_NOTES).map { it.second to it.third }
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

/** [text] cut to at most [max] characters, never between the two halves of a character made of two parts. */
private fun cutReply(text: String, max: Int): String {
    if (max <= 0) return ""
    if (text.length <= max) return text
    var end = max
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}
