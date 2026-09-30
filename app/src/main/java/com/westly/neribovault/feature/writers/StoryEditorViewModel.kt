package com.westly.neribovault.feature.writers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.StoryEntity
import com.westly.neribovault.data.repository.StoriesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The fields of the story form. [targetWordCount] is kept as typed digits. */
data class StoryForm(
    val title: String = "",
    val synopsis: String = "",
    val genre: String = StoryOptions.DEFAULT_GENRE,
    val status: String = StoryOptions.DEFAULT_STATUS,
    val targetWordCount: String = "",
)

/** What the story form needs from the ViewModel. The form itself lives in the screen. */
data class StoryEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    /** The values the form started with, used to tell whether anything changed. */
    val initial: StoryForm = StoryForm(),
    val isSaving: Boolean = false,
)

/**
 * Loads one story (or prepares a new one) and saves it when asked. There is no autosave: story
 * setup is a deliberate action, so nothing is written until [save].
 */
class StoryEditorViewModel(
    private val storyId: String,
    private val repository: StoriesRepository,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = storyId == WritersRoutes.NEW_ID

    private val _state = MutableStateFlow(StoryEditorUiState(isLoaded = isNew))
    val state: StateFlow<StoryEditorUiState> = _state.asStateFlow()

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val story = repository.getById(storyId)
            if (story == null || story.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    isLoaded = true,
                    initial = StoryForm(
                        title = story.title,
                        synopsis = story.synopsis,
                        genre = story.genre,
                        status = story.status,
                        targetWordCount = story.targetWordCount?.toString().orEmpty(),
                    ),
                )
            }
        }
    }

    /**
     * Saves [form] and then calls [onSaved] with the story's id. A blank title is not saved.
     * Keeps everything the form does not edit (creation time) exactly as it was.
     */
    fun save(form: StoryForm, onSaved: (savedId: String) -> Unit) {
        if (_state.value.isSaving || form.title.isBlank()) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = if (isNew) null else repository.getById(storyId)
            val id = existing?.id ?: newId()
            val target = form.targetWordCount.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
            repository.upsert(
                StoryEntity(
                    id = id,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    isDeleted = existing?.isDeleted ?: false,
                    deletedAt = existing?.deletedAt,
                    title = form.title.trim(),
                    synopsis = form.synopsis.trim(),
                    genre = form.genre,
                    status = form.status,
                    targetWordCount = target,
                ),
            )
            _state.update { it.copy(isSaving = false) }
            onSaved(id)
        }
    }
}
