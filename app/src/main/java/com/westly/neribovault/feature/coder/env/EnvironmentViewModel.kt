package com.westly.neribovault.feature.coder.env

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A `.env` file that was read and is waiting for the person to confirm adding its variables. Holds the values, so it lives in memory only. */
data class DotEnvImport(
    val path: String,
    val variables: List<Pair<String, String>>,
    val skippedLines: Int,
    val overwrites: List<String>,
)

/** Everything the Environment screen shows. The values are only in memory; they are never saved with the screen's state. */
data class EnvironmentUiState(
    val project: CoderProjectEntity? = null,
    val isLoading: Boolean = true,
    val vars: List<EnvVar> = emptyList(),
    val envFiles: List<String> = emptyList(),
    val pendingImport: DotEnvImport? = null,
    /** The `.env` file the person is asked about after an import. */
    val askDeleteFile: String? = null,
    val message: String? = null,
)

/**
 * State and actions of the Environment screen of one project. Variables are read from and saved to
 * [CoderEnvStore] (encrypted); `.env` files are found and read through [CoderFileStore]. Nothing here is logged
 * or sent anywhere.
 */
class EnvironmentViewModel(
    private val projectId: String,
    private val projects: CoderProjectsRepository,
    private val envStore: CoderEnvStore,
    private val files: CoderFileStore,
) : ViewModel() {
    private val _state = MutableStateFlow(EnvironmentUiState())

    /** What the screen shows. */
    val state: StateFlow<EnvironmentUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = envStore.load(projectId)
            val found = findEnvFiles()
            val project = projects.getById(projectId)
            _state.update { it.copy(project = project, vars = loaded, envFiles = found, isLoading = false) }
        }
    }

    /** The reason [name] and [value] cannot be saved (for the sheet to show while typing), or null. [originalName] is null for a new variable. */
    fun problemWith(originalName: String?, name: String, value: String): String? =
        CoderEnvRules.validateForSave(_state.value.vars, originalName, name, value)

    /** Saves a new variable, or the edit of the variable called [originalName]. Returns false (and says nothing) when the rules refuse it. */
    fun save(originalName: String?, name: String, value: String): Boolean {
        if (problemWith(originalName, name, value) != null) return false
        val current = _state.value.vars
        val next = if (originalName == null) {
            current + EnvVar(name, value)
        } else {
            current.map { if (it.name == originalName) EnvVar(name, value) else it }
        }
        persist(next, "Saved")
        return true
    }

    /** Removes the variable called [name]. */
    fun delete(name: String) {
        persist(_state.value.vars.filter { it.name != name }, "Variable deleted")
    }

    private fun persist(next: List<EnvVar>, successMessage: String) {
        viewModelScope.launch {
            if (envStore.save(projectId, next)) {
                _state.update { it.copy(vars = next, message = successMessage) }
            } else {
                _state.update { it.copy(message = "Couldn't save the variables. Try again.") }
            }
        }
    }

    /** Looks for `.env` files again (after the project's files changed). */
    fun refreshEnvFiles() {
        viewModelScope.launch {
            val found = findEnvFiles()
            _state.update { it.copy(envFiles = found) }
        }
    }

    /** Reads the `.env` file at [path] and asks the person to confirm before anything is added. */
    fun prepareImport(path: String) {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { files.read(projectId, path) }
            if (read !is CoderReadResult.Text) {
                _state.update { it.copy(message = "Couldn't read that file.") }
                return@launch
            }
            val parsed = CoderEnvRules.parseDotEnvDetailed(read.text)
            if (parsed.variables.isEmpty()) {
                _state.update { it.copy(message = "No variables found in that file.") }
                return@launch
            }
            val overwrites = CoderEnvRules.overwrittenNames(_state.value.vars, parsed.variables)
            _state.update { it.copy(pendingImport = DotEnvImport(path, parsed.variables, parsed.skippedLines, overwrites)) }
        }
    }

    /** The person said no: forget the file that was read. */
    fun cancelImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    /** Adds the waiting variables (names that exist get the new value), then asks whether to delete the `.env` file. */
    fun confirmImport() {
        val pending = _state.value.pendingImport ?: return
        val merge = CoderEnvRules.merge(_state.value.vars, pending.variables)
        viewModelScope.launch {
            if (envStore.save(projectId, merge.merged)) {
                val text = when {
                    merge.notAdded > 0 -> "Added ${merge.added + merge.overwritten}. ${merge.notAdded} didn't fit: the limit is 50 variables."
                    else -> "Added ${merge.added + merge.overwritten} variables"
                }
                _state.update { it.copy(vars = merge.merged, pendingImport = null, askDeleteFile = pending.path, message = text) }
            } else {
                _state.update { it.copy(pendingImport = null, message = "Couldn't save the variables. Try again.") }
            }
        }
    }

    /** The answer to "Delete the .env file from this project?". */
    fun answerDeleteFile(delete: Boolean) {
        val path = _state.value.askDeleteFile ?: return
        _state.update { it.copy(askDeleteFile = null) }
        if (!delete) return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { files.delete(projectId, path) }
            val found = findEnvFiles()
            _state.update { it.copy(envFiles = found, message = if (removed) "File deleted" else "Couldn't delete the file.") }
        }
    }

    /** Marks the snackbar message as shown. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun findEnvFiles(): List<String> = withContext(Dispatchers.IO) {
        files.listAll(projectId)
            .filter { !it.isDirectory }
            .map { it.path }
            .filter { path ->
                val name = CoderPaths.nameOf(path)
                name == ".env" || name.startsWith(".env.")
            }
    }
}
