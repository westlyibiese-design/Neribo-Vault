package com.westly.neribovault.core.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.graphics.vector.ImageVector
import com.westly.neribovault.core.navigation.Routes

/** Describes one vault for lists such as the Home screen. [id] matches the lock vault ids. */
data class VaultDefinition(
    val id: String,
    val name: String,
    val description: String,
    val route: String,
    val icon: ImageVector,
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
        ),
        VaultDefinition(
            id = "ideas",
            name = "Ideas",
            description = "Sparks worth keeping before they fade",
            route = Routes.IDEAS,
            icon = Icons.Outlined.Lightbulb,
        ),
        VaultDefinition(
            id = "goals",
            name = "Goals",
            description = "What you are working toward, step by step",
            route = Routes.GOALS,
            icon = Icons.Outlined.Flag,
        ),
        VaultDefinition(
            id = "diary",
            name = "Diary",
            description = "Your private daily pages",
            route = Routes.DIARY,
            icon = Icons.Outlined.Book,
        ),
        VaultDefinition(
            id = "writers",
            name = "Writers",
            description = "Stories, chapters, characters and ideas",
            route = Routes.WRITERS,
            icon = Icons.Outlined.Edit,
        ),
        VaultDefinition(
            id = "posts",
            name = "Posts",
            description = "Plan and schedule what you share online",
            route = Routes.POSTS,
            icon = Icons.Outlined.Public,
        ),
        VaultDefinition(
            id = "church",
            name = "Church",
            description = "Sermons, messages and study notes",
            route = Routes.CHURCH,
            icon = Icons.Outlined.Bookmark,
        ),
        VaultDefinition(
            id = "memories",
            name = "Memories",
            description = "Moments and people worth remembering",
            route = Routes.MEMORIES,
            icon = Icons.Outlined.Favorite,
        ),
        VaultDefinition(
            id = "documents",
            name = "Documents",
            description = "Important papers, with expiry reminders",
            route = Routes.DOCUMENTS,
            icon = Icons.Outlined.Folder,
        ),
        VaultDefinition(
            id = "developer",
            name = "Developer",
            description = "Projects, secrets, bugs and tasks",
            route = Routes.DEVELOPER,
            icon = Icons.Outlined.Code,
        ),
    )

    /** The vault with this [id], or null. */
    fun find(id: String): VaultDefinition? = all.firstOrNull { it.id == id }
}
