package com.westly.neribovault.feature.posts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.data.repository.PostsRepository
import com.westly.neribovault.feature.posts.reminders.PostReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The two tabs at the top of the Posts list. */
enum class PostsTab { Pipeline, Upcoming }

/** Everything the Posts list screen draws. */
data class PostsUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val tab: PostsTab = PostsTab.Pipeline,
    /** Stored status value used to filter the pipeline, or null for All. */
    val status: String? = null,
    /** Pipeline posts, most recently updated first, after the status filter. */
    val pipeline: List<SocialPostEntity> = emptyList(),
    /** Scheduled posts, earliest first. The screen groups them by day. */
    val scheduled: List<SocialPostEntity> = emptyList(),
    val isLoading: Boolean = true,
)

private data class PostControls(
    val query: String,
    val isSearchOpen: Boolean,
    val tab: PostsTab,
    val status: String?,
)

/** State and actions for the Posts list. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostsViewModel(
    private val repository: PostsRepository,
    private val appContext: Context,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val tabFlow = MutableStateFlow(PostsTab.Pipeline)
    private val statusFlow = MutableStateFlow<String?>(null)

    private val controls: Flow<PostControls> = combine(
        queryFlow,
        searchOpenFlow,
        tabFlow,
        statusFlow,
    ) { query, open, tab, status ->
        PostControls(query = query, isSearchOpen = open, tab = tab, status = status)
    }

    private val trimmedQuery: Flow<String> = queryFlow.map { it.trim() }.distinctUntilChanged()

    private val allPosts: Flow<List<SocialPostEntity>> = trimmedQuery.flatMapLatest { query ->
        if (query.isEmpty()) repository.observeAll() else repository.search(query)
    }

    private val scheduledPosts: Flow<List<SocialPostEntity>> = trimmedQuery.flatMapLatest { query ->
        if (query.isEmpty()) {
            repository.observeScheduled()
        } else {
            repository.search(query).map { list ->
                list.filter { it.status == STATUS_SCHEDULED && it.scheduledAt != null }
                    .sortedBy { it.scheduledAt ?: Long.MAX_VALUE }
            }
        }
    }

    val state: StateFlow<PostsUiState> = combine(controls, allPosts, scheduledPosts) { c, all, scheduled ->
        PostsUiState(
            query = c.query,
            isSearchOpen = c.isSearchOpen,
            tab = c.tab,
            status = c.status,
            pipeline = all.filter { c.status == null || it.status == c.status },
            scheduled = scheduled,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostsUiState())

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    fun selectTab(tab: PostsTab) {
        tabFlow.value = tab
    }

    fun selectStatus(status: String?) {
        statusFlow.value = status
    }

    /** Soft-deletes the post and cancels its reminder. */
    fun delete(id: String) {
        viewModelScope.launch {
            repository.softDelete(id)
            PostReminderScheduler.cancel(appContext, id)
        }
    }

    /** Brings a post back from Recently deleted and recreates its reminder if it needs one. */
    fun restore(id: String) {
        viewModelScope.launch {
            repository.restore(id)
            repository.getById(id)?.let { PostReminderScheduler.sync(appContext, it) }
        }
    }

    /** Marks the post as posted now and cancels its reminder. */
    fun markPosted(post: SocialPostEntity) {
        viewModelScope.launch {
            repository.setStatus(post.id, STATUS_POSTED, System.currentTimeMillis())
            PostReminderScheduler.cancel(appContext, post.id)
        }
    }

    /** Saves a copy of the post as a draft, with no schedule and no posted date. */
    fun duplicate(post: SocialPostEntity) {
        viewModelScope.launch { repository.upsert(post.toDraftCopy()) }
    }
}

/** A draft copy of this post: new id, no schedule, not posted. */
internal fun SocialPostEntity.toDraftCopy(): SocialPostEntity {
    val now = System.currentTimeMillis()
    return SocialPostEntity(
        id = newId(),
        createdAt = now,
        updatedAt = now,
        platform = platform,
        title = if (title.isBlank()) "" else "${title.trim()} (copy)",
        caption = caption,
        hashtags = hashtags,
        status = STATUS_DRAFT,
        scheduledAt = null,
        postedAt = null,
        notes = notes,
    )
}
