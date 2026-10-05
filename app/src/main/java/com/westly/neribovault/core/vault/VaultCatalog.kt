package com.westly.neribovault.core.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector
import com.westly.neribovault.core.navigation.Routes

/** Describes one vault for lists such as the Home screen. [id] matches the lock vault ids. */
data class VaultDefinition(
    val id: String,
    val name: String,
    val description: String,
    val route: String,
    val icon: ImageVector,
    val tagline: String = "",
)

/** Every vault in the app, in display order. */
object VaultCatalog {
    val all: List<VaultDefinition> = listOf(
        VaultDefinition(
            id = "notes",
            name = "Notes",
            description = "Quick thoughts, lists and everything you jot down",
            route = Routes.NOTES,
            icon = Icons.Outlined.Description,
            tagline = "Quick thoughts",
        ),
        VaultDefinition(
            id = "ideas",
            name = "Ideas",
            description = "Sparks worth keeping before they fade",
            route = Routes.IDEAS,
            icon = Icons.Outlined.Lightbulb,
            tagline = "Sparks to keep",
        ),
        VaultDefinition(
            id = "goals",
            name = "Goals",
            description = "What you are working toward, step by step",
            route = Routes.GOALS,
            icon = Icons.Outlined.TrackChanges,
            tagline = "Step by step",
        ),
        VaultDefinition(
            id = "diary",
            name = "Diary",
            description = "Your private daily pages",
            route = Routes.DIARY,
            icon = Icons.Outlined.CalendarMonth,
            tagline = "Daily pages",
        ),
        VaultDefinition(
            id = "writers",
            name = "Writers",
            description = "Stories, chapters, characters and ideas",
            route = Routes.WRITERS,
            icon = Icons.Outlined.Edit,
            tagline = "Stories and more",
        ),
        VaultDefinition(
            id = "screenplays",
            name = "Screenplays",
            description = "Write scenes and scripts in proper screenplay format",
            route = Routes.SCREENPLAYS,
            icon = Icons.Outlined.Movie,
            tagline = "Scenes and scripts",
        ),
        VaultDefinition(
            id = "posts",
            name = "Posts",
            description = "Plan and schedule what you share online",
            route = Routes.POSTS,
            icon = Icons.Outlined.Campaign,
            tagline = "Plan and schedule",
        ),
        VaultDefinition(
            id = "church",
            name = "Church",
            description = "Sermons, messages and study notes",
            route = Routes.CHURCH,
            icon = Icons.Outlined.Bookmark,
            tagline = "Sermons and study",
        ),
        VaultDefinition(
            id = "memories",
            name = "Memories",
            description = "Moments and people worth remembering",
            route = Routes.MEMORIES,
            icon = Icons.Outlined.Image,
            tagline = "Moments kept",
        ),
        VaultDefinition(
            id = "documents",
            name = "Documents",
            description = "Important papers, with expiry reminders",
            route = Routes.DOCUMENTS,
            icon = Icons.Outlined.Folder,
            tagline = "Papers and expiry",
        ),
        VaultDefinition(
            id = "developer",
            name = "Developer",
            description = "Projects, secrets, bugs and tasks",
            route = Routes.DEVELOPER,
            icon = Icons.Outlined.Code,
            tagline = "Projects and tasks",
        ),
    )

    /** The vault with this [id], or null. */
    fun find(id: String): VaultDefinition? = all.firstOrNull { it.id == id }
}
