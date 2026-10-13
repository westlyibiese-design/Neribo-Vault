package com.westly.neribovault.feature.coder.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.CoderCheckResult
import com.westly.neribovault.feature.coder.files.CoderStarterFile
import com.westly.neribovault.feature.coder.runCoderSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Coder home screen shows. [createdProjectId] is set once after a project was made, so the screen can open it. */
data class CoderHomeUiState(
    val projects: List<CoderProjectEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isCreating: Boolean = false,
    val createdProjectId: String? = null,
    val message: String? = null,
    val selfTest: List<CoderCheckResult>? = null,
)

private val BLANK_HTML: String = listOf(
    "<!DOCTYPE html>",
    "<html lang=\"en\">",
    "<head>",
    "  <meta charset=\"UTF-8\">",
    "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
    "  <title>My page</title>",
    "</head>",
    "<body>",
    "  <h1>Hello</h1>",
    "</body>",
    "</html>",
    "",
).joinToString("\n")

/** State and actions for the list of Coder projects. */
class CoderHomeViewModel(
    private val projects: CoderProjectsRepository,
) : ViewModel() {

    private data class Local(
        val isCreating: Boolean = false,
        val createdProjectId: String? = null,
        val message: String? = null,
        val selfTest: List<CoderCheckResult>? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<CoderHomeUiState> = combine(projects.observeAll(), local) { list, extra ->
        CoderHomeUiState(
            projects = list,
            isLoading = false,
            isCreating = extra.isCreating,
            createdProjectId = extra.createdProjectId,
            message = extra.message,
            selfTest = extra.selfTest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoderHomeUiState())

    /** Makes a blank HTML project. A second call while one is being made is ignored. */
    fun createProject(name: String) {
        if (local.value.isCreating) return
        local.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            val created = projects.createProject(name, "blank", listOf(CoderStarterFile("index.html", BLANK_HTML)))
            local.update {
                if (created != null) {
                    it.copy(isCreating = false, createdProjectId = created.id)
                } else {
                    it.copy(isCreating = false, message = "Couldn't create the project. Please try again.")
                }
            }
        }
    }

    fun consumeCreated() {
        local.update { it.copy(createdProjectId = null) }
    }

    fun consumeMessage() {
        local.update { it.copy(message = null) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { projects.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { projects.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { projects.restore(id) }
    }

    /** Debug only: runs the Coder self-test. Only the application context is used, and it is not kept. */
    fun runSelfTest(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { runCoderSelfTest(appContext) }
            local.update { it.copy(selfTest = results) }
        }
    }

    fun dismissSelfTest() {
        local.update { it.copy(selfTest = null) }
    }
}
