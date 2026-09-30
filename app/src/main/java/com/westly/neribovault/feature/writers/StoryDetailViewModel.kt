package com.westly.neribovault.feature.writers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.StoryChapterEntity
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.repository.StoriesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The three tabs of the story screen. */
enum class StoryTab(val label: String) {
    Chapters("Chapters"),
    Characters("Characters"),
    Notes("Notes"),
}

/** Everything the story screen draws. */
data class StoryDetailUiState(
    val story: StoryEntity? = null,
    val chapters: List<StoryChapterEntity> = emptyList(),
    val tab: StoryTab = StoryTab.Chapters,
    val isLoading: Boolean = true,
) {
    val totalWords: Int get() = chapters.sumOf { it.wordCount }
}

/** State and chapter actions for one story. */
class StoryDetailViewModel(
    private val storyId: String,
    private val repository: StoriesRepository,
) : ViewModel() {

    private val tabFlow = MutableStateFlow(StoryTab.Chapters)

    val state: StateFlow<StoryDetailUiState> = combine(
        repository.observeById(storyId),
        repository.observeChapters(storyId),
        tabFlow,
    ) { story, chapters, tab ->
        StoryDetailUiState(story = story, chapters = chapters, tab = tab, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryDetailUiState())

    fun selectTab(tab: StoryTab) {
        tabFlow.value = tab
    }

    /** Moves a chapter one place up ([direction] -1) or down (+1) and rewrites the order. */
    fun moveChapter(chapterId: String, direction: Int) {
        val ids = state.value.chapters.map { it.id }.toMutableList()
        val from = ids.indexOf(chapterId)
        val to = from + direction
        if (from < 0 || to !in ids.indices) return
        val moved = ids.removeAt(from)
        ids.add(to, moved)
        viewModelScope.launch { repository.reorderChapters(storyId, ids) }
    }

    fun renameChapter(chapterId: String, title: String) {
        viewModelScope.launch {
            val chapter = repository.getChapterById(chapterId) ?: return@launch
            repository.upsertChapter(chapter.copy(title = title.trim()))
        }
    }

    fun deleteChapter(chapterId: String) {
        viewModelScope.launch { repository.softDeleteChapter(chapterId) }
    }

    fun restoreChapter(chapterId: String) {
        viewModelScope.launch { repository.restoreChapter(chapterId) }
    }

    /** Soft-deletes the story, then reports its id so the list can offer Undo. */
    fun deleteStory(onDone: (deletedId: String) -> Unit) {
        viewModelScope.launch {
            repository.softDelete(storyId)
            onDone(storyId)
        }
    }

    /** The whole story as plain text, or null when there is nothing to export yet. */
    fun exportText(): String? {
        val current = state.value
        val story = current.story ?: return null
        if (current.chapters.isEmpty()) return null
        return buildStoryExport(story.title, current.chapters)
    }
}
