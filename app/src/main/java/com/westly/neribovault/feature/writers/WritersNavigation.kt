package com.westly.neribovault.feature.writers

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.feature.writers.characters.CharacterEditorScreen
import com.westly.neribovault.feature.writers.ideas.WritingIdeasScreen
import com.westly.neribovault.feature.writers.notes.StoryNoteEditorScreen

/** Route names and argument keys for the Writers vault. */
object WritersRoutes {
    const val LIST = Routes.WRITERS
    const val STORY_DETAIL = "writers/story/detail/{storyId}"
    const val STORY_EDIT = "writers/story/edit/{storyId}"
    const val CHAPTER = "writers/chapter/{storyId}/{chapterId}"
    const val CHARACTER = "writers/character/{storyId}/{characterId}"
    const val STORY_NOTE = "writers/note/{storyId}/{noteId}"
    const val IDEAS = "writers/ideas"
    const val TRASH = "writers/trash"

    const val ARG_STORY_ID = "storyId"
    const val ARG_CHAPTER_ID = "chapterId"
    const val ARG_CHARACTER_ID = "characterId"
    const val ARG_NOTE_ID = "noteId"

    /** The id argument value that means "create a new item". */
    const val NEW_ID = "new"

    /** Key an editor uses to hand a just-deleted id back to the screen underneath it. */
    const val KEY_DELETED_ID = "deletedId"

    fun storyDetail(storyId: String) = "writers/story/detail/$storyId"
    fun storyEdit(storyId: String) = "writers/story/edit/$storyId"
    fun chapter(storyId: String, chapterId: String) = "writers/chapter/$storyId/$chapterId"
    fun character(storyId: String, characterId: String) = "writers/character/$storyId/$characterId"
    fun storyNote(storyId: String, noteId: String) = "writers/note/$storyId/$noteId"
}

/**
 * Registers the Writers vault screens: stories list, story detail, story editor, chapter
 * editor, Recently deleted, and the destinations that Phase 7 fills in (characters, story
 * notes and writing ideas) through the seam composables.
 */
fun NavGraphBuilder.writersGraph(navController: NavHostController) {
    composable(WritersRoutes.LIST) { entry ->
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(WritersRoutes.KEY_DELETED_ID, null)
        }
        WritersScreen(
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(WritersRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onOpenStory = { id ->
                navController.navigate(WritersRoutes.storyDetail(id)) { launchSingleTop = true }
            },
            onNewStory = {
                navController.navigate(WritersRoutes.storyEdit(WritersRoutes.NEW_ID)) {
                    launchSingleTop = true
                }
            },
            onEditStory = { id ->
                navController.navigate(WritersRoutes.storyEdit(id)) { launchSingleTop = true }
            },
            onOpenIdeas = {
                navController.navigate(WritersRoutes.IDEAS) { launchSingleTop = true }
            },
            onOpenTrash = {
                navController.navigate(WritersRoutes.TRASH) { launchSingleTop = true }
            },
        )
    }
    composable(
        route = WritersRoutes.STORY_DETAIL,
        arguments = listOf(navArgument(WritersRoutes.ARG_STORY_ID) { type = NavType.StringType }),
    ) { entry ->
        val storyId = entry.arguments?.getString(WritersRoutes.ARG_STORY_ID).orEmpty()
        val deletedIdFlow = remember(entry) {
            entry.savedStateHandle.getStateFlow<String?>(WritersRoutes.KEY_DELETED_ID, null)
        }
        StoryDetailScreen(
            storyId = storyId,
            deletedIdFlow = deletedIdFlow,
            onDeletedIdConsumed = {
                entry.savedStateHandle.set<String?>(WritersRoutes.KEY_DELETED_ID, null)
            },
            onBack = { navController.popBackStack() },
            onEditStory = {
                navController.navigate(WritersRoutes.storyEdit(storyId)) { launchSingleTop = true }
            },
            onOpenChapter = { chapterId ->
                navController.navigate(WritersRoutes.chapter(storyId, chapterId)) {
                    launchSingleTop = true
                }
            },
            onNewChapter = {
                navController.navigate(WritersRoutes.chapter(storyId, WritersRoutes.NEW_ID)) {
                    launchSingleTop = true
                }
            },
            onOpenCharacter = { characterId ->
                navController.navigate(WritersRoutes.character(storyId, characterId)) {
                    launchSingleTop = true
                }
            },
            onOpenNote = { noteId ->
                navController.navigate(WritersRoutes.storyNote(storyId, noteId)) {
                    launchSingleTop = true
                }
            },
            onStoryDeleted = { deletedId ->
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(WritersRoutes.KEY_DELETED_ID, deletedId)
                navController.popBackStack()
            },
        )
    }
    composable(
        route = WritersRoutes.STORY_EDIT,
        arguments = listOf(navArgument(WritersRoutes.ARG_STORY_ID) { type = NavType.StringType }),
    ) { entry ->
        val storyId = entry.arguments?.getString(WritersRoutes.ARG_STORY_ID)
            ?: WritersRoutes.NEW_ID
        StoryEditorScreen(
            storyId = storyId,
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
    composable(
        route = WritersRoutes.CHAPTER,
        arguments = listOf(
            navArgument(WritersRoutes.ARG_STORY_ID) { type = NavType.StringType },
            navArgument(WritersRoutes.ARG_CHAPTER_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val storyId = entry.arguments?.getString(WritersRoutes.ARG_STORY_ID).orEmpty()
        val chapterId = entry.arguments?.getString(WritersRoutes.ARG_CHAPTER_ID)
            ?: WritersRoutes.NEW_ID
        ChapterEditorScreen(
            storyId = storyId,
            chapterId = chapterId,
            onBack = { navController.popBackStack() },
            onOpenChapter = { targetId ->
                // Replace this editor with the neighbouring chapter so Back still returns to
                // the story.
                navController.navigate(WritersRoutes.chapter(storyId, targetId)) {
                    popUpTo(WritersRoutes.CHAPTER) { inclusive = true }
                }
            },
            onDeleted = { deletedId ->
                if (deletedId != null) {
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(WritersRoutes.KEY_DELETED_ID, deletedId)
                }
                navController.popBackStack()
            },
        )
    }
    composable(
        route = WritersRoutes.CHARACTER,
        arguments = listOf(
            navArgument(WritersRoutes.ARG_STORY_ID) { type = NavType.StringType },
            navArgument(WritersRoutes.ARG_CHARACTER_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val storyId = entry.arguments?.getString(WritersRoutes.ARG_STORY_ID).orEmpty()
        val characterId = entry.arguments?.getString(WritersRoutes.ARG_CHARACTER_ID)
            ?: WritersRoutes.NEW_ID
        CharacterEditorScreen(
            storyId = storyId,
            characterId = characterId,
            onBack = { navController.popBackStack() },
        )
    }
    composable(
        route = WritersRoutes.STORY_NOTE,
        arguments = listOf(
            navArgument(WritersRoutes.ARG_STORY_ID) { type = NavType.StringType },
            navArgument(WritersRoutes.ARG_NOTE_ID) { type = NavType.StringType },
        ),
    ) { entry ->
        val storyId = entry.arguments?.getString(WritersRoutes.ARG_STORY_ID).orEmpty()
        val noteId = entry.arguments?.getString(WritersRoutes.ARG_NOTE_ID)
            ?: WritersRoutes.NEW_ID
        StoryNoteEditorScreen(
            storyId = storyId,
            noteId = noteId,
            onBack = { navController.popBackStack() },
        )
    }
    composable(WritersRoutes.IDEAS) {
        WritingIdeasScreen(onBack = { navController.popBackStack() })
    }
    composable(WritersRoutes.TRASH) {
        WritersTrashScreen(onBack = { navController.popBackStack() })
    }
}
