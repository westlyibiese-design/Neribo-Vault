package com.westly.neribovault.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.westly.neribovault.data.local.dao.AuditLogDao
import com.westly.neribovault.data.local.dao.BugDao
import com.westly.neribovault.data.local.dao.ChurchRecordDao
import com.westly.neribovault.data.local.dao.DiaryEntryDao
import com.westly.neribovault.data.local.dao.FolderPlanDao
import com.westly.neribovault.data.local.dao.GoalDao
import com.westly.neribovault.data.local.dao.GoalMilestoneDao
import com.westly.neribovault.data.local.dao.IdeaDao
import com.westly.neribovault.data.local.dao.MemoryDao
import com.westly.neribovault.data.local.dao.NoteDao
import com.westly.neribovault.data.local.dao.PersonalDocumentDao
import com.westly.neribovault.data.local.dao.PlanningDocDao
import com.westly.neribovault.data.local.dao.ProjectDao
import com.westly.neribovault.data.local.dao.ProjectDocumentDao
import com.westly.neribovault.data.local.dao.PromptDao
import com.westly.neribovault.data.local.dao.SecretDao
import com.westly.neribovault.data.local.dao.SocialPostDao
import com.westly.neribovault.data.local.dao.StoryChapterDao
import com.westly.neribovault.data.local.dao.StoryCharacterDao
import com.westly.neribovault.data.local.dao.StoryDao
import com.westly.neribovault.data.local.dao.StoryNoteDao
import com.westly.neribovault.data.local.dao.TaskDao
import com.westly.neribovault.data.local.dao.WritingIdeaDao
import com.westly.neribovault.data.local.entity.AuditLogEntity
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

/** The single Room database of the app. Version 1 holds every table the roadmap needs. */
@Database(
    entities = [
        NoteEntity::class,
        IdeaEntity::class,
        GoalEntity::class,
        GoalMilestoneEntity::class,
        DiaryEntryEntity::class,
        StoryEntity::class,
        StoryChapterEntity::class,
        StoryCharacterEntity::class,
        StoryNoteEntity::class,
        WritingIdeaEntity::class,
        SocialPostEntity::class,
        ChurchRecordEntity::class,
        MemoryEntity::class,
        PersonalDocumentEntity::class,
        ProjectEntity::class,
        SecretEntity::class,
        BugEntity::class,
        TaskEntity::class,
        PlanningDocEntity::class,
        FolderPlanEntity::class,
        PromptEntity::class,
        ProjectDocumentEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class NeriboDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun ideaDao(): IdeaDao
    abstract fun goalDao(): GoalDao
    abstract fun goalMilestoneDao(): GoalMilestoneDao
    abstract fun diaryEntryDao(): DiaryEntryDao
    abstract fun storyDao(): StoryDao
    abstract fun storyChapterDao(): StoryChapterDao
    abstract fun storyCharacterDao(): StoryCharacterDao
    abstract fun storyNoteDao(): StoryNoteDao
    abstract fun writingIdeaDao(): WritingIdeaDao
    abstract fun socialPostDao(): SocialPostDao
    abstract fun churchRecordDao(): ChurchRecordDao
    abstract fun memoryDao(): MemoryDao
    abstract fun personalDocumentDao(): PersonalDocumentDao
    abstract fun projectDao(): ProjectDao
    abstract fun secretDao(): SecretDao
    abstract fun bugDao(): BugDao
    abstract fun taskDao(): TaskDao
    abstract fun planningDocDao(): PlanningDocDao
    abstract fun folderPlanDao(): FolderPlanDao
    abstract fun promptDao(): PromptDao
    abstract fun projectDocumentDao(): ProjectDocumentDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        const val FILE_NAME = "neribo_vault.db"

        /** Builds the database. Call once; [AppContainer] keeps the only instance. */
        fun create(context: Context): NeriboDatabase =
            Room.databaseBuilder(context.applicationContext, NeriboDatabase::class.java, FILE_NAME).build()
    }
}
