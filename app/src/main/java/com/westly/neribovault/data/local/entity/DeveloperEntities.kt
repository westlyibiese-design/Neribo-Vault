package com.westly.neribovault.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A software project in the Developer vault. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val name: String,
    val description: String,
    val status: String,
    val techStack: List<String> = emptyList(),
    val repoUrl: String,
    val liveUrl: String,
)

/** A stored credential or key. Encryption fields are used from Phase 12. */
@Entity(tableName = "secrets", indices = [Index("projectId")])
data class SecretEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val label: String,
    val category: String,
    val isSecret: Boolean,
    val publicValue: String? = null,
    val ciphertext: String? = null,
    val iv: String? = null,
    val pinSalt: String? = null,
    val pinAHash: String? = null,
    val pinBHash: String? = null,
    val decoyValue: String? = null,
    val lockedUntil: Long? = null,
)

/** A bug report. */
@Entity(tableName = "bugs", indices = [Index("projectId")])
data class BugEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val description: String,
    val severity: String,
    val status: String,
    val stepsToReproduce: String,
    val resolution: String,
    val resolvedAt: Long? = null,
)

/** A to-do item. */
@Entity(tableName = "tasks", indices = [Index("projectId")])
data class TaskEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val notes: String,
    val dueAt: Long? = null,
    val priority: String,
    val isDone: Boolean = false,
    val doneAt: Long? = null,
    val repeatRule: String? = null,
)

/** A planning document. */
@Entity(tableName = "planning_docs", indices = [Index("projectId")])
data class PlanningDocEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val body: String,
    val kind: String,
)

/** A planned folder structure. */
@Entity(tableName = "folder_plans", indices = [Index("projectId")])
data class FolderPlanEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val treeText: String,
)

/** A saved AI prompt. */
@Entity(tableName = "prompts", indices = [Index("projectId")])
data class PromptEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val body: String,
    val category: String,
    val isFavorite: Boolean = false,
)

/** A document attached to a project. */
@Entity(tableName = "project_documents", indices = [Index("projectId")])
data class ProjectDocumentEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val projectId: String? = null,
    val title: String,
    val body: String,
    val kind: String,
)

/** One line of the audit log. No soft-delete columns. */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val action: String,
    val entityType: String,
    val entityId: String? = null,
    val createdAt: Long,
)
