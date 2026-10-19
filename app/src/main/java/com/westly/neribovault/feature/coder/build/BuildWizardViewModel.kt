package com.westly.neribovault.feature.coder.build

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.ai.CoderAiSettings
import com.westly.neribovault.feature.coder.env.CoderEnvStore
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.github.CoderGitHubClient
import com.westly.neribovault.feature.coder.github.CoderGitHubPusher
import com.westly.neribovault.feature.coder.github.CoderGitHubResult
import com.westly.neribovault.feature.coder.github.CoderGitHubSettings
import com.westly.neribovault.feature.coder.github.PushPrepared
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a build is: nothing running, working, finished well, or stopped by a failure. */
enum class BuildPhase { Idle, Working, Done, Failed }

/**
 * Why a build stopped. [stepName] is the stage or GitHub step that failed; [explanation] is the one plain sentence
 * for a failed run; [logTail] holds the cleaned last lines of the log; [message] is the plain reason when the failure
 * was not a failed run (for example a refused push).
 */
data class BuildFailure(val stepName: String, val explanation: String?, val logTail: String, val message: String?)

/** Everything the wizard shows. It never holds the token or a variable's value. */
data class BuildWizardUiState(
    val isLoading: Boolean = true,
    val project: CoderProjectEntity? = null,
    val connected: Boolean = false,
    val login: String? = null,
    val needsWorkflowScope: Boolean = false,
    val manualStep: Int = 3,
    val plan: CoderBuildPlan? = null,
    val planChecked: Boolean = false,
    val outputDir: String = "dist",
    val envNames: List<String> = emptyList(),
    val envSelected: Set<String> = emptySet(),
    val phase: BuildPhase = BuildPhase.Idle,
    val stages: List<BuildStage> = emptyList(),
    val failure: BuildFailure? = null,
    val pendingPush: PushPrepared? = null,
    val company: String? = null,
    val message: String? = null,
) {
    /** The step on screen, 1 to 5. A step that is already satisfied is skipped, so this is the first one that is not. */
    val step: Int
        get() = when {
            !connected || needsWorkflowScope -> 1
            project?.repoOwner == null || project?.repoName == null -> 2
            else -> manualStep
        }
}

/** True when GitHub's `X-OAuth-Scopes` text lists `workflow`, or when it is absent (a token that has no scopes list, such as a fine-grained one). */
fun scopesAllowWorkflow(scopes: String?): Boolean = scopes == null || scopes.split(',').any { it.trim() == "workflow" }

/**
 * State and actions of the build wizard of one project. It checks the connection, the repository, the project and
 * the variables, and then runs the build: save the files to GitHub (after the person confirms what is pushed),
 * set the variables, start the workflow, watch the real steps and download the result. Polling continues while the
 * view model lives; a state file lets a new one pick up a run that is still going. The token is only read through
 * [CoderGitHubSettings.token] for a single call and is never kept.
 */
class BuildWizardViewModel(
    private val projectId: String,
    private val projects: CoderProjectsRepository,
    private val settings: CoderGitHubSettings,
    private val aiSettings: CoderAiSettings,
    private val store: CoderFileStore,
    private val envStore: CoderEnvStore,
    private val client: CoderGitHubClient,
    private val pusher: CoderGitHubPusher,
    private val runner: CoderBuildRunner,
    private val actions: CoderGitHubActions,
) : ViewModel() {
    private val _state = MutableStateFlow(BuildWizardUiState())

    /** What the screen shows. */
    val state: StateFlow<BuildWizardUiState> = _state.asStateFlow()

    private class Inputs(val project: CoderProjectEntity?, val connected: Boolean, val login: String?, val last4: String?, val company: String?)

    private var buildJob: Job? = null
    private var scopeJob: Job? = null
    private var scopeKey: String? = null
    private var resumeTried = false
    private var pushAnswer: CompletableDeferred<Boolean>? = null
    private var runId: Long? = null
    private var dispatchedAt = 0L
    private var githubStages: List<BuildStage> = emptyList()
    private val fixed = arrayOf(BuildStageState.Waiting, BuildStageState.Waiting, BuildStageState.Waiting, BuildStageState.Waiting)

    init {
        viewModelScope.launch {
            combine(
                projects.observeById(projectId),
                settings.connected,
                settings.login,
                settings.tokenLast4,
                aiSettings.activeProvider,
            ) { project, connected, login, last4, provider -> Inputs(project, connected, login, last4, provider?.company) }
                .collect { inputs ->
                    _state.update {
                        it.copy(
                            project = inputs.project,
                            connected = inputs.connected,
                            login = inputs.login,
                            company = inputs.company,
                            isLoading = false,
                        )
                    }
                    refreshScopes(if (inputs.connected) inputs.last4 ?: "" else null)
                }
        }
    }

    /**
     * Reads the project and the variables again. The screen calls it each time it opens, because the person may
     * have changed files, variables or the GitHub connection on the screens in between. Also picks up a run that
     * was still going the last time (once).
     */
    fun refresh() {
        viewModelScope.launch {
            loadPlan()
            loadEnv()
            if (!resumeTried) {
                resumeTried = true
                resumeIfRunning()
            }
        }
    }

    /** Goes one step back when that is possible here (steps 4 and 5, while nothing is running). False means the screen should close. */
    fun goBackStep(): Boolean {
        val current = _state.value
        if (current.phase == BuildPhase.Working) return false
        if (current.step in 4..5) {
            _state.update { it.copy(manualStep = current.step - 1, phase = BuildPhase.Idle, failure = null, stages = emptyList()) }
            return true
        }
        return false
    }

    /** Moves from step 3 or 4 to the next one. */
    fun continueStep() {
        val step = _state.value.step
        if (step in 3..4) _state.update { it.copy(manualStep = step + 1) }
    }

    /** The person typed a new output folder name. */
    fun setOutputDir(text: String) {
        _state.update { it.copy(outputDir = text.trim()) }
    }

    /** The person ticked or unticked a variable for the build. */
    fun setEnvSelected(name: String, selected: Boolean) {
        _state.update { it.copy(envSelected = if (selected) it.envSelected + name else it.envSelected - name) }
    }

    /** Marks the snackbar message as shown. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** The person answered the question about what will be pushed. */
    fun answerPush(confirm: Boolean) {
        pushAnswer?.complete(confirm)
    }

    /** Starts a build. Does nothing while one is running, and nothing without a supported plan, a valid output folder and a linked repository. */
    fun startBuild() {
        val current = _state.value
        val plan = current.plan ?: return
        if (current.phase == BuildPhase.Working || !plan.supported) return
        if (!CoderPackageAnalyzer.isValidOutputDir(current.outputDir)) return
        val project = current.project ?: return
        val owner = project.repoOwner ?: return
        val repo = project.repoName ?: return
        val branch = project.repoBranch ?: return
        runId = null
        githubStages = emptyList()
        for (i in fixed.indices) fixed[i] = BuildStageState.Waiting
        _state.update { it.copy(phase = BuildPhase.Working, failure = null) }
        publish()
        buildJob = viewModelScope.launch {
            try {
                runBuild(plan.copy(outputDir = current.outputDir), owner, repo, branch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAt(null, "The build", "Something went wrong. Try again.")
            }
        }
    }

    /** Stops watching and asks GitHub to stop the run. Leaving the screen does not do this; only this button does. */
    fun cancelBuild() {
        if (_state.value.phase != BuildPhase.Working) return
        buildJob?.cancel()
        buildJob = null
        pushAnswer?.complete(false)
        val id = runId
        val project = _state.value.project
        val owner = project?.repoOwner
        val repo = project?.repoName
        _state.update { it.copy(phase = BuildPhase.Idle, pendingPush = null, message = "Build cancelled") }
        if (id != null && owner != null && repo != null) {
            viewModelScope.launch {
                val token = settings.token()
                if (token != null) runner.cancelRun(token, owner, repo, id)
                saveState(owner, repo, project?.repoBranch ?: "", "cancelled")
            }
        }
    }

    /** Back to the Start build button after a failure or a finished build. */
    fun tryAgain() {
        runId = null
        githubStages = emptyList()
        _state.update { it.copy(phase = BuildPhase.Idle, failure = null, stages = emptyList(), manualStep = 5) }
    }

    /**
     * Keeps the message for AI help (the failed step, the plain sentence and the cleaned log) so the project screen
     * can put it in the AI input without sending it. False when there is nothing to prepare.
     */
    fun prepareAskAi(): Boolean {
        val failure = _state.value.failure ?: return false
        val text = buildString {
            append("My GitHub build failed at the step \"").append(failure.stepName).append("\".\n")
            if (failure.message != null) append(failure.message).append('\n')
            if (failure.explanation != null) append(failure.explanation).append('\n')
            if (failure.logTail.isNotEmpty()) append("\nLast lines of the build log:\n").append(failure.logTail)
        }.take(MAX_AI_MESSAGE)
        CoderBuildAiHandoff.put(projectId, text)
        return true
    }

    // ---- Checks before the build ------------------------------------------------------------

    private fun refreshScopes(key: String?) {
        if (key == scopeKey) return
        scopeKey = key
        scopeJob?.cancel()
        if (key == null) {
            _state.update { it.copy(needsWorkflowScope = false) }
            return
        }
        scopeJob = viewModelScope.launch {
            val token = settings.token() ?: return@launch
            val result = client.getUser(token)
            if (result is CoderGitHubResult.Ok) {
                val allowed = scopesAllowWorkflow(result.value.scopes)
                _state.update { it.copy(needsWorkflowScope = !allowed) }
            }
        }
    }

    private suspend fun loadPlan() {
        val plan = withContext(Dispatchers.IO) {
            val packageText = (store.read(projectId, "package.json") as? CoderReadResult.Text)?.text
            val rootNames = store.listAll(projectId).filter { !it.isDirectory && !it.path.contains('/') }.map { it.path }.toSet()
            val configName = rootNames.firstOrNull { it.startsWith("vite.config.") }
            val config = if (configName == null) null else (store.read(projectId, configName) as? CoderReadResult.Text)?.text
            CoderPackageAnalyzer.analyze(packageText, rootNames, config)
        }
        _state.update {
            val keep = it.planChecked && it.outputDir.isNotEmpty() && it.plan?.supported == true && plan.supported
            it.copy(plan = plan, planChecked = true, outputDir = if (keep) it.outputDir else plan.outputDir)
        }
    }

    private suspend fun loadEnv() {
        val names = envStore.load(projectId).map { it.name }
        _state.update {
            val kept = it.envSelected.filter { name -> name in names }.toSet()
            val added = names.filter { name -> name !in it.envNames }
            it.copy(envNames = names, envSelected = kept + added)
        }
    }

    // ---- The build --------------------------------------------------------------------------

    private suspend fun runBuild(plan: CoderBuildPlan, owner: String, repo: String, branch: String) {
        val names = _state.value.envSelected.filter { it in _state.value.envNames }.sorted()
        setFixed(0, BuildStageState.Running)
        val fileProblem = writeWorkflow(CoderBuildWorkflow.generate(plan, names))
        if (fileProblem != null) return failAt(0, "Saving your files to GitHub", fileProblem)
        val token = settings.token() ?: return failAt(0, "Saving your files to GitHub", "Connect your GitHub account first.")

        val prepared = when (val r = pusher.prepare(projectId, owner, repo, branch)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return failAt(0, "Saving your files to GitHub", CoderActionsErrors.improvePushError(r).message)
        }
        val plan0 = prepared.preview.plan
        if (plan0.newFiles.isNotEmpty() || plan0.changedFiles.isNotEmpty()) {
            val answer = CompletableDeferred<Boolean>()
            pushAnswer = answer
            _state.update { it.copy(pendingPush = prepared) }
            val confirmed = answer.await()
            pushAnswer = null
            _state.update { it.copy(pendingPush = null) }
            if (!confirmed) {
                _state.update { it.copy(phase = BuildPhase.Idle, message = "Nothing was pushed. Tap Start build when you're ready.") }
                setFixed(0, BuildStageState.Waiting)
                return
            }
            val pushed = pusher.execute(projectId, owner, repo, branch, prepared.preview, "Update from Neribo Vault Coder") { _, _ -> }
            if (pushed is CoderGitHubResult.Err) {
                return failAt(0, "Saving your files to GitHub", CoderActionsErrors.improvePushError(pushed).message)
            }
        }
        setFixed(0, BuildStageState.Done)

        setFixed(1, BuildStageState.Running)
        val values = envStore.load(projectId).associate { it.name to it.value }
        for (name in names) {
            val value = values[name] ?: continue
            val set = actions.setVariable(token, owner, repo, name, value)
            if (set is CoderGitHubResult.Err) return failAt(1, "Setting variables", set.message)
        }
        setFixed(1, BuildStageState.Done)

        setFixed(2, BuildStageState.Running)
        val started = when (val r = runner.start(token, owner, repo, branch)) {
            is CoderGitHubResult.Ok -> r.value
            is CoderGitHubResult.Err -> return failAt(2, "Starting the build", r.message)
        }
        runId = started.run.id
        dispatchedAt = started.dispatchedAtMillis
        saveState(owner, repo, branch, "running")
        setFixed(2, BuildStageState.Done)
        watchAndFinish(token, owner, repo, branch, plan.outputDir, started.run.id)
    }

    private suspend fun watchAndFinish(token: String, owner: String, repo: String, branch: String, outputDir: String, id: Long) {
        val watched = runner.watch(token, owner, repo, id) { snapshot ->
            githubStages = snapshot.stages()
            publish()
        }
        when (watched) {
            is CoderGitHubResult.Err -> failAt(null, "Watching the build", watched.message)
            is CoderGitHubResult.Ok -> finishRun(token, owner, repo, branch, outputDir, watched.value)
        }
    }

    private suspend fun finishRun(token: String, owner: String, repo: String, branch: String, outputDir: String, snapshot: RunSnapshot) {
        githubStages = snapshot.stages()
        publish()
        val run = snapshot.run
        if (run.conclusion == "success") {
            setFixed(3, BuildStageState.Running)
            when (val r = runner.downloadResult(token, owner, repo, projectId, run, outputDir)) {
                is CoderGitHubResult.Err -> failAt(3, "Downloading the result", r.message)
                is CoderGitHubResult.Ok -> {
                    setFixed(3, BuildStageState.Done)
                    saveState(owner, repo, branch, "done")
                    _state.update { it.copy(phase = BuildPhase.Done) }
                }
            }
            return
        }
        if (run.conclusion == "cancelled") {
            saveState(owner, repo, branch, "cancelled")
            _state.update { it.copy(phase = BuildPhase.Idle, message = "The build was cancelled on GitHub.") }
            return
        }
        val info = runner.failureDetails(token, owner, repo, snapshot.jobs)
        saveState(owner, repo, branch, "failed")
        val failure = BuildFailure(info.stepName, CoderBuildErrorExplainer.explain(info.logTail), info.logTail, null)
        _state.update { it.copy(phase = BuildPhase.Failed, failure = failure) }
    }

    /** Looks once at a run that was still going when the screen was last open, then watches or finishes it. */
    private suspend fun resumeIfRunning() {
        if (_state.value.phase != BuildPhase.Idle) return
        val saved = withContext(Dispatchers.IO) { CoderBuildFiles.readState(store, projectId) } ?: return
        if (saved.status != "running") return
        val project = projects.getById(projectId) ?: return
        if (project.repoOwner != saved.owner || project.repoName != saved.repo || project.repoBranch != saved.branch) return
        val token = settings.token() ?: return
        runId = saved.runId
        dispatchedAt = saved.dispatchedAtMillis
        githubStages = emptyList()
        for (i in fixed.indices) fixed[i] = if (i < 3) BuildStageState.Done else BuildStageState.Waiting
        val outputDir = _state.value.outputDir
        _state.update { it.copy(phase = BuildPhase.Working, failure = null, manualStep = 5) }
        publish()
        buildJob = viewModelScope.launch {
            try {
                when (val first = runner.look(token, saved.owner, saved.repo, saved.runId)) {
                    is CoderGitHubResult.Err -> failAt(null, "Watching the build", first.message)
                    is CoderGitHubResult.Ok ->
                        if (first.value.run.status == "completed") {
                            finishRun(token, saved.owner, saved.repo, saved.branch, outputDir, first.value)
                        } else {
                            githubStages = first.value.stages()
                            publish()
                            watchAndFinish(token, saved.owner, saved.repo, saved.branch, outputDir, saved.runId)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAt(null, "The build", "Something went wrong. Try again.")
            }
        }
    }

    // ---- Small helpers ----------------------------------------------------------------------

    /** Writes the workflow file into the project. Returns a plain-English problem, or null when it is in place. A file Coder did not write is never touched. */
    private suspend fun writeWorkflow(text: String): String? = withContext(Dispatchers.IO) {
        when (val existing = store.read(projectId, CoderBuildWorkflow.WORKFLOW_PATH)) {
            is CoderReadResult.Text -> {
                if (!CoderBuildWorkflow.isOurs(existing.text)) return@withContext CoderBuildWorkflow.MSG_FOREIGN_FILE
                if (existing.text == text) return@withContext null
            }
            CoderReadResult.Missing -> Unit
            else -> return@withContext CoderBuildWorkflow.MSG_FOREIGN_FILE
        }
        if (store.write(projectId, CoderBuildWorkflow.WORKFLOW_PATH, text) == CoderWriteResult.Saved) {
            null
        } else {
            "Couldn't save the build file in your project. Try again."
        }
    }

    private suspend fun saveState(owner: String, repo: String, branch: String, status: String) {
        val id = runId ?: return
        withContext(Dispatchers.IO) {
            CoderBuildFiles.writeState(store, projectId, CoderBuildStateFile(id, dispatchedAt, status, owner, repo, branch))
        }
    }

    /** Ends the build with a failure. [fixedIndex] is the stage that failed, or null when GitHub's own steps show it. */
    private fun failAt(fixedIndex: Int?, stepName: String, message: String) {
        if (fixedIndex != null) fixed[fixedIndex] = BuildStageState.Failed
        publish()
        _state.update {
            it.copy(phase = BuildPhase.Failed, failure = BuildFailure(stepName, null, "", message), pendingPush = null)
        }
    }

    private fun setFixed(index: Int, value: BuildStageState) {
        fixed[index] = value
        publish()
    }

    /** Puts the fixed stages and GitHub's own steps together in the order the screen lists them. */
    private fun publish() {
        val list = ArrayList<BuildStage>()
        list.add(BuildStage("Saving your files to GitHub", fixed[0]))
        list.add(BuildStage("Setting variables", fixed[1]))
        list.add(BuildStage("Starting the build", fixed[2]))
        list.addAll(githubStages)
        list.add(BuildStage("Downloading the result", fixed[3]))
        _state.update { it.copy(stages = list) }
    }

    override fun onCleared() {
        // Leaving the screen stops watching; the run on GitHub carries on and the state file lets a new screen find it.
        buildJob?.cancel()
        scopeJob?.cancel()
        pushAnswer?.complete(false)
        super.onCleared()
    }

    private companion object {
        const val MAX_AI_MESSAGE = 6_000
    }
}
