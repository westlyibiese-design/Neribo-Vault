package com.westly.neribovault.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.di.AppContainer
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.core.util.startOfDayMillis
import com.westly.neribovault.data.local.NoteTraceEntry
import com.westly.neribovault.data.local.entity.BugEntity
import com.westly.neribovault.data.local.entity.ChurchRecordEntity
import com.westly.neribovault.data.local.entity.DiaryEntryEntity
import com.westly.neribovault.data.local.entity.FolderPlanEntity
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import com.westly.neribovault.data.local.entity.IdeaEntity
import com.westly.neribovault.data.local.entity.MemoryEntity
import com.westly.neribovault.data.local.entity.NoteEntity
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.data.local.entity.PlanningDocEntity
import com.westly.neribovault.data.local.entity.ProjectDocumentEntity
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.PromptEntity
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.data.local.entity.StoryCharacterEntity
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.local.entity.StoryNoteEntity
import com.westly.neribovault.data.local.entity.TaskEntity
import com.westly.neribovault.data.local.entity.WritingIdeaEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of the self-test for one repository. */
data class SelfTestResult(val name: String, val passed: Boolean, val message: String? = null)

/** Number of non-deleted rows in one table. */
data class RowCount(val label: String, val count: Int)

/** State of the Diagnostics screen. */
data class DiagnosticsUiState(
    val isRunning: Boolean = false,
    val results: List<SelfTestResult> = emptyList(),
    val counts: List<RowCount> = emptyList(),
    val noteTrace: List<NoteTraceEntry> = emptyList(),
)

/** Runs a full insert, read, trash, restore and delete cycle on every repository. */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refreshCounts()
        refreshNoteTrace()
    }

    /** Reloads the note delete trace. */
    fun refreshNoteTrace() {
        viewModelScope.launch {
            val entries = container.noteDeleteTrace.recent()
            _state.update { it.copy(noteTrace = entries) }
        }
    }

    fun clearNoteTrace() {
        viewModelScope.launch {
            container.noteDeleteTrace.clear()
            _state.update { it.copy(noteTrace = emptyList()) }
        }
    }

    fun runSelfTest() {
        if (_state.value.isRunning) return
        _state.update { it.copy(isRunning = true, results = emptyList()) }
        viewModelScope.launch {
            for (test in tests()) {
                val result = test()
                _state.update { it.copy(results = it.results + result) }
            }
            _state.update { it.copy(isRunning = false) }
            loadCounts()
            refreshNoteTrace()
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch { loadCounts() }
    }

    private suspend fun loadCounts() {
        val db = container.database
        val counts = listOf(
            RowCount("Notes", db.noteDao().countActive()),
            RowCount("Ideas", db.ideaDao().countActive()),
            RowCount("Goals", db.goalDao().countActive()),
            RowCount("Goal milestones", db.goalMilestoneDao().countAll()),
            RowCount("Diary entries", db.diaryEntryDao().countActive()),
            RowCount("Stories", db.storyDao().countActive()),
            RowCount("Story chapters", db.storyChapterDao().countActive()),
            RowCount("Characters", db.storyCharacterDao().countActive()),
            RowCount("Story notes", db.storyNoteDao().countActive()),
            RowCount("Writing ideas", db.writingIdeaDao().countActive()),
            RowCount("Posts", db.socialPostDao().countActive()),
            RowCount("Church records", db.churchRecordDao().countActive()),
            RowCount("Memories", db.memoryDao().countActive()),
            RowCount("Personal documents", db.personalDocumentDao().countActive()),
            RowCount("Projects", db.projectDao().countActive()),
            RowCount("Secrets", db.secretDao().countActive()),
            RowCount("Bugs", db.bugDao().countActive()),
            RowCount("Tasks", db.taskDao().countActive()),
            RowCount("Planning docs", db.planningDocDao().countActive()),
            RowCount("Folder plans", db.folderPlanDao().countActive()),
            RowCount("Prompts", db.promptDao().countActive()),
            RowCount("Project documents", db.projectDocumentDao().countActive()),
            RowCount("Audit log", db.auditLogDao().countAll()),
        )
        _state.update { it.copy(counts = counts) }
    }

    /**
     * Runs one full cycle for an entity: write, read back, observe, soft delete, check the trash,
     * restore and delete permanently. Always removes the sample item, even on failure.
     */
    private suspend fun <T> cycle(
        name: String,
        id: String,
        idOf: (T) -> String,
        upsert: suspend () -> Unit,
        getById: suspend () -> T?,
        observeAll: Flow<List<T>>,
        observeTrashed: Flow<List<T>>,
        softDelete: suspend () -> Unit,
        restore: suspend () -> Unit,
        delete: suspend () -> Unit,
        afterUpsert: suspend () -> Unit = {},
        afterDelete: suspend () -> Unit = {},
    ): SelfTestResult {
        return try {
            upsert()
            check(getById() != null) { "Read back returned nothing" }
            check(observeAll.first().any { idOf(it) == id }) { "Not found in observeAll" }
            afterUpsert()
            softDelete()
            check(observeAll.first().none { idOf(it) == id }) { "Still in observeAll after soft delete" }
            check(observeTrashed.first().any { idOf(it) == id }) { "Not found in observeTrashed" }
            restore()
            check(observeAll.first().any { idOf(it) == id }) { "Not back in observeAll after restore" }
            delete()
            check(getById() == null) { "Still present after permanent delete" }
            afterDelete()
            SelfTestResult(name, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SelfTestResult(name, false, e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                try {
                    delete()
                } catch (e: Exception) {
                    // Best-effort cleanup; the result above already reports the real problem.
                }
            }
        }
    }

    private fun tests(): List<suspend () -> SelfTestResult> {
        val c = container
        return listOf<suspend () -> SelfTestResult>(
            { notesTest(c) },
            { ideasTest(c) },
            { goalsTest(c) },
            { diaryTest(c) },
            { storiesTest(c) },
            { charactersTest(c) },
            { storyNotesTest(c) },
            { writingIdeasTest(c) },
            { postsTest(c) },
            { churchTest(c) },
            { memoriesTest(c) },
            { documentsTest(c) },
            { projectsTest(c) },
            { secretsTest(c) },
            { bugsTest(c) },
            { tasksTest(c) },
            { planningDocsTest(c) },
            { folderPlansTest(c) },
            { promptsTest(c) },
            { projectDocumentsTest(c) },
            { auditTest(c) },
        )
    }

    private suspend fun notesTest(c: AppContainer): SelfTestResult {
        val r = c.notesRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = NoteEntity(id = id, createdAt = now, updatedAt = now, title = "Sunday service reminder", body = "Bring the offering envelope.")
        return cycle<NoteEntity>("Notes", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id, "Diagnostics self-test") }, { r.restore(id) }, { r.deletePermanently(id, "Diagnostics self-test") })
    }

    private suspend fun ideasTest(c: AppContainer): SelfTestResult {
        val r = c.ideasRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = IdeaEntity(id = id, createdAt = now, updatedAt = now, title = "Jollof pop-up stall", description = "Weekend stall in Benin City.", category = "business")
        return cycle<IdeaEntity>("Ideas", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun goalsTest(c: AppContainer): SelfTestResult {
        val r = c.goalsRepository
        val id = newId()
        val milestoneId = newId()
        val now = System.currentTimeMillis()
        val item = GoalEntity(id = id, createdAt = now, updatedAt = now, title = "Read one book a month", description = "Start with a short one.", category = "learning")
        val milestone = GoalMilestoneEntity(id = milestoneId, goalId = id, title = "Pick the first book", sortOrder = 0, createdAt = now, updatedAt = now)
        return cycle<GoalEntity>(
            "Goals and milestones", id, { it.id },
            { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(),
            { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) },
            afterUpsert = {
                r.upsertMilestone(milestone)
                check(r.observeMilestones(id).first().any { it.id == milestoneId }) { "Milestone not found" }
            },
            afterDelete = {
                check(r.observeMilestones(id).first().isEmpty()) { "Milestone left behind after goal delete" }
            },
        )
    }

    private suspend fun diaryTest(c: AppContainer): SelfTestResult {
        val r = c.diaryRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = DiaryEntryEntity(id = id, createdAt = now, updatedAt = now, entryDate = startOfDayMillis(now), title = "Harmattan morning", body = "Cold air and a slow start.")
        return cycle<DiaryEntryEntity>("Diary", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun storiesTest(c: AppContainer): SelfTestResult {
        val r = c.storiesRepository
        val id = newId()
        val chapterId = newId()
        val now = System.currentTimeMillis()
        val item = StoryEntity(id = id, createdAt = now, updatedAt = now, title = "The Danfo Driver", synopsis = "A night route through Lagos.", genre = "Drama")
        val chapter = StoryChapterEntity(id = chapterId, createdAt = now, updatedAt = now, storyId = id, title = "Chapter one", body = "Tunde starts the engine.", sortOrder = 0, wordCount = 4)
        return cycle<StoryEntity>(
            "Stories and chapters", id, { it.id },
            { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(),
            { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) },
            afterUpsert = {
                r.upsertChapter(chapter)
                check(r.observeChapters(id).first().any { it.id == chapterId }) { "Chapter not found" }
                val stats = r.observeStoryStats().first()[id]
                check(stats != null && stats.chapterCount == 1 && stats.wordCount == 4) { "Story stats are wrong" }
            },
            afterDelete = {
                check(r.getChapterById(chapterId) == null) { "Chapter left behind after story delete" }
            },
        )
    }

    private suspend fun charactersTest(c: AppContainer): SelfTestResult {
        val r = c.storyCharactersRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = StoryCharacterEntity(id = id, createdAt = now, updatedAt = now, storyId = newId(), name = "Adaeze", role = "protagonist", description = "A nurse from Enugu.")
        return cycle<StoryCharacterEntity>("Characters", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun storyNotesTest(c: AppContainer): SelfTestResult {
        val r = c.storyNotesRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = StoryNoteEntity(id = id, createdAt = now, updatedAt = now, storyId = newId(), title = "Setting: Onitsha market", body = "Crowded, loud, alive.", category = "setting")
        return cycle<StoryNoteEntity>("Story notes", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun writingIdeasTest(c: AppContainer): SelfTestResult {
        val r = c.writingIdeasRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = WritingIdeaEntity(id = id, createdAt = now, updatedAt = now, title = "NEPA and the wedding", body = "Light goes off during the vows.")
        return cycle<WritingIdeaEntity>("Writing ideas", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun postsTest(c: AppContainer): SelfTestResult {
        val r = c.postsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = SocialPostEntity(id = id, createdAt = now, updatedAt = now, platform = "instagram", title = "Monday motivation", caption = "Small steps still count.")
        return cycle<SocialPostEntity>("Posts", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun churchTest(c: AppContainer): SelfTestResult {
        val r = c.churchRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = ChurchRecordEntity(id = id, createdAt = now, updatedAt = now, type = "sermon", title = "Faith in the waiting", recordDate = startOfDayMillis(now))
        return cycle<ChurchRecordEntity>("Church records", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun memoriesTest(c: AppContainer): SelfTestResult {
        val r = c.memoriesRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = MemoryEntity(id = id, createdAt = now, updatedAt = now, title = "Christmas in the village", description = "Everyone home at last.", memoryDate = now, location = "Benin City")
        return cycle<MemoryEntity>("Memories", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun documentsTest(c: AppContainer): SelfTestResult {
        val r = c.personalDocumentsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = PersonalDocumentEntity(id = id, createdAt = now, updatedAt = now, title = "Driver's licence", category = "identity", issuer = "FRSC", notes = "Renew before expiry.")
        return cycle<PersonalDocumentEntity>("Personal documents", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun projectsTest(c: AppContainer): SelfTestResult {
        val r = c.projectsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = ProjectEntity(id = id, createdAt = now, updatedAt = now, name = "Church attendance tracker", description = "Simple attendance app.", status = "planning", repoUrl = "", liveUrl = "")
        return cycle<ProjectEntity>("Projects", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun secretsTest(c: AppContainer): SelfTestResult {
        val r = c.secretsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = SecretEntity(id = id, createdAt = now, updatedAt = now, label = "Hosting dashboard link", category = "link", isSecret = false, publicValue = "https://example.ng")
        return cycle<SecretEntity>("Secrets", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun bugsTest(c: AppContainer): SelfTestResult {
        val r = c.bugsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = BugEntity(id = id, createdAt = now, updatedAt = now, title = "Receipt total is off by one kobo", description = "Rounding on the invoice page.", severity = "low", status = "open", stepsToReproduce = "Add three items and check the total.", resolution = "")
        return cycle<BugEntity>("Bugs", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun tasksTest(c: AppContainer): SelfTestResult {
        val r = c.tasksRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = TaskEntity(id = id, createdAt = now, updatedAt = now, title = "Buy data bundle", notes = "Before Friday.", priority = "normal")
        return cycle<TaskEntity>("Tasks", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun planningDocsTest(c: AppContainer): SelfTestResult {
        val r = c.planningDocsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = PlanningDocEntity(id = id, createdAt = now, updatedAt = now, title = "Booking flow outline", body = "Guest picks dates, then a room.", kind = "feature")
        return cycle<PlanningDocEntity>("Planning docs", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun folderPlansTest(c: AppContainer): SelfTestResult {
        val r = c.folderPlansRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = FolderPlanEntity(id = id, createdAt = now, updatedAt = now, title = "Web app layout", treeText = "src/\n  pages/\n  components/")
        return cycle<FolderPlanEntity>("Folder plans", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun promptsTest(c: AppContainer): SelfTestResult {
        val r = c.promptsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = PromptEntity(id = id, createdAt = now, updatedAt = now, title = "Explain this error", body = "Explain this error message in plain words.", category = "debugging")
        return cycle<PromptEntity>("Prompts", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    private suspend fun projectDocumentsTest(c: AppContainer): SelfTestResult {
        val r = c.projectDocumentsRepository
        val id = newId()
        val now = System.currentTimeMillis()
        val item = ProjectDocumentEntity(id = id, createdAt = now, updatedAt = now, title = "Client handover notes", body = "Logins, hosting and support contacts.", kind = "handover")
        return cycle<ProjectDocumentEntity>("Project documents", id, { it.id }, { r.upsert(item) }, { r.getById(id) }, r.observeAll(), r.observeTrashed(), { r.softDelete(id) }, { r.restore(id) }, { r.deletePermanently(id) })
    }

    /** The audit log has no trash, so it gets its own shorter check. */
    private suspend fun auditTest(c: AppContainer): SelfTestResult {
        val marker = newId()
        val dao = c.database.auditLogDao()
        var entryId: String? = null
        return try {
            c.auditRepository.log("diagnostics_check", "diagnostics", marker)
            val entry = c.auditRepository.observeRecent(500).first().firstOrNull { it.entityId == marker }
            check(entry != null) { "Logged entry not found in observeRecent" }
            entryId = entry.id
            SelfTestResult("Audit log", true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SelfTestResult("Audit log", false, e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                try {
                    val found = entryId ?: dao.observeRecent(500).first().firstOrNull { it.entityId == marker }?.id
                    if (found != null) dao.deleteById(found)
                } catch (e: Exception) {
                    // Best-effort cleanup.
                }
            }
        }
    }
}
