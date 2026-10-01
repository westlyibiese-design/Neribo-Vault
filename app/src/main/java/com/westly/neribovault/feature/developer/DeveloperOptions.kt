package com.westly.neribovault.feature.developer

import com.westly.neribovault.data.local.entity.ProjectEntity

/** One project status: the stored value and the label shown to the owner. */
data class ProjectStatus(val value: String, val label: String)

/** Every project status, in the order the editor offers them. */
val PROJECT_STATUSES: List<ProjectStatus> = listOf(
    ProjectStatus("planning", "Planning"),
    ProjectStatus("active", "Active"),
    ProjectStatus("paused", "Paused"),
    ProjectStatus("shipped", "Shipped"),
    ProjectStatus("archived", "Archived"),
)

/** The order of the status filter chips on the Developer home (after "All"). */
val HOME_FILTER_ORDER: List<String> = listOf("active", "planning", "paused", "shipped", "archived")

/** Friendly label for a stored status value. */
fun projectStatusLabel(value: String): String =
    PROJECT_STATUSES.firstOrNull { it.value == value }?.label
        ?: value.replaceFirstChar { it.uppercase() }

/** Tech stack suggestions offered in the project editor. */
val TECH_SUGGESTIONS: List<String> = listOf(
    "Kotlin", "Jetpack Compose", "React", "TypeScript", "Node", "Supabase",
    "Firebase", "Cloudflare", "PostgreSQL", "Tailwind", "Flutter", "Python",
)

const val MAX_TECH_ITEMS = 12
const val MAX_TECH_LENGTH = 24

/** One kind of secret: the stored value and the label shown to the owner. */
data class SecretCategory(val value: String, val label: String)

/** Every secret category, in the order the editor offers them. */
val SECRET_CATEGORIES: List<SecretCategory> = listOf(
    SecretCategory("api_key", "API key"),
    SecretCategory("password", "Password"),
    SecretCategory("token", "Token"),
    SecretCategory("database", "Database"),
    SecretCategory("ssh_key", "SSH key"),
    SecretCategory("env", "Env variable"),
    SecretCategory("other", "Other"),
)

/** Friendly label for a stored secret category. */
fun secretCategoryLabel(value: String): String =
    SECRET_CATEGORIES.firstOrNull { it.value == value }?.label ?: "Other"

/** True for an empty link or one that starts with http:// or https://. */
fun isValidWebUrl(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")
}

/** Plain-text summary of a project for the "Copy project summary" action. */
fun buildProjectSummary(project: ProjectEntity): String {
    val lines = mutableListOf<String>()
    lines += project.name.trim().ifEmpty { "Untitled project" }
    lines += "Status: ${projectStatusLabel(project.status)}"
    if (project.description.isNotBlank()) lines += project.description.trim()
    if (project.techStack.isNotEmpty()) lines += "Stack: ${project.techStack.joinToString(", ")}"
    if (project.repoUrl.isNotBlank()) lines += "Repo: ${project.repoUrl.trim()}"
    if (project.liveUrl.isNotBlank()) lines += "Live: ${project.liveUrl.trim()}"
    return lines.joinToString("\n")
}
