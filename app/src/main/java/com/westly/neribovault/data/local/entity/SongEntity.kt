package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song in the Lyrics vault. [content] holds the lyrics in the Lyrics text format (section
 * labels in square brackets, a blank line between sections). [notes] are private and never printed.
 * [status] is one of "idea", "draft" or "finished".
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val writer: String,
    val songKey: String,
    val tempoBpm: Int?,
    val mood: String,
    val status: String,
    val notes: String,
    val content: String,
)
