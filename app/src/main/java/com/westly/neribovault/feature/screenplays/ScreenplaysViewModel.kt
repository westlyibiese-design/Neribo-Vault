package com.westly.neribovault.feature.screenplays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.CheckResult
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import com.westly.neribovault.feature.screenplays.engine.ScriptPaginator
import com.westly.neribovault.feature.screenplays.engine.ScriptSamples
import com.westly.neribovault.feature.screenplays.engine.runEngineSelfTest
import com.westly.neribovault.feature.screenplays.engine.speakerOf
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The title shown (and stored for a new screenplay) when the owner leaves the title blank. */
const val UNTITLED_SCREENPLAY = "Untitled screenplay"

/** Page, scene and character totals of a script. */
data class ScriptCounts(
    val pages: Int,
    val scenes: Int,
    val characters: Int,
    val isEmpty: Boolean,
)

/** Lays [blocks] out and counts pages, scenes and distinct speakers (case-insensitive). */
fun countsOf(blocks: List<ScriptBlock>): ScriptCounts {
    val paginated = ScriptPaginator.paginate(blocks)
    val characters = blocks
        .filter { it.type == BlockType.CHARACTER }
        .map { speakerOf(it.text).uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()
        .size
    return ScriptCounts(
        pages = paginated.pageCount,
        scenes = paginated.scenes.size,
        characters = characters,
        isEmpty = blocks.isEmpty() || paginated.pageCount == 0,
    )
}

/** "1 page", "2 pages", "0 scenes". */
fun countLabel(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"

/** The quiet line on a card: "2 pages \u00B7 3 scenes", or "Empty". */
fun pagesAndScenesLabel(counts: ScriptCounts): String =
    if (counts.isEmpty) {
        "Empty"
    } else {
        countLabel(counts.pages, "page") + " \u00B7 " + countLabel(counts.scenes, "scene")
    }

/** One row of the list: the screenplay and its (cached) counts. */
data class ScreenplayListItem(val screenplay: ScreenplayEntity, val counts: ScriptCounts)

/** Which sample the debug menu adds. */
enum class DebugSample(val title: String) {
    A("Sample A: short"),
    B("Sample B: three pages"),
    C("Sample C: page-break test"),
}

/** Everything the Screenplays list screen draws. */
data class ScreenplaysUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val items: List<ScreenplayListItem> = emptyList(),
    val hasAny: Boolean = false,
    val defaultAuthor: String = "",
    val selfTest: List<CheckResult>? = null,
    val isLoading: Boolean = true,
)

/** State and actions for the Screenplays list. */
class ScreenplaysViewModel(private val repository: ScreenplaysRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val selfTestFlow = MutableStateFlow<List<CheckResult>?>(null)

    /** Counts by `id:updatedAt`, so scrolling and re-filtering never lay a script out again. */
    private val countsCache = ConcurrentHashMap<String, ScriptCounts>()

    private val allItems: Flow<List<ScreenplayListItem>> = repository.observeAll()
        .map { list -> list.map { screenplay -> ScreenplayListItem(screenplay, countsFor(screenplay)) } }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<ScreenplaysUiState> = combine(
        allItems,
        queryFlow,
        searchOpenFlow,
        selfTestFlow,
    ) { items, query, searchOpen, selfTest ->
        val needle = query.trim()
        val shown = if (needle.isEmpty()) {
            items
        } else {
            items.filter { item ->
                item.screenplay.title.contains(needle, ignoreCase = true) ||
                    item.screenplay.author.contains(needle, ignoreCase = true)
            }
        }
        ScreenplaysUiState(
            query = query,
            isSearchOpen = searchOpen,
            items = shown,
            hasAny = items.isNotEmpty(),
            defaultAuthor = items.firstOrNull()?.screenplay?.author.orEmpty(),
            selfTest = selfTest,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenplaysUiState())

    private fun countsFor(screenplay: ScreenplayEntity): ScriptCounts {
        if (countsCache.size > 200) countsCache.clear()
        val key = screenplay.id + ":" + screenplay.updatedAt
        return countsCache.getOrPut(key) { countsOf(Fountain.parse(screenplay.content)) }
    }

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

    /** Creates an empty screenplay and calls [onCreated] with its id once it is saved. */
    fun create(title: String, author: String, onCreated: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val screenplay = ScreenplayEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            title = title.trim().ifEmpty { UNTITLED_SCREENPLAY },
            author = author.trim(),
            contact = "",
            noticeEnabled = true,
            noticeText = "",
            content = "",
        )
        viewModelScope.launch {
            repository.upsert(screenplay)
            onCreated(screenplay.id)
        }
    }

    fun updateDetails(id: String, title: String, author: String) {
        viewModelScope.launch {
            val existing = repository.getById(id) ?: return@launch
            repository.upsert(existing.copy(title = title.trim(), author = author.trim()))
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            val source = repository.getById(id) ?: return@launch
            val now = System.currentTimeMillis()
            repository.upsert(
                source.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = "Copy of " + source.title.ifBlank { UNTITLED_SCREENPLAY },
                ),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    /** Debug menu: adds one of the three sample scripts as a new screenplay. */
    fun addSample(sample: DebugSample) {
        val now = System.currentTimeMillis()
        val content = when (sample) {
            DebugSample.A -> ScriptSamples.sampleA
            DebugSample.B -> ScriptSamples.sampleB
            DebugSample.C -> ScriptSamples.sampleC
        }
        viewModelScope.launch {
            repository.upsert(
                ScreenplayEntity(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    title = sample.title,
                    author = "Neribo",
                    contact = "",
                    noticeEnabled = true,
                    noticeText = "",
                    content = content,
                ),
            )
        }
    }

    /** Debug menu: runs the engine checks off the main thread and shows the result. */
    fun runSelfTest() {
        viewModelScope.launch(Dispatchers.Default) {
            selfTestFlow.value = runEngineSelfTest()
        }
    }

    fun dismissSelfTest() {
        selfTestFlow.value = null
    }
}
