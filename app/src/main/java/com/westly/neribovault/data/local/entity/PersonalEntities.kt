package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A note in the Notes vault. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
)

/** An idea. category: general, business, app, content, creative, personal. status: new, exploring, in_progress, done, dropped. */
@Entity(tableName = "ideas")
data class IdeaEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val description: String,
    val category: String,
    val status: String = "new",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
)

/** A goal. status: active, paused, completed. */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val description: String,
    val category: String,
    val targetDate: Long? = null,
    val status: String = "active",
    val completedAt: Long? = null,
    val isPinned: Boolean = false,
)

/** A checklist step of a goal. Hard-deleted; it has no soft-delete columns. */
@Entity(tableName = "goal_milestones", indices = [Index("goalId")])
data class GoalMilestoneEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val title: String,
    val isDone: Boolean = false,
    val doneAt: Long? = null,
    val dueDate: Long? = null,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A diary entry. entryDate is the start-of-day epoch millis of the day it is about. */
@Entity(tableName = "diary_entries", indices = [Index("entryDate")])
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val entryDate: Long,
    val title: String,
    val body: String,
    val mood: String? = null,
    val tags: List<String> = emptyList(),
)

/** A social media post draft. status: idea, draft, scheduled, posted. */
@Entity(tableName = "social_posts")
data class SocialPostEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val platform: String,
    val title: String,
    val caption: String,
    val hashtags: List<String> = emptyList(),
    val status: String = "idea",
    val scheduledAt: Long? = null,
    val postedAt: Long? = null,
    val notes: String = "",
)

/** A sermon, message, Bible study, teaching or prayer record. */
@Entity(tableName = "church_records", indices = [Index("recordDate")])
data class ChurchRecordEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val type: String,
    val title: String,
    val speaker: String = "",
    val church: String = "",
    val recordDate: Long,
    val scriptureRefs: String = "",
    val summary: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
)

/** A remembered moment. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val description: String,
    val memoryDate: Long,
    val location: String,
    val people: List<String> = emptyList(),
    val photoUris: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** A personal document record such as an ID or certificate. */
@Entity(tableName = "personal_documents")
data class PersonalDocumentEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val category: String,
    val issuer: String,
    val issueDate: Long? = null,
    val expiryDate: Long? = null,
    val remindDaysBefore: Int = 30,
    val fileUri: String? = null,
    val notes: String,
)
