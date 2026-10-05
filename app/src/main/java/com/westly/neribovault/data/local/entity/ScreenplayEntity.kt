package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A screenplay in the Screenplays vault. [content] is the script body as Fountain text; the
 * title page fields ([title], [author], [contact]) and the notice settings are kept apart from it.
 * An empty [noticeText] means "use the default notice".
 */
@Entity(tableName = "screenplays")
data class ScreenplayEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val title: String,
    val author: String,
    val contact: String,
    val noticeEnabled: Boolean,
    val noticeText: String,
    val content: String,
)
