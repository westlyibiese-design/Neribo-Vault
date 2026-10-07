package com.westly.neribovault.core.search

import com.westly.neribovault.core.di.AppContainer
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.core.vault.VaultCatalog
import com.westly.neribovault.feature.church.ChurchRoutes
import com.westly.neribovault.feature.developer.DeveloperRoutes
import com.westly.neribovault.feature.diary.DiaryRoutes
import com.westly.neribovault.feature.documents.DocumentsRoutes
import com.westly.neribovault.feature.goals.GoalsRoutes
import com.westly.neribovault.feature.ideas.IdeasRoutes
import com.westly.neribovault.feature.lyrics.LyricsRoutes
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.memories.MemoriesRoutes
import com.westly.neribovault.feature.notes.NotesRoutes
import com.westly.neribovault.feature.posts.PostsRoutes
import com.westly.neribovault.feature.screenplays.ScreenplaysRoutes
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.writers.WritersRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One searchable thing in any vault. [body] is searchable text and is never shown in full.
 * [route] is ready to pass to `navController.navigate`.
 */
data class IndexedItem(
    val vaultId: String,
    val vaultLabel: String,
    val kind: String,
    val id: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
    val route: String,
)

/**
 * Reads every searchable item of every vault from the repositories. Used by Search and Recent.
 * Secrets, file paths and the contents of hidden (locked) vaults are never read into the index.
 */
class GlobalIndex(private val container: AppContainer) {

    /**
     * Loads every searchable item (one-shot, off the main thread). Vaults whose id is in
     * [hiddenVaults] are skipped completely, so their contents never reach memory here.
     */
    suspend fun load(hiddenVaults: Set<String>): List<IndexedItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<IndexedItem>()
        if (VAULT_NOTES !in hiddenVaults) loadNotes(items)
        if (VAULT_IDEAS !in hiddenVaults) loadIdeas(items)
        if (VAULT_GOALS !in hiddenVaults) loadGoals(items)
        if (VAULT_DIARY !in hiddenVaults) loadDiary(items)
        if (VAULT_WRITERS !in hiddenVaults) loadWriters(items)
        if (VAULT_SCREENPLAYS !in hiddenVaults) loadScreenplays(items)
        if (VAULT_LYRICS !in hiddenVaults) loadSongs(items)
        if (VAULT_POSTS !in hiddenVaults) loadPosts(items)
        if (VAULT_CHURCH !in hiddenVaults) loadChurch(items)
        if (VAULT_MEMORIES !in hiddenVaults) loadMemories(items)
        if (VAULT_DOCUMENTS !in hiddenVaults) loadDocuments(items)
        if (VAULT_DEVELOPER !in hiddenVaults) loadDeveloper(items)
        items
    }

    private suspend fun loadNotes(out: MutableList<IndexedItem>) {
        container.notesRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { note ->
                out.add(
                    item(
                        vaultId = VAULT_NOTES,
                        kind = "Note",
                        id = note.id,
                        title = titleOrFallback(note.title, note.body, "Untitled note"),
                        body = joinText(note.body, note.tags.joinToString(" ")),
                        updatedAt = note.updatedAt,
                        route = NotesRoutes.editor(note.id),
                    ),
                )
            }
    }

    private suspend fun loadIdeas(out: MutableList<IndexedItem>) {
        container.ideasRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { idea ->
                out.add(
                    item(
                        vaultId = VAULT_IDEAS,
                        kind = "Idea",
                        id = idea.id,
                        title = titleOrFallback(idea.title, idea.description, "Untitled idea"),
                        body = joinText(idea.description, idea.tags.joinToString(" ")),
                        updatedAt = idea.updatedAt,
                        route = IdeasRoutes.editor(idea.id),
                    ),
                )
            }
    }

    private suspend fun loadGoals(out: MutableList<IndexedItem>) {
        container.goalsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { goal ->
                out.add(
                    item(
                        vaultId = VAULT_GOALS,
                        kind = "Goal",
                        id = goal.id,
                        title = titleOrFallback(goal.title, goal.description, "Untitled goal"),
                        body = goal.description,
                        updatedAt = goal.updatedAt,
                        route = GoalsRoutes.detail(goal.id),
                    ),
                )
            }
    }

    private suspend fun loadDiary(out: MutableList<IndexedItem>) {
        container.diaryRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { entry ->
                val title = entry.title.trim().ifEmpty { formatDate(entry.entryDate) }
                out.add(
                    item(
                        vaultId = VAULT_DIARY,
                        kind = "Diary entry",
                        id = entry.id,
                        title = title,
                        body = joinText(entry.body, entry.tags.joinToString(" ")),
                        updatedAt = entry.updatedAt,
                        route = DiaryRoutes.entry(entry.id),
                    ),
                )
            }
    }

    private suspend fun loadWriters(out: MutableList<IndexedItem>) {
        val stories = container.storiesRepository.observeAll().first().filter { !it.isDeleted }
        val liveStoryIds = HashSet<String>()
        for (story in stories) {
            liveStoryIds.add(story.id)
            out.add(
                item(
                    vaultId = VAULT_WRITERS,
                    kind = "Story",
                    id = story.id,
                    title = titleOrFallback(story.title, story.synopsis, "Untitled story"),
                    body = joinText(story.synopsis, story.genre),
                    updatedAt = story.updatedAt,
                    route = WritersRoutes.storyDetail(story.id),
                ),
            )
            container.storiesRepository.observeChapters(story.id).first()
                .filter { !it.isDeleted }
                .forEach { chapter ->
                    out.add(
                        item(
                            vaultId = VAULT_WRITERS,
                            kind = "Chapter",
                            id = chapter.id,
                            title = titleOrFallback(chapter.title, chapter.body, "Untitled chapter"),
                            body = chapter.body,
                            updatedAt = chapter.updatedAt,
                            route = WritersRoutes.chapter(story.id, chapter.id),
                        ),
                    )
                }
        }
        // Characters and notes of a trashed story stay out of the index with their story.
        container.storyCharactersRepository.observeAll().first()
            .filter { !it.isDeleted && it.storyId in liveStoryIds }
            .forEach { character ->
                out.add(
                    item(
                        vaultId = VAULT_WRITERS,
                        kind = "Character",
                        id = character.id,
                        title = titleOrFallback(character.name, character.description, "Unnamed character"),
                        body = joinText(
                            character.role,
                            character.description,
                            character.traits.joinToString(" "),
                            character.backstory,
                        ),
                        updatedAt = character.updatedAt,
                        route = WritersRoutes.character(character.storyId, character.id),
                    ),
                )
            }
        container.storyNotesRepository.observeAll().first()
            .filter { !it.isDeleted && it.storyId in liveStoryIds }
            .forEach { note ->
                out.add(
                    item(
                        vaultId = VAULT_WRITERS,
                        kind = "Story note",
                        id = note.id,
                        title = titleOrFallback(note.title, note.body, "Untitled note"),
                        body = joinText(note.body, note.category),
                        updatedAt = note.updatedAt,
                        route = WritersRoutes.storyNote(note.storyId, note.id),
                    ),
                )
            }
        container.writingIdeasRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { idea ->
                out.add(
                    item(
                        vaultId = VAULT_WRITERS,
                        kind = "Writing idea",
                        id = idea.id,
                        title = titleOrFallback(idea.title, idea.body, "Untitled idea"),
                        body = joinText(idea.body, idea.genre.orEmpty()),
                        updatedAt = idea.updatedAt,
                        route = WritersRoutes.IDEAS,
                    ),
                )
            }
    }

    /** Title, author and the text of every block of the script. Contact details are never indexed. */
    private suspend fun loadScreenplays(out: MutableList<IndexedItem>) {
        container.screenplaysRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { screenplay ->
                val script = Fountain.parse(screenplay.content)
                    .map { it.text }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                out.add(
                    item(
                        vaultId = VAULT_SCREENPLAYS,
                        kind = "Screenplay",
                        id = screenplay.id,
                        title = screenplay.title.trim().ifEmpty { "Untitled screenplay" },
                        body = screenplay.author + " " + script,
                        updatedAt = screenplay.updatedAt,
                        route = ScreenplaysRoutes.editor(screenplay.id),
                    ),
                )
            }
    }

    /** Title, writer and every lyric line. The private notes are never indexed. */
    private suspend fun loadSongs(out: MutableList<IndexedItem>) {
        container.songsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { song ->
                val lyrics = LyricsFormat.parse(song.content)
                    .flatMap { section -> section.text.split('\n') }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                out.add(
                    item(
                        vaultId = VAULT_LYRICS,
                        kind = "Song",
                        id = song.id,
                        title = song.title.trim().ifEmpty { "Untitled song" },
                        body = song.writer + " " + lyrics,
                        updatedAt = song.updatedAt,
                        route = LyricsRoutes.editor(song.id),
                    ),
                )
            }
    }

    private suspend fun loadPosts(out: MutableList<IndexedItem>) {
        container.postsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { post ->
                out.add(
                    item(
                        vaultId = VAULT_POSTS,
                        kind = "Post",
                        id = post.id,
                        title = titleOrFallback(post.title, post.caption, "Untitled post"),
                        body = joinText(post.caption, post.hashtags.joinToString(" "), post.notes),
                        updatedAt = post.updatedAt,
                        route = PostsRoutes.editor(post.id),
                    ),
                )
            }
    }

    private suspend fun loadChurch(out: MutableList<IndexedItem>) {
        container.churchRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { record ->
                out.add(
                    item(
                        vaultId = VAULT_CHURCH,
                        kind = "Church record",
                        id = record.id,
                        title = titleOrFallback(record.title, record.summary, "Untitled record"),
                        body = joinText(
                            record.speaker,
                            record.church,
                            record.scriptureRefs,
                            record.summary,
                            record.notes,
                            record.tags.joinToString(" "),
                        ),
                        updatedAt = record.updatedAt,
                        route = ChurchRoutes.editor(record.id),
                    ),
                )
            }
    }

    private suspend fun loadMemories(out: MutableList<IndexedItem>) {
        // Photo paths are never indexed.
        container.memoriesRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { memory ->
                out.add(
                    item(
                        vaultId = VAULT_MEMORIES,
                        kind = "Memory",
                        id = memory.id,
                        title = titleOrFallback(memory.title, memory.description, "Untitled memory"),
                        body = joinText(
                            memory.description,
                            memory.location,
                            memory.people.joinToString(" "),
                            memory.tags.joinToString(" "),
                        ),
                        updatedAt = memory.updatedAt,
                        route = MemoriesRoutes.detail(memory.id),
                    ),
                )
            }
    }

    private suspend fun loadDocuments(out: MutableList<IndexedItem>) {
        // The attached file path is never indexed.
        container.personalDocumentsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { document ->
                out.add(
                    item(
                        vaultId = VAULT_DOCUMENTS,
                        kind = "Document",
                        id = document.id,
                        title = titleOrFallback(document.title, document.notes, "Untitled document"),
                        body = joinText(document.category, document.issuer, document.notes),
                        updatedAt = document.updatedAt,
                        route = DocumentsRoutes.detail(document.id),
                    ),
                )
            }
    }

    /** Projects, bugs, tasks, plans, folder plans, prompts and project documents. Never secrets. */
    private suspend fun loadDeveloper(out: MutableList<IndexedItem>) {
        container.projectsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { project ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Project",
                        id = project.id,
                        title = titleOrFallback(project.name, project.description, "Untitled project"),
                        body = joinText(project.description, project.techStack.joinToString(" ")),
                        updatedAt = project.updatedAt,
                        route = DeveloperRoutes.projectDetail(project.id),
                    ),
                )
            }
        container.bugsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { bug ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Bug",
                        id = bug.id,
                        title = titleOrFallback(bug.title, bug.description, "Untitled bug"),
                        body = joinText(bug.description, bug.stepsToReproduce, bug.resolution),
                        updatedAt = bug.updatedAt,
                        route = DeveloperRoutes.bug(bug.projectId ?: DeveloperRoutes.NONE, bug.id),
                    ),
                )
            }
        container.tasksRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { task ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Task",
                        id = task.id,
                        title = titleOrFallback(task.title, task.notes, "Untitled task"),
                        body = task.notes,
                        updatedAt = task.updatedAt,
                        route = DeveloperRoutes.task(task.projectId, task.id),
                    ),
                )
            }
        container.planningDocsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { doc ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Plan",
                        id = doc.id,
                        title = titleOrFallback(doc.title, doc.body, "Untitled plan"),
                        body = doc.body,
                        updatedAt = doc.updatedAt,
                        route = DeveloperRoutes.plan(doc.projectId ?: DeveloperRoutes.NONE, doc.id),
                    ),
                )
            }
        container.folderPlansRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { plan ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Folder plan",
                        id = plan.id,
                        title = titleOrFallback(plan.title, plan.treeText, "Untitled folder plan"),
                        body = plan.treeText,
                        updatedAt = plan.updatedAt,
                        route = DeveloperRoutes.folderPlan(plan.projectId ?: DeveloperRoutes.NONE, plan.id),
                    ),
                )
            }
        container.promptsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { prompt ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Prompt",
                        id = prompt.id,
                        title = titleOrFallback(prompt.title, prompt.body, "Untitled prompt"),
                        body = joinText(prompt.body, prompt.category),
                        updatedAt = prompt.updatedAt,
                        route = DeveloperRoutes.prompt(prompt.projectId, prompt.id),
                    ),
                )
            }
        container.projectDocumentsRepository.observeAll().first()
            .filter { !it.isDeleted }
            .forEach { doc ->
                out.add(
                    item(
                        vaultId = VAULT_DEVELOPER,
                        kind = "Project doc",
                        id = doc.id,
                        title = titleOrFallback(doc.title, doc.body, "Untitled document"),
                        body = doc.body,
                        updatedAt = doc.updatedAt,
                        route = DeveloperRoutes.doc(doc.projectId ?: DeveloperRoutes.NONE, doc.id),
                    ),
                )
            }
    }

    private fun item(
        vaultId: String,
        kind: String,
        id: String,
        title: String,
        body: String,
        updatedAt: Long,
        route: String,
    ): IndexedItem = IndexedItem(
        vaultId = vaultId,
        vaultLabel = VaultCatalog.find(vaultId)?.name ?: vaultId,
        kind = kind,
        id = id,
        title = title,
        body = body,
        updatedAt = updatedAt,
        route = route,
    )

    private companion object {
        const val VAULT_NOTES = "notes"
        const val VAULT_IDEAS = "ideas"
        const val VAULT_GOALS = "goals"
        const val VAULT_DIARY = "diary"
        const val VAULT_WRITERS = "writers"
        const val VAULT_SCREENPLAYS = "screenplays"
        const val VAULT_LYRICS = "lyrics"
        const val VAULT_POSTS = "posts"
        const val VAULT_CHURCH = "church"
        const val VAULT_MEMORIES = "memories"
        const val VAULT_DOCUMENTS = "documents"
        const val VAULT_DEVELOPER = "developer"

        /** Longest title used when the real title is empty and the start of the text stands in. */
        const val FALLBACK_TITLE_CHARS = 60

        /** The trimmed [raw] title, else the start of [fallbackText], else [placeholder]. */
        fun titleOrFallback(raw: String, fallbackText: String, placeholder: String): String {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) return trimmed
            val fromText = snippet(fallbackText, FALLBACK_TITLE_CHARS)
            return fromText.ifEmpty { placeholder }
        }

        /** Joins the non-blank [parts] with line breaks. */
        fun joinText(vararg parts: String): String =
            parts.filter { it.isNotBlank() }.joinToString("\n")
    }
}
