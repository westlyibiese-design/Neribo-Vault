package com.westly.neribovault.feature.screenplays.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.engine.defaultNoticeText
import java.time.LocalDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_DEBOUNCE_MS = 600L

/**
 * What the Title page screen draws. [noticeText] is the stored value ("" means "use the default").
 * [noticeField] is what the notice field shows right now.
 */
data class TitlePageUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val author: String = "",
    val contact: String = "",
    val noticeEnabled: Boolean = true,
    val noticeText: String = "",
    val noticeField: String = "",
    val year: Int = 0,
    val isSaving: Boolean = false,
) {
    /** The notice that will print: the stored text, or the default when that is blank. */
    val effectiveNotice: String
        get() = if (noticeText.isBlank()) defaultNoticeText(author, year) else noticeText
}

/**
 * Edits the title page fields and the copyright notice of one screenplay. Changes are saved after a
 * 600 ms pause and when the screen stops. Only the title page columns are written; `content` is
 * always taken from the latest saved entity, so the script text is never touched.
 */
class TitlePageViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
) : ViewModel() {

    private val year: Int = LocalDate.now().year
    private val _state = MutableStateFlow(TitlePageUiState(year = year))
    val state: StateFlow<TitlePageUiState> = _state.asStateFlow()

    private var loaded = false
    private var dirty = false
    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch {
            val entity = repository.getById(screenplayId)
            if (entity == null) {
                _state.update { it.copy(isLoading = false, notFound = true) }
            } else {
                loaded = true
                _state.update {
                    it.copy(
                        isLoading = false,
                        title = entity.title,
                        author = entity.author,
                        contact = entity.contact,
                        noticeEnabled = entity.noticeEnabled,
                        noticeText = entity.noticeText,
                        noticeField = entity.noticeText.ifBlank { defaultNoticeText(entity.author, year) },
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    /** While the notice is the default, the field follows the author's name. */
    fun onAuthorChange(value: String) = edit { current ->
        val followsDefault = current.noticeText.isBlank() && current.noticeField.isNotBlank()
        current.copy(
            author = value,
            noticeField = if (followsDefault) defaultNoticeText(value, year) else current.noticeField,
        )
    }

    fun onContactChange(value: String) = edit { it.copy(contact = value) }

    fun onNoticeEnabledChange(value: Boolean) = edit { it.copy(noticeEnabled = value) }

    /** Typing in the notice field. Blank, or exactly the default, is stored as "" (use the default). */
    fun onNoticeChange(value: String) = edit { current ->
        val isDefault = value == defaultNoticeText(current.author, year)
        current.copy(
            noticeField = value,
            noticeText = if (value.isBlank() || isDefault) "" else value,
        )
    }

    /** Stores a blank notice (the default) and refreshes the field. */
    fun resetNotice() = edit { current ->
        current.copy(noticeText = "", noticeField = defaultNoticeText(current.author, year))
    }

    private fun edit(change: (TitlePageUiState) -> TitlePageUiState) {
        if (!loaded) return
        _state.update { change(it).copy(isSaving = true) }
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist()
        }
    }

    /** Saves right now without waiting for the pause. Safe to call from lifecycle callbacks. */
    fun flush() {
        saveJob?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { persist() }
    }

    private suspend fun persist() {
        if (!loaded) return
        withContext(NonCancellable) {
            saveMutex.withLock {
                if (dirty) {
                    dirty = false
                    val shown = _state.value
                    val entity = repository.getById(screenplayId)
                    if (entity != null) {
                        val updated = entity.copy(
                            title = shown.title,
                            author = shown.author,
                            contact = shown.contact,
                            noticeEnabled = shown.noticeEnabled,
                            noticeText = shown.noticeText,
                        )
                        if (updated != entity) repository.upsert(updated)
                    }
                }
                if (!dirty) _state.update { it.copy(isSaving = false) }
            }
        }
    }
}
