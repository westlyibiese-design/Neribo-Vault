package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One code project in the Coder vault. Only the metadata lives in Room: the project's files are a
 * real folder tree in the app's private storage (`filesDir/coder/<id>`).
 * [templateId] is "blank", a template id, or "zip" for an imported project.
 */
@Entity(tableName = "coder_projects")
data class CoderProjectEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val name: String,
    val templateId: String,
)
