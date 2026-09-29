package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A story project. status: idea, drafting, revising, complete. */
@Entity(tableName = "stories")
data class StoryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val synopsis: String,
    val genre: String,
    val status: String = "idea",
    val targetWordCount: Int? = null,
)

/** A chapter of a story. */
@Entity(tableName = "story_chapters", indices = [Index("storyId")])
data class StoryChapterEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val storyId: String,
    val title: String,
    val body: String,
    val sortOrder: Int,
    val wordCount: Int,
)

/** A character of a story. */
@Entity(tableName = "story_characters", indices = [Index("storyId")])
data class StoryCharacterEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val storyId: String,
    val name: String,
    val role: String,
    val description: String,
    val traits: List<String> = emptyList(),
    val backstory: String = "",
)

/** A planning note attached to a story. */
@Entity(tableName = "story_notes", indices = [Index("storyId")])
data class StoryNoteEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val storyId: String,
    val title: String,
    val body: String,
    val category: String,
)

/** A loose writing idea. status: spark, developing, used. */
@Entity(tableName = "writing_ideas")
data class WritingIdeaEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val body: String,
    val genre: String? = null,
    val status: String = "spark",
)
