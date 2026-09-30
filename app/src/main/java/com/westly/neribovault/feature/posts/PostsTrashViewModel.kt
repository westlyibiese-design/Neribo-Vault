package com.westly.neribovault.feature.posts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.SocialPostEntity
import com.westly.neribovault.data.repository.PostsRepository
import com.westly.neribovault.feature.posts.reminders.PostReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The deleted posts, most recently deleted first. */
data class PostsTrashUiState(
    val posts: List<SocialPostEntity> = emptyList(),
    val isLoading: Boolean = true,
)

/** State and actions for the Posts "Recently deleted" screen. */
class PostsTrashViewModel(
    private val repository: PostsRepository,
    private val appContext: Context,
) : ViewModel() {

    val state: StateFlow<PostsTrashUiState> = repository.observeTrashed()
        .map { posts -> PostsTrashUiState(posts = posts, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostsTrashUiState())

    /** Restores the post and recreates its reminder when it is scheduled for the future. */
    fun restore(id: String) {
        viewModelScope.launch {
            repository.restore(id)
            repository.getById(id)?.let { PostReminderScheduler.sync(appContext, it) }
        }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch {
            repository.deletePermanently(id)
            PostReminderScheduler.cancel(appContext, id)
        }
    }

    /** Permanently deletes every post that is in the trash right now. */
    fun emptyTrash() {
        viewModelScope.launch {
            repository.observeTrashed().first().forEach { post ->
                repository.deletePermanently(post.id)
                PostReminderScheduler.cancel(appContext, post.id)
            }
        }
    }
}
