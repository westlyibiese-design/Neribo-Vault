package com.westly.neribovault.feature.posts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.data.repository.PostsRepository
import com.westly.neribovault.feature.posts.reminders.PostReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
private const val DEFAULT_PLATFORM = "instagram"

/** The little "Saved" / "Saving…" label in the editor's top bar. */
enum class PostSaveStatus { Idle, Saving, Saved }

/** What the editor shows besides the three text fields, which the screen edits directly. */
data class PostEditorUiState(
    val isLoaded: Boolean = false,
    val notFound: Boolean = false,
    val platform: String = DEFAULT_PLATFORM,
    val status: String = STATUS_IDEA,
    val hashtags: List<String> = emptyList(),
    val scheduledAt: Long? = null,
    val postedAt: Long? = null,
    val updatedAt: Long? = null,
    val saveStatus: PostSaveStatus = PostSaveStatus.Idle,
)

private data class PostDraft(
    val title: String = "",
    val caption: String = "",
    val notes: String = "",
)

/**
 * Loads one post (or prepares a new one), debounces autosave by 600ms and flushes on demand.
 * A new post is only created once it has something in it, and a new post that ends up
 * completely empty is discarded. After every save the post's reminder is brought in line with
 * its status and time.
 */
class PostEditorViewModel(
    private val postId: String,
    private val repository: PostsRepository,
    private val appContext: Context,
) : ViewModel() {

    /** True when this editor was opened with the `new` route argument. */
    val isNew: Boolean = postId == PostsRoutes.NEW_POST_ID

    private val _state = MutableStateFlow(PostEditorUiState(isLoaded = isNew))
    val state: StateFlow<PostEditorUiState> = _state.asStateFlow()

    private val draft = MutableStateFlow(PostDraft())

    /** The latest title, even before it has been saved. */
    val currentTitle: String get() = draft.value.title

    /** The latest caption, even before it has been saved. */
    val currentCaption: String get() = draft.value.caption

    /** The latest private notes, even before they have been saved. */
    val currentNotes: String get() = draft.value.notes

    private var currentId: String? = if (isNew) null else postId
    private var createdAt: Long = 0L

    @Volatile
    private var dirty = false

    @Volatile
    private var deleted = false

    private val saveMutex = Mutex()
    private var saveJob: Job? = null

    // Writes must finish even when the screen is already gone and viewModelScope is cancelled,
    // so they run in their own scope. It only ever holds short database writes.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (!isNew) load()
    }

    private fun load() {
        viewModelScope.launch {
            val post = repository.getById(postId)
            if (post == null || post.isDeleted) {
                _state.update { it.copy(notFound = true) }
                return@launch
            }
            createdAt = post.createdAt
            draft.value = PostDraft(title = post.title, caption = post.caption, notes = post.notes)
            _state.update {
                it.copy(
                    isLoaded = true,
                    platform = post.platform,
                    status = post.status,
                    hashtags = post.hashtags,
                    scheduledAt = post.scheduledAt,
                    postedAt = post.postedAt,
                    updatedAt = post.updatedAt,
                )
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value == draft.value.title) return
        draft.update { it.copy(title = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onCaptionChange(value: String) {
        if (value == draft.value.caption) return
        draft.update { it.copy(caption = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun onNotesChange(value: String) {
        if (value == draft.value.notes) return
        draft.update { it.copy(notes = value) }
        scheduleSave(SAVE_DEBOUNCE_MS)
    }

    fun setPlatform(value: String) {
        if (value == _state.value.platform) return
        _state.update { it.copy(platform = value) }
        scheduleSave(0L)
    }

    /** Adds every hashtag found in [raw] (typed or pasted), skipping duplicates. */
    fun addHashtags(raw: String) {
        val incoming = splitHashtagInput(raw)
        if (incoming.isEmpty()) return
        var changed = false
        _state.update { current ->
            val merged = (current.hashtags + incoming).distinct()
            changed = merged.size != current.hashtags.size
            if (changed) current.copy(hashtags = merged) else current
        }
        if (changed) scheduleSave(0L)
    }

    fun removeHashtag(tag: String) {
        if (tag !in _state.value.hashtags) return
        _state.update { it.copy(hashtags = it.hashtags - tag) }
        scheduleSave(0L)
    }

    /**
     * Picks a status. Scheduled needs a time, so a missing or past one becomes tomorrow at
     * 6:30 PM. Posted stamps the posted time; any other status clears it.
     */
    fun setStatus(value: String) {
        if (value == _state.value.status) return
        val now = System.currentTimeMillis()
        _state.update { current ->
            when (value) {
                STATUS_SCHEDULED -> current.copy(
                    status = value,
                    scheduledAt = current.scheduledAt?.takeIf { it > now } ?: defaultScheduleMillis(now),
                    postedAt = null,
                )
                STATUS_POSTED -> current.copy(status = value, postedAt = current.postedAt ?: now)
                else -> current.copy(status = value, postedAt = null)
            }
        }
        scheduleSave(0L)
    }

    /** Sets the schedule time. An Idea or Draft becomes Scheduled. */
    fun setSchedule(epochMillis: Long) {
        _state.update { current ->
            val becomesScheduled = current.status == STATUS_IDEA || current.status == STATUS_DRAFT
            current.copy(
                scheduledAt = epochMillis,
                status = if (becomesScheduled) STATUS_SCHEDULED else current.status,
            )
        }
        scheduleSave(0L)
    }

    /** Removes the schedule time. A Scheduled post goes back to Draft. */
    fun clearSchedule() {
        _state.update { current ->
            current.copy(
                scheduledAt = null,
                status = if (current.status == STATUS_SCHEDULED) STATUS_DRAFT else current.status,
            )
        }
        scheduleSave(0L)
    }

    /** Marks the post as posted now. Saving cancels its reminder. */
    fun markPosted() {
        _state.update { it.copy(status = STATUS_POSTED, postedAt = System.currentTimeMillis()) }
        scheduleSave(0L)
    }

    /** Saves right now if anything changed. Called when the screen stops or is left. */
    fun flush() {
        saveJob?.cancel()
        if (dirty) saveScope.launch { persist() }
    }

    /**
     * Saves the post (so nothing is lost) and stores a draft copy of it. Reports the copy's id,
     * or null when there is nothing saved to copy yet.
     */
    fun duplicate(onDone: (newId: String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val copyId = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val source = currentId?.let { repository.getById(it) }
                    if (source == null) {
                        null
                    } else {
                        val copy = source.toDraftCopy()
                        repository.upsert(copy)
                        copy.id
                    }
                }
            }
            onDone(copyId)
        }
    }

    /**
     * Soft-deletes the post (saving any pending text first so Undo brings it back intact),
     * cancels its reminder and reports the deleted id, or null when nothing had been saved yet.
     */
    fun deletePost(onDone: (deletedId: String?) -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            val id = withContext(NonCancellable) {
                saveMutex.withLock {
                    persistLocked()
                    val target = currentId
                    if (target != null) {
                        repository.softDelete(target)
                        PostReminderScheduler.cancel(appContext, target)
                    }
                    deleted = true
                    target
                }
            }
            onDone(id)
        }
    }

    private fun scheduleSave(delayMs: Long) {
        dirty = true
        _state.update {
            if (it.saveStatus == PostSaveStatus.Saving) it else it.copy(saveStatus = PostSaveStatus.Saving)
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMs)
            persist()
        }
    }

    private suspend fun persist() {
        withContext(NonCancellable) {
            saveMutex.withLock { persistLocked() }
        }
    }

    /** Must be called with [saveMutex] held. Writes the latest draft if it changed. */
    private suspend fun persistLocked() {
        if (deleted || !dirty) return
        dirty = false
        val text = draft.value
        val meta = _state.value
        val existingId = currentId
        val isEmpty = text.title.isBlank() &&
            text.caption.isBlank() &&
            text.notes.isBlank() &&
            meta.hashtags.isEmpty()
        if (isEmpty && isNew) {
            if (existingId != null) {
                repository.deletePermanently(existingId)
                PostReminderScheduler.cancel(appContext, existingId)
                currentId = null
            }
            _state.update { it.copy(saveStatus = PostSaveStatus.Idle, updatedAt = null) }
            return
        }
        val now = System.currentTimeMillis()
        val id = existingId ?: newId().also {
            currentId = it
            createdAt = now
        }
        val entity = SocialPostEntity(
            id = id,
            createdAt = createdAt,
            updatedAt = now,
            platform = meta.platform,
            title = text.title,
            caption = text.caption,
            hashtags = meta.hashtags,
            status = meta.status,
            scheduledAt = meta.scheduledAt,
            postedAt = meta.postedAt,
            notes = text.notes,
        )
        repository.upsert(entity)
        PostReminderScheduler.sync(appContext, entity)
        _state.update {
            it.copy(
                saveStatus = if (dirty) PostSaveStatus.Saving else PostSaveStatus.Saved,
                updatedAt = now,
            )
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }
}
