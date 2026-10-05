package com.westly.neribovault.core.di

import android.content.Context
import com.westly.neribovault.data.cloud.CloudAuth
import com.westly.neribovault.data.cloud.SyncEngine
import com.westly.neribovault.data.local.NeriboDatabase
import com.westly.neribovault.data.local.NoteDeleteTrace
import com.westly.neribovault.data.repository.AuditRepository
import com.westly.neribovault.data.repository.BugsRepository
import com.westly.neribovault.data.repository.ChurchRepository
import com.westly.neribovault.data.repository.DiaryRepository
import com.westly.neribovault.data.repository.FolderPlansRepository
import com.westly.neribovault.data.repository.GoalsRepository
import com.westly.neribovault.data.repository.IdeasRepository
import com.westly.neribovault.data.repository.MemoriesRepository
import com.westly.neribovault.data.repository.NotesRepository
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import com.westly.neribovault.data.repository.PlanningDocsRepository
import com.westly.neribovault.data.repository.PostsRepository
import com.westly.neribovault.data.repository.ProjectDocumentsRepository
import com.westly.neribovault.data.repository.ProjectsRepository
import com.westly.neribovault.data.repository.PromptsRepository
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.data.repository.SecretsRepository
import com.westly.neribovault.data.repository.StoriesRepository
import com.westly.neribovault.data.repository.StoryCharactersRepository
import com.westly.neribovault.data.repository.StoryNotesRepository
import com.westly.neribovault.data.repository.TasksRepository
import com.westly.neribovault.data.repository.WritingIdeasRepository
import com.westly.neribovault.data.settings.SettingsStore

/** Manual dependency container. Built once by [com.westly.neribovault.NeriboApp]. */
class AppContainer(context: Context) {
    val database: NeriboDatabase = NeriboDatabase.create(context)
    private val db: NeriboDatabase = database

    val settingsStore: SettingsStore = SettingsStore(context.applicationContext)
    val noteDeleteTrace: NoteDeleteTrace = NoteDeleteTrace(db)
    val notesRepository: NotesRepository = NotesRepository(db.noteDao(), noteDeleteTrace)
    val ideasRepository: IdeasRepository = IdeasRepository(db.ideaDao())
    val goalsRepository: GoalsRepository = GoalsRepository(db, db.goalDao(), db.goalMilestoneDao())
    val diaryRepository: DiaryRepository = DiaryRepository(db.diaryEntryDao())
    val storiesRepository: StoriesRepository = StoriesRepository(db, db.storyDao(), db.storyChapterDao(), db.storyCharacterDao(), db.storyNoteDao())
    val storyCharactersRepository: StoryCharactersRepository = StoryCharactersRepository(db.storyCharacterDao())
    val storyNotesRepository: StoryNotesRepository = StoryNotesRepository(db.storyNoteDao())
    val writingIdeasRepository: WritingIdeasRepository = WritingIdeasRepository(db.writingIdeaDao())
    val postsRepository: PostsRepository = PostsRepository(db.socialPostDao())
    val churchRepository: ChurchRepository = ChurchRepository(db.churchRecordDao())
    val memoriesRepository: MemoriesRepository = MemoriesRepository(db.memoryDao())
    val personalDocumentsRepository: PersonalDocumentsRepository = PersonalDocumentsRepository(db.personalDocumentDao())
    val projectsRepository: ProjectsRepository = ProjectsRepository(db.projectDao())
    val secretsRepository: SecretsRepository = SecretsRepository(db.secretDao())
    val bugsRepository: BugsRepository = BugsRepository(db.bugDao())
    val tasksRepository: TasksRepository = TasksRepository(db.taskDao())
    val planningDocsRepository: PlanningDocsRepository = PlanningDocsRepository(db.planningDocDao())
    val folderPlansRepository: FolderPlansRepository = FolderPlansRepository(db.folderPlanDao())
    val promptsRepository: PromptsRepository = PromptsRepository(db.promptDao())
    val projectDocumentsRepository: ProjectDocumentsRepository = ProjectDocumentsRepository(db.projectDocumentDao())
    val auditRepository: AuditRepository = AuditRepository(db.auditLogDao())
    val screenplaysRepository: ScreenplaysRepository = ScreenplaysRepository(db.screenplayDao())

    val cloudAuth: CloudAuth = CloudAuth(context.applicationContext, db)
    val syncEngine: SyncEngine = SyncEngine(context.applicationContext, db, cloudAuth)

    /** Permanently removes everything that has been in the trash since before [cutoffMillis]. */
    suspend fun purgeExpiredTrash(cutoffMillis: Long) {
        notesRepository.purgeTrashedBefore(cutoffMillis, "Clean-up: note had been in Recently deleted for over 30 days")
        ideasRepository.purgeTrashedBefore(cutoffMillis)
        goalsRepository.purgeTrashedBefore(cutoffMillis)
        diaryRepository.purgeTrashedBefore(cutoffMillis)
        storiesRepository.purgeTrashedBefore(cutoffMillis)
        storyCharactersRepository.purgeTrashedBefore(cutoffMillis)
        storyNotesRepository.purgeTrashedBefore(cutoffMillis)
        writingIdeasRepository.purgeTrashedBefore(cutoffMillis)
        postsRepository.purgeTrashedBefore(cutoffMillis)
        churchRepository.purgeTrashedBefore(cutoffMillis)
        memoriesRepository.purgeTrashedBefore(cutoffMillis)
        personalDocumentsRepository.purgeTrashedBefore(cutoffMillis)
        projectsRepository.purgeTrashedBefore(cutoffMillis)
        secretsRepository.purgeTrashedBefore(cutoffMillis)
        bugsRepository.purgeTrashedBefore(cutoffMillis)
        tasksRepository.purgeTrashedBefore(cutoffMillis)
        planningDocsRepository.purgeTrashedBefore(cutoffMillis)
        folderPlansRepository.purgeTrashedBefore(cutoffMillis)
        promptsRepository.purgeTrashedBefore(cutoffMillis)
        projectDocumentsRepository.purgeTrashedBefore(cutoffMillis)
        screenplaysRepository.purgeTrashedBefore(cutoffMillis)
    }
}
